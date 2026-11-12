package com.letvcast.player;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 极简 SMB2 / SMB3 客户端（纯 Java，零第三方依赖，Android 4.3 可跑）。
 *
 * <p>为什么需要自己写：jcifs 1.3.x 只会 SMB1（CIFS）。macOS 13 起系统共享服务
 * 彻底移除了 SMB1，Windows 10/11 也默认关闭 SMB1，所以旧库连不上现代机器。
 *
 * <p>按 MS-SMB2 实现：NEGOTIATE / SESSION_SETUP(NTLMv2) / TREE_CONNECT /
 * CREATE / QUERY_DIRECTORY / READ / CLOSE，并同时实现
 * SMB 2.x 的 HMAC-SHA256 签名与 SMB 3.x 的 AES-CMAC 签名（含 SP800-108 KDF）。
 *
 * <p>不做的事：加密（不声明加密能力，服务器就不会加密）、DFS、多通道、压缩。
 * 这些对「局域网里放个电影」没有意义，反而会拖慢老机器。
 */
public class Smb2Client {

    /* ---------------- SMB2 命令码 ---------------- */
    private static final int CMD_NEGOTIATE = 0;
    private static final int CMD_SESSION_SETUP = 1;
    private static final int CMD_LOGOFF = 2;
    private static final int CMD_TREE_CONNECT = 3;
    private static final int CMD_TREE_DISCONNECT = 4;
    private static final int CMD_CREATE = 5;
    private static final int CMD_CLOSE = 6;
    private static final int CMD_READ = 8;
    private static final int CMD_QUERY_DIRECTORY = 14;

    /* ---------------- 协议方言 ---------------- */
    private static final int DIALECT_202 = 0x0202;
    private static final int DIALECT_210 = 0x0210;
    private static final int DIALECT_300 = 0x0300;
    private static final int DIALECT_302 = 0x0302;

    /* ---------------- 标志位 ---------------- */
    private static final int FLAGS_SERVER_TO_REDIR = 0x00000001;
    private static final int FLAGS_SIGNED = 0x00000008;

    /* ---------------- NTSTATUS ---------------- */
    public static final int STATUS_SUCCESS = 0x00000000;
    private static final int STATUS_MORE_PROCESSING_REQUIRED = 0xC0000016;
    private static final int STATUS_BUFFER_OVERFLOW = 0x80000005;
    private static final int STATUS_NO_MORE_FILES = 0x80000006;

    /* ---------------- 访问掩码 ---------------- */
    private static final int FILE_READ_DATA = 0x00000001;
    private static final int FILE_LIST_DIRECTORY = 0x00000001;
    private static final int FILE_READ_ATTRIBUTES = 0x00000080;
    private static final int FILE_SHARE_ALL = 0x00000007;   // read|write|delete
    private static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;
    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x00000010;
    private static final int FILE_OPEN = 1;
    private static final int FILE_DIRECTORY_FILE = 0x00000001;
    private static final int FILE_NON_DIRECTORY_FILE = 0x00000040;
    private static final int FILE_SYNCHRONOUS_IO_NONALERT = 0x00000020;

    private static final int IMPERSONATION = 2;
    /**
     * 目录项信息类：0x25 带 FileId（Win7+ 默认，文件名在偏移 104），
     * 0x24 老式兼容（文件名在偏移 94，部分 NAS 只支持它）
     */
    private static final int FILE_ID_BOTH_DIRECTORY_INFORMATION = 0x25;
    private static final int FILE_BOTH_DIRECTORY_INFORMATION = 0x24;

    /* ---------------- NTLM 标志 ---------------- */
    private static final int NTLM_UNICODE = 0x00000001;
    private static final int NTLM_REQUEST_TARGET = 0x00000004;
    private static final int NTLM_SIGN = 0x00000010;
    private static final int NTLM_NTLM = 0x00000200;
    private static final int NTLM_ALWAYS_SIGN = 0x00008000;
    private static final int NTLM_EXTENDED_SESSION_SECURITY = 0x00080000;
    private static final int NTLM_TARGET_INFO = 0x00800000;
    private static final int NTLM_VERSION = 0x02000000;
    private static final int NTLM_128 = 0x20000000;
    private static final int NTLM_KEY_EXCH = 0x40000000;
    private static final int NTLM_56 = 0x80000000;

    private final String host;
    private final int port;
    private final String share;
    private String user;
    private String domain;
    private final String password;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    private long messageId = 1;
    private long sessionId;
    private int treeId;
    private int dialect;
    private int maxReadSize = 65536;
    private int maxTransactSize = 65536;

    /** 签名/加密用的会话密钥（SMB2.1 直接当签名密钥，SMB3 需要先 KDF 派生） */
    private byte[] signingKey;
    private boolean signing;

    private final byte[] clientGuid = new byte[16];
    private boolean connected;

    /** 当前进行到哪一步（界面用它显示「正在认证…」这类进度，避免看起来卡死） */
    public static volatile String lastStage = "";

    /** 握手阶段的总超时（毫秒）；握手结束后归零，正常读写不受它限制 */
    private long handshakeDeadline;

    private static void stage(String s) {
        lastStage = s;
    }

    public static String stageText() {
        return lastStage;
    }

    /* 运行状态（诊断用，主界面日志会读） */
    public int negotiatedDialect() { return dialect; }

    public String dialectName() {
        switch (dialect) {
            case DIALECT_202: return "SMB 2.0.2";
            case DIALECT_210: return "SMB 2.1";
            case DIALECT_300: return "SMB 3.0";
            case DIALECT_302: return "SMB 3.0.2";
            default: return "SMB?";
        }
    }
    public boolean signingActive() { return signing; }
    public int sigOk;
    public int sigBad;

    /** 所有请求串行化：一条 TCP 连接，多线程同时读（播放 + 浏览）时不会串包 */
    private final Object ioLock = new Object();

    /** 允许的最高方言（默认 3.0.2；连不上时上层会降到 2.1 再试） */
    private final int maxDialect;

    public Smb2Client(String host, int port, String share, String user, String password) {
        this(host, port, share, user, password, DIALECT_302);
    }

    public Smb2Client(String host, int port, String share, String user, String password, int maxDialect) {
        this.maxDialect = maxDialect;
        this.host = host;
        this.port = port > 0 ? port : 445;
        this.share = share == null ? "" : share;
        this.password = password == null ? "" : password;
        String u = user == null ? "" : user.trim();
        // 兼容「域[反斜杠]用户名」与 user@domain 两种写法：SMB 只认裸用户名 + 域
        int bs = u.indexOf('\\');
        if (bs >= 0) {
            this.domain = u.substring(0, bs);
            this.user = u.substring(bs + 1);
        } else {
            int at = u.indexOf('@');
            if (at > 0) {
                this.user = u.substring(0, at);
                this.domain = u.substring(at + 1);
            } else {
                this.user = u;
                this.domain = "";
            }
        }
        new Random().nextBytes(clientGuid);
    }

    /* ============================================================
     *  对外接口
     * ============================================================ */

    /** 建立连接（协商 → 认证 → 挂载共享） */
    public void connect() throws IOException {
        synchronized (ioLock) {
            if (connected) return;
            try {
                stage("连接端口");
                handshakeDeadline = System.currentTimeMillis() + 30000;
                socket = new Socket();
                // 电视上不能让用户对着「正在连接」干等：连接 8 秒，握手各步 10 秒，
                // 整个握手加起来最多 30 秒，超了直接报错，绝不留「正在连接」这种状态。
                socket.connect(new InetSocketAddress(host, port), 8000);
                socket.setSoTimeout(10000);
                socket.setTcpNoDelay(true);
                in = socket.getInputStream();
                out = socket.getOutputStream();

                stage("协商");
                negotiate();
                stage("认证");
                sessionSetup();
                stage("挂载共享");
                treeConnect();
                connected = true;
                handshakeDeadline = 0;
                stage("已连接");
            } catch (SocketTimeoutException e) {
                closeQuietly();
                throw new Smb2Exception(0, "服务器无响应", "在「" + lastStage
                        + "」这一步等了 10 秒没回音，请检查端口/防火墙是否放通了 445");
            } catch (IOException e) {
                closeQuietly();
                throw e;
            } catch (RuntimeException e) {
                closeQuietly();
                throw new IOException("SMB2 连接异常：" + e, e);
            }
        }
    }

    /** 列出目录 */
    public List<FileItem> list(String path) throws IOException {
        List<FileItem> items = new ArrayList<>();
        byte[] fid = openDirectory(path);
        try {
            int cls = FILE_ID_BOTH_DIRECTORY_INFORMATION;
            int nameOff = 104;
            boolean first = true;
            while (true) {
                byte[] resp;
                if (first) {
                    try {
                        resp = queryDirectory(fid, true, cls);
                    } catch (Smb2Exception se) {
                        // 老 NAS 只认 0x24，退一级再试
                        if (se.status == 0xC000000D || se.status == 0xC00000BB || se.status == 0xC0000010) {
                            cls = FILE_BOTH_DIRECTORY_INFORMATION;
                            nameOff = 94;
                            resp = queryDirectory(fid, true, cls);
                        } else {
                            throw se;
                        }
                    }
                    first = false;
                } else {
                    resp = queryDirectory(fid, false, cls);
                }
                if (resp == null) break;
                parseDirEntries(resp, path, items, nameOff);
                if (bodyLength(resp) <= 8) break;
                int st = statusOf(resp);
                if (st != STATUS_SUCCESS && st != STATUS_BUFFER_OVERFLOW) break;
            }
        } finally {
            closeFile(fid);
        }
        return items;
    }

    /** 文件大小（顺带判断是不是目录） */
    public long size(String path) throws IOException {
        byte[] resp = createRequest(path, false);
        try {
            return le64(resp, 64 + 48);
        } finally {
            closeFile(fileIdOf(resp));
        }
    }

    /** 判断路径是不是目录 */
    public boolean isDirectory(String path) throws IOException {
        byte[] resp = createRequest(path, true);
        int status = statusOf(resp);
        if (status == STATUS_SUCCESS) {
            closeFile(fileIdOf(resp));
            return true;
        }
        return false;
    }

    /** 打开文件用于随机读取 */
    public Smb2File open(String path) throws IOException {
        byte[] resp = createRequest(path, false);
        long size = le64(resp, 64 + 48);
        return new Smb2File(fileIdOf(resp), size);
    }

    /**
     * 关闭连接。<b>不允许阻塞</b>：播放器退出时主线程会调到这里，
     * 只要有一个线程还卡在 read 上，就能把界面拖到 ANR。
     * 所以这里只做「关 socket」这一件事——阻塞中的读会立刻抛异常退出，
     * 服务器也会在 TCP 断开后自己回收会话（比发 LOGOFF 再等回包可靠得多）。
     */
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        connected = false;
        sessionId = 0;
        treeId = 0;
        signing = false;
        signingKey = null;
        // v1.49：只关 socket，不把 in/out 置空。
        // 播放线程可能正阻塞在 in.read()/out.write() 里，置空会变成 NullPointerException，
        // 而 NPE 不是 IOException，调用方的重试逻辑接不住；关 socket 让它老老实实抛 "Socket closed"。
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /* ============================================================
     *  文件句柄
     * ============================================================ */

    public class Smb2File implements SmbHandle {
        private byte[] fileId;
        private final long fileSize;
        private long pos;
        private boolean closed;

        Smb2File(byte[] fileId, long fileSize) {
            this.fileId = fileId;
            this.fileSize = fileSize;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (closed) throw new IOException("文件已关闭");
            if (pos >= fileSize) return -1;
            int want = (int) Math.min(len, Math.min(fileSize - pos, Math.min(maxReadSize, 1 << 20)));
            if (want <= 0) return -1;
            int n = readAt(fileId, pos, buf, off, want);
            if (n > 0) pos += n;
            return n <= 0 ? -1 : n;
        }

        @Override
        public void seek(long p) throws IOException {
            if (p < 0) p = 0;
            pos = p;
        }

        @Override
        public long size() {
            return fileSize;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                closeFile(fileId);
            } catch (Throwable ignored) {
            }
            fileId = null;
        }
    }

    /* ============================================================
     *  协商
     * ============================================================ */

    private void negotiate() throws IOException {
        socket.setSoTimeout(8000);
        // 从高到低（3.1.1 需要 negotiate context，故意不声明）
        int[] dialects = maxDialect >= DIALECT_302
                ? new int[]{DIALECT_302, DIALECT_300, DIALECT_210, DIALECT_202}
                : new int[]{DIALECT_210, DIALECT_202};
        int bodyLen = 36 + dialects.length * 2;
        byte[] body = new byte[bodyLen];
        le16(body, 0, 36);
        le16(body, 2, dialects.length);
        le16(body, 4, 0x0001);          // SecurityMode: signing enabled
        le16(body, 6, 0);
        le32(body, 8, 0);               // Capabilities：不声明 DFS/加密/LargeMTU
        System.arraycopy(clientGuid, 0, body, 12, 16);
        le32(body, 28, 0);
        le16(body, 32, 0);
        le16(body, 34, 0);
        for (int i = 0; i < dialects.length; i++) {
            le16(body, 36 + i * 2, dialects[i]);
        }

        byte[] h = header(CMD_NEGOTIATE, 0, 0, 1);
        byte[] resp = transact(h, body, false);
        int st = statusOf(resp);
        if (st != STATUS_SUCCESS) throw new Smb2Exception(st, "SMB2 协商失败", describe(st, "协商"));

        dialect = le16(resp, 64 + 4);
        maxTransactSize = (int) le32(resp, 64 + 28);
        maxReadSize = (int) le32(resp, 64 + 32);
        if (maxTransactSize <= 0) maxTransactSize = 65536;
        if (maxReadSize <= 0) maxReadSize = 65536;
        if (maxTransactSize > (1 << 20)) maxTransactSize = 1 << 20;
        if (maxReadSize > (1 << 20)) maxReadSize = 1 << 20;
    }

    /* ============================================================
     *  NTLMv2 认证
     * ============================================================ */

    private void sessionSetup() throws IOException {
        socket.setSoTimeout(10000);
        byte[] type1 = ntlmType1();
        byte[] token = spnegoType1(type1);
        byte[] lastType3 = null;
        byte[] type2 = null;

        for (int round = 0; round < 4; round++) {
            byte[] body = new byte[24 + token.length];
            le16(body, 0, 25);
            body[2] = 0;                          // Flags
            body[3] = 0x01;                       // SecurityMode: signing enabled
            le32(body, 4, 0);
            le32(body, 8, 0);
            le16(body, 12, 64 + 24);
            le16(body, 14, token.length);
            le64(body, 16, 0);
            System.arraycopy(token, 0, body, 24, token.length);

            byte[] h = header(CMD_SESSION_SETUP, 0, sessionId, 1);
            byte[] resp = transact(h, body, false);
            int st = statusOf(resp);

            long sid = le64(resp, 40);
            if (sid != 0) sessionId = sid;

            if (st == STATUS_MORE_PROCESSING_REQUIRED) {
                byte[] sec = securityBuffer(resp);
                type2 = extractNtlmssp(sec);
                if (type2 == null) throw new Smb2Exception(st, "服务器未返回 NTLM 挑战（可能只支持 Kerberos）", "认证");
                token = spnegoType3(buildType3(type2, type1));
                lastType3 = token;
                continue;
            }
            if (st != STATUS_SUCCESS) {
                throw new Smb2Exception(st, "SMB 认证失败", describe(st, "认证"));
            }
            // 认证通过
            if (type2 == null) {
                // 匿名或无需认证：没有会话密钥就不签名
                signing = false;
                return;
            }
            deriveSigningKey(type2);
            return;
        }
        throw new Smb2Exception(STATUS_MORE_PROCESSING_REQUIRED, "SMB 认证轮次超限", "认证");
    }

    private void deriveSigningKey(byte[] type2) throws IOException {
        // sessionKey 在 buildType3 时算好，存在字段里
        if (sessionKeyRaw == null) {
            signing = false;
            return;
        }
        if (dialect >= DIALECT_300) {
            signingKey = kdf(sessionKeyRaw, "SMB2AESCMAC", "SmbSign");
        } else {
            signingKey = sessionKeyRaw;
        }
        signing = signingKey != null && signingKey.length >= 16;
        sessionKeyRaw = null;
    }

    private byte[] sessionKeyRaw;

    /** SPNEGO NegTokenInit 包装 32 字节 NTLMSSP Type1 */
    private static byte[] spnegoType1(byte[] type1) {
        byte[] ntlmOid = {0x2b, 0x06, 0x01, 0x04, 0x01, (byte) 0x82, 0x37, 0x02, 0x02, 0x0a};
        byte[] spnegoOid = {0x2b, 0x06, 0x01, 0x05, 0x05, 0x02};
        byte[] mechTypes = derWrap(0xa0, derWrap(0x30, derWrap(0x06, ntlmOid)));
        byte[] mechToken = derWrap(0xa2, derWrap(0x04, type1));
        byte[] negTokenInit = derWrap(0xa0, derWrap(0x30, concat(mechTypes, mechToken)));
        return derWrap(0x60, concat(derWrap(0x06, spnegoOid), negTokenInit));
    }

    /** SPNEGO NegTokenResp 包装 NTLMSSP Type3 */
    private static byte[] spnegoType3(byte[] type3) {
        // a1 LL 30 LL a2 LL 04 LL <type3>
        byte[] inner = derWrap(0x04, type3);
        byte[] a2 = derWrap(0xa2, inner);
        byte[] seq = derWrap(0x30, a2);
        return derWrap(0xa1, seq);
    }

    private static byte[] derWrap(int tag, byte[] content) {
        int len = content.length;
        byte[] head;
        if (len < 0x80) {
            head = new byte[]{(byte) tag, (byte) len};
        } else if (len < 0x100) {
            head = new byte[]{(byte) tag, (byte) 0x81, (byte) len};
        } else {
            head = new byte[]{(byte) tag, (byte) 0x82, (byte) (len >> 8), (byte) len};
        }
        byte[] out = new byte[head.length + len];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(content, 0, out, head.length, len);
        return out;
    }

    /** 32 字节标准 NTLMSSP Type1 */
    private static byte[] ntlmType1() {
        byte[] b = new byte[32];
        byte[] sig = {'N', 'T', 'L', 'M', 'S', 'S', 'P', 0};
        System.arraycopy(sig, 0, b, 0, 8);
        le32(b, 8, 1);
        int flags = NTLM_UNICODE | NTLM_REQUEST_TARGET | NTLM_NTLM | NTLM_ALWAYS_SIGN
                | NTLM_EXTENDED_SESSION_SECURITY | NTLM_SIGN | NTLM_128 | NTLM_56;
        le32(b, 12, flags);
        return b;
    }

    /** 从 SPNEGO 数据里挖出 NTLMSSP 消息（跳过所有 DER 包装） */
    private static byte[] extractNtlmssp(byte[] data) {
        if (data == null) return null;
        for (int i = 0; i + 8 <= data.length; i++) {
            if (data[i] == 'N' && data[i + 1] == 'T' && data[i + 2] == 'L' && data[i + 3] == 'M'
                    && data[i + 4] == 'S' && data[i + 5] == 'S' && data[i + 6] == 'P' && data[i + 7] == 0) {
                return Arrays.copyOfRange(data, i, data.length);
            }
        }
        return null;
    }

    /** 用服务器 Type2 挑战构造 NTLMv2 Type3 */
    private byte[] buildType3(byte[] type2, byte[] type1) throws IOException {
        int serverFlags = (int) le32(type2, 20);
        byte[] serverChallenge = Arrays.copyOfRange(type2, 24, 32);

        byte[] targetInfo = new byte[0];
        boolean micRequired = false;
        if ((serverFlags & NTLM_TARGET_INFO) != 0) {
            int tiLen = le16(type2, 40);
            int tiOff = (int) le32(type2, 44);
            if (tiOff > 0 && tiOff + tiLen <= type2.length && tiLen > 0) {
                targetInfo = Arrays.copyOfRange(type2, tiOff, tiOff + tiLen);
                micRequired = targetInfoHasMicFlag(targetInfo);
            }
        }

        String u = user == null ? "" : user;
        String d = domain == null ? "" : domain;

        byte[] ntHash = md4(utf16le(password));
        // 密码为空时 NT 哈希就是空串的 MD4，这是合法的（macOS 允许空密码账号）
        byte[] responseKey = hmacMd5(ntHash, utf16le(upper(u) + d));

        byte[] clientChallenge = new byte[8];
        new Random().nextBytes(clientChallenge);
        long now = (System.currentTimeMillis() + 11644473600000L) * 10000L;  // FILETIME

        // blob: 01 01 00 00 | 00 00 00 00 | timestamp | clientChallenge | 00*4 | targetInfo | 00*4
        byte[] blob = new byte[4 + 4 + 8 + 8 + 4 + targetInfo.length + 4];
        int p = 0;
        blob[p++] = 0x01;
        blob[p++] = 0x01;
        blob[p++] = 0;
        blob[p++] = 0;
        p += 4;                                     // reserved
        le64(blob, p, now);
        p += 8;
        System.arraycopy(clientChallenge, 0, blob, p, 8);
        p += 8;
        p += 4;                                     // unknown
        if (targetInfo.length > 0) {
            System.arraycopy(targetInfo, 0, blob, p, targetInfo.length);
            p += targetInfo.length;
        }
        p += 4;                                     // terminator

        byte[] ntProof = hmacMd5(responseKey, concat(serverChallenge, blob));
        byte[] ntResponse = concat(ntProof, blob);
        byte[] lmResponse = concat(hmacMd5(responseKey, concat(serverChallenge, clientChallenge)), clientChallenge);

        byte[] sessionBaseKey = hmacMd5(responseKey, ntProof);
        byte[] exportedSessionKey;
        boolean keyExch = (serverFlags & NTLM_KEY_EXCH) != 0;
        if (keyExch) {
            byte[] randomKey = new byte[16];
            new Random().nextBytes(randomKey);
            exportedSessionKey = randomKey;
            sessionKeyRaw = randomKey;
        } else {
            exportedSessionKey = null;
            sessionKeyRaw = sessionBaseKey;
        }

        int flags = serverFlags;
        flags |= NTLM_UNICODE | NTLM_NTLM | NTLM_EXTENDED_SESSION_SECURITY | NTLM_ALWAYS_SIGN
                | NTLM_REQUEST_TARGET | NTLM_SIGN;
        flags &= ~NTLM_VERSION;

        byte[] u16User = utf16le(u);
        byte[] u16Domain = utf16le(d);
        byte[] u16Wkst = utf16le("LUMO");
        byte[] encKey = new byte[0];
        if (keyExch && exportedSessionKey != null) {
            encKey = rc4(sessionBaseKey, exportedSessionKey);
        }

        int fixed = 64 + 8 + (micRequired ? 16 : 0);
        int off = fixed;
        byte[] msg = new byte[fixed + lmResponse.length + ntResponse.length + u16Domain.length
                + u16User.length + u16Wkst.length + encKey.length];
        byte[] sig = {'N', 'T', 'L', 'M', 'S', 'S', 'P', 0};
        System.arraycopy(sig, 0, msg, 0, 8);
        le32(msg, 8, 3);

        le16(msg, 12, lmResponse.length);
        le16(msg, 14, lmResponse.length);
        le32(msg, 16, off);

        le16(msg, 20, ntResponse.length);
        le16(msg, 22, ntResponse.length);
        le32(msg, 24, off + lmResponse.length);

        le16(msg, 28, u16Domain.length);
        le16(msg, 30, u16Domain.length);
        le32(msg, 32, off + lmResponse.length + ntResponse.length);

        int userOff = off + lmResponse.length + ntResponse.length + u16Domain.length;
        le16(msg, 36, u16User.length);
        le16(msg, 38, u16User.length);
        le32(msg, 40, userOff);

        int wkstOff = userOff + u16User.length;
        le16(msg, 44, u16Wkst.length);
        le16(msg, 46, u16Wkst.length);
        le32(msg, 48, wkstOff);

        int keyOff = wkstOff + u16Wkst.length;
        le16(msg, 52, encKey.length);
        le16(msg, 54, encKey.length);
        le32(msg, 56, encKey.length > 0 ? keyOff : 0);

        le32(msg, 60, flags);
        // 如果服务器给了 Version 字段，保留 8 字节零版本，更贴近 Windows 行为
        int wp = 64;
        if ((serverFlags & NTLM_VERSION) != 0) {
            msg[wp] = 10;
            msg[wp + 1] = 0;
            le16(msg, wp + 2, 19045);
            msg[wp + 4] = 0;
            msg[wp + 5] = 0;
            le16(msg, wp + 6, 0);
        }
        wp += 8;

        System.arraycopy(lmResponse, 0, msg, off, lmResponse.length);
        System.arraycopy(ntResponse, 0, msg, off + lmResponse.length, ntResponse.length);
        System.arraycopy(u16Domain, 0, msg, off + lmResponse.length + ntResponse.length, u16Domain.length);
        System.arraycopy(u16User, 0, msg, userOff, u16User.length);
        System.arraycopy(u16Wkst, 0, msg, wkstOff, u16Wkst.length);
        if (encKey.length > 0) System.arraycopy(encKey, 0, msg, keyOff, encKey.length);

        if (micRequired) {
            // MIC = HMAC_MD5(ExportedSessionKey, Type1 || Type2 || Type3[mic=0])
            byte[] zeroMic = new byte[16];
            System.arraycopy(zeroMic, 0, msg, wp, 16);
            byte[] micKey = exportedSessionKey != null ? exportedSessionKey : sessionBaseKey;
            byte[] mic = hmacMd5(micKey, concat(concat(type1, type2), msg));
            System.arraycopy(mic, 0, msg, wp, 16);
        }
        return msg;
    }

    private static boolean targetInfoHasMicFlag(byte[] av) {
        int p = 0;
        while (p + 4 <= av.length) {
            int id = le16(av, p);
            int len = le16(av, p + 2);
            if (id == 0) break;
            if (id == 6 && len >= 4) {   // MsvAvFlags
                int flags = (int) le32(av, p + 4);
                return (flags & 0x2) != 0;
            }
            p += 4 + len;
        }
        return false;
    }

    /* ============================================================
     *  TREE_CONNECT / 目录 / 读
     * ============================================================ */

    private void treeConnect() throws IOException {
        socket.setSoTimeout(10000);
        String unc = "\\\\" + host + "\\" + share;
        byte[] path = utf16le(unc);
        byte[] body = new byte[8 + path.length];
        le16(body, 0, 9);
        le16(body, 2, 0);
        le16(body, 4, 64 + 8);
        le16(body, 6, path.length);
        System.arraycopy(path, 0, body, 8, path.length);

        byte[] h = header(CMD_TREE_CONNECT, 0, sessionId, 1);
        byte[] resp = transact(h, body, true);
        int st = statusOf(resp);
        if (st != STATUS_SUCCESS) {
            throw new Smb2Exception(st, "挂载共享失败", describe(st, "挂载 \\\\" + host + "\\" + share));
        }
        treeId = (int) le32(resp, 36);
    }

    private byte[] openDirectory(String path) throws IOException {
        return fileIdOf(createRequest(path, true));
    }

    private byte[] createRequest(String path, boolean directory) throws IOException {
        String name = smbName(path);
        byte[] nameBuf = utf16le(name);
        byte[] body = new byte[56 + nameBuf.length];
        le16(body, 0, 57);
        body[2] = 0;
        body[3] = 0;                                     // oplock: none
        le32(body, 4, IMPERSONATION);
        le32(body, 24, directory ? (FILE_LIST_DIRECTORY | FILE_READ_ATTRIBUTES)
                : (FILE_READ_DATA | FILE_READ_ATTRIBUTES));
        le32(body, 28, directory ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL);
        le32(body, 32, FILE_SHARE_ALL);
        le32(body, 36, FILE_OPEN);
        le32(body, 40, FILE_SYNCHRONOUS_IO_NONALERT
                | (directory ? FILE_DIRECTORY_FILE : FILE_NON_DIRECTORY_FILE));
        le16(body, 44, 64 + 56);
        le16(body, 46, nameBuf.length);
        le32(body, 48, 0);
        le32(body, 52, 0);
        System.arraycopy(nameBuf, 0, body, 56, nameBuf.length);

        byte[] h = header(CMD_CREATE, treeId, sessionId, 1);
        byte[] resp = transact(h, body, true);
        int st = statusOf(resp);
        if (st != STATUS_SUCCESS && st != STATUS_BUFFER_OVERFLOW) {
            throw new Smb2Exception(st, "打开失败", describe(st, (directory ? "目录 " : "文件 ") + name));
        }
        return resp;
    }

    private byte[] queryDirectory(byte[] fileId, boolean first, int infoClass) throws IOException {
        byte[] pattern = utf16le("*");
        byte[] body = new byte[32 + pattern.length];
        le16(body, 0, 33);
        body[2] = (byte) infoClass;
        body[3] = (byte) (first ? 0x01 : 0x00);           // RESTART_SCANS
        le32(body, 4, 0);
        System.arraycopy(fileId, 0, body, 8, 16);
        le16(body, 24, 64 + 32);
        le16(body, 26, pattern.length);
        le32(body, 28, Math.min(maxTransactSize, 65536));
        System.arraycopy(pattern, 0, body, 32, pattern.length);

        socket.setSoTimeout(15000);
        byte[] h = header(CMD_QUERY_DIRECTORY, treeId, sessionId, 1);
        byte[] resp = transact(h, body, true);
        int st = statusOf(resp);
        if (st == STATUS_SUCCESS || st == STATUS_BUFFER_OVERFLOW) return resp;
        if (st == STATUS_NO_MORE_FILES) return null;
        throw new Smb2Exception(st, "列目录失败", describe(st, "列目录"));
    }

    private int readAt(byte[] fileId, long offset, byte[] buf, int off, int len) throws IOException {
        byte[] body = new byte[48];
        le16(body, 0, 49);
        body[2] = 0;
        body[3] = 0;
        le32(body, 4, len);
        le64(body, 8, offset);
        System.arraycopy(fileId, 0, body, 16, 16);
        le32(body, 32, 0);
        le32(body, 36, 0);
        le32(body, 40, 0);
        le16(body, 44, 0);
        le16(body, 46, 0);

        byte[] h = header(CMD_READ, treeId, sessionId, 1);
        byte[] resp = transact(h, body, true);
        int st = statusOf(resp);
        if (st != STATUS_SUCCESS && st != 0x00000103 /* STATUS_PENDING */) {
            if (st == 0xC0000011) return -1;   // STATUS_END_OF_FILE
            throw new Smb2Exception(st, "读取失败", describe(st, "读取"));
        }
        int dataOff = resp[64 + 2] & 0xFF;
        int dataLen = (int) le32(resp, 64 + 4);
        if (dataLen <= 0) return -1;
        int from = dataOff;
        if (from < 64) from = 64 + 16;
        if (from + dataLen > resp.length) {
            dataLen = resp.length - from;
        }
        if (dataLen <= 0) return -1;
        System.arraycopy(resp, from, buf, off, Math.min(dataLen, len));
        return Math.min(dataLen, len);
    }

    private void closeFile(byte[] fileId) throws IOException {
        if (fileId == null) return;
        byte[] body = new byte[24];
        le16(body, 0, 24);
        le16(body, 2, 0);
        le32(body, 4, 0);
        System.arraycopy(fileId, 0, body, 8, 16);
        byte[] h = header(CMD_CLOSE, treeId, sessionId, 1);
        transact(h, body, true);
    }

    private static byte[] fileIdOf(byte[] resp) {
        return Arrays.copyOfRange(resp, 64 + 64, 64 + 80);
    }

    /** 把路径转成 SMB2 的 name（反斜杠分隔、无前导斜杠；根目录为空串） */
    private static String smbName(String path) {
        if (path == null) return "";
        String p = path.replace('/', '\\').trim();
        while (p.startsWith("\\")) p = p.substring(1);
        while (p.endsWith("\\")) p = p.substring(0, p.length() - 1);
        // 去掉 "." 与 ".."，避免服务器拒绝
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("\\\\")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (sb.length() > 0) sb.append('\\');
            sb.append(seg);
        }
        return sb.toString();
    }

    private void parseDirEntries(byte[] resp, String path, List<FileItem> out, int nameOff) {
        int outOff = le16(resp, 64 + 2);
        int outLen = (int) le32(resp, 64 + 4);
        int start = outOff;
        if (start < 64) start = 64 + 8;
        int end = Math.min(resp.length, start + outLen);
        int p = start;
        int guard = 0;
        while (p + nameOff <= end && guard++ < 8192) {
            int next = (int) le32(resp, p);
            long eof = le64(resp, p + 40);
            int attr = (int) le32(resp, p + 56);
            int nameLen = (int) le32(resp, p + 60);
            if (p + nameOff + nameLen > end) break;
            String name = utf16leDecode(resp, p + nameOff, nameLen);
            if (name != null && !name.isEmpty() && !".".equals(name) && !"..".equals(name)) {
                boolean isDir = (attr & FILE_ATTRIBUTE_DIRECTORY) != 0;
                String childPath = (path == null || path.isEmpty() || "/".equals(path))
                        ? "/" + name
                        : (path.endsWith("/") ? path + name : path + "/" + name);
                out.add(new FileItem(name, childPath, isDir, isDir ? 0 : eof, formatTime(le64(resp, p + 24))));
            }
            if (next <= 0) break;
            p += next;
        }
    }

    /** FILETIME（1601 起 100ns）→ 可读时间，列表里显示用 */
    private static String formatTime(long fileTime) {
        if (fileTime <= 0) return "";
        long ms = fileTime / 10000L - 11644473600000L;
        if (ms <= 0) return "";
        try {
            return TIME_FMT.format(new java.util.Date(ms));
        } catch (Throwable t) {
            return "";
        }
    }

    private static final java.text.SimpleDateFormat TIME_FMT =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);

    /* ============================================================
     *  报文收发与签名
     * ============================================================ */

    private byte[] header(int command, int treeIdArg, long sessionIdArg, int credits) {
        byte[] h = new byte[64];
        h[0] = (byte) 0xFE;
        h[1] = 'S';
        h[2] = 'M';
        h[3] = 'B';
        le16(h, 4, 64);
        le16(h, 6, credits);
        le16(h, 12, command);
        le16(h, 14, 1);
        le32(h, 16, 0);
        le32(h, 20, 0);
        le64(h, 24, messageId++);
        le32(h, 32, 0);
        le32(h, 36, treeIdArg);
        le64(h, 40, sessionIdArg);
        return h;
    }

    /** 发一个请求，返回响应 PDU（含 64 字节头）。签名按需自动加上 */
    private byte[] transact(byte[] h, byte[] body, boolean allowSign) throws IOException {
        synchronized (ioLock) {
            if (out == null || in == null) throw new IOException("SMB 会话已关闭");
            if (handshakeDeadline > 0 && System.currentTimeMillis() > handshakeDeadline) {
                throw new Smb2Exception(0, "连接超时", "卡在「" + lastStage + "」这一步（总共超过 30 秒）");
            }
            if (!allowSign) {
                // 协商/认证阶段签名还没建立
            } else if (signing && signingKey != null) {
                le32(h, 16, (int) (le32(h, 16) | FLAGS_SIGNED));
            }
            byte[] pdu = concat(h, body);
            if (allowSign && signing && signingKey != null) {
                byte[] sig = computeSignature(pdu);
                System.arraycopy(sig, 0, pdu, 48, 16);
            }
            sendFramed(pdu);
            byte[] resp = readFramed();
            // 服务端签名能验证就验证（失败只记录，不中断——某些服务器不签名响应）
            // 服务器若签名响应，顺手校验（只统计，不阻断：个别服务器不签响应）
            verifyServerSignature(resp);
            return resp;
        }
    }

    private byte[] computeSignature(byte[] pdu) throws IOException {
        byte[] tmp = Arrays.copyOf(pdu, pdu.length);
        Arrays.fill(tmp, 48, 64, (byte) 0);
        if (dialect >= DIALECT_300) {
            return aesCmac(signingKey, tmp);
        }
        return hmacSha256(signingKey, tmp);
    }

    private boolean verifyServerSignature(byte[] resp) {
        if (resp == null || resp.length < 64) return false;
        if ((le32(resp, 16) & FLAGS_SIGNED) == 0) return false;
        if (!signing || signingKey == null) return false;
        try {
            byte[] got = Arrays.copyOfRange(resp, 48, 64);
            byte[] calc = computeSignature(resp);
            boolean ok = Arrays.equals(Arrays.copyOf(calc, 16), got);
            if (ok) sigOk++; else sigBad++;
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private void sendFramed(byte[] pdu) throws IOException {
        byte[] frame = new byte[4 + pdu.length];
        frame[0] = 0;
        frame[1] = (byte) (pdu.length >> 16);
        frame[2] = (byte) (pdu.length >> 8);
        frame[3] = (byte) pdu.length;
        System.arraycopy(pdu, 0, frame, 4, pdu.length);
        out.write(frame);
        out.flush();
    }

    private byte[] readFramed() throws IOException {
        byte[] len = readFully(4);
        int n = ((len[1] & 0xFF) << 16) | ((len[2] & 0xFF) << 8) | (len[3] & 0xFF);
        if (n <= 0 || n > (16 << 20)) throw new IOException("SMB 响应长度异常：" + n);
        byte[] pdu = readFully(n);
        if (pdu.length < 64) throw new IOException("SMB 响应过短");
        return pdu;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException("SMB 连接被服务器关闭");
            off += r;
        }
        return b;
    }

    private static int statusOf(byte[] resp) {
        return resp == null ? -1 : (int) le32(resp, 8);
    }

    private static int bodyLength(byte[] resp) {
        return resp.length - 64;
    }

    private static byte[] securityBuffer(byte[] resp) {
        int off = le16(resp, 64 + 4);
        int len = le16(resp, 64 + 6);
        if (off <= 0 || len <= 0) return null;
        int start = off;
        if (start < 64) start = 64 + 8;
        if (start + len > resp.length) return null;
        return Arrays.copyOfRange(resp, start, start + len);
    }

    /* ============================================================
     *  错误说明（给用户看的中文）
     * ============================================================ */

    public static class Smb2Exception extends IOException {
        public final int status;

        public Smb2Exception(int status, String message, String detail) {
            super(detail == null || detail.isEmpty() ? message : message + "：" + detail);
            this.status = status;
        }
    }

    static String describe(int status, String what) {
        switch (status) {
            case 0xC0000022:
                return "拒绝访问 —— 账号/密码不对，或该账号没有共享权限（macOS：系统设置 → 通用 → 共享 → 文件共享 → 选项 里勾选该用户）";
            case 0xC000006D:
            case 0xC000006A:
                return "用户名或密码错误";
            case 0xC00000CC:
                return "共享名不存在 —— 请确认共享文件夹名称（macOS 默认共享名是「用户名」或你自定义的名字）";
            case 0xC0000034:
                return "路径不存在";
            case 0xC00000BB:
                return "服务器不支持该操作（可能是 SMB1 老设备）";
            case 0xC00000C0:
                return "设备不存在";
            case 0xC000018B:
                return "账号被锁定";
            case 0xC0000234:
                return "账号已被禁用";
            case 0xC00002EE:
                return "服务器未就绪";
            default:
                return String.format("%s 失败（0x%08X）", what, status);
        }
    }

    /* ============================================================
     *  密码学工具
     * ============================================================ */

    static byte[] hmacMd5(byte[] key, byte[] data) throws IOException {
        return mac("HmacMD5", key, data);
    }

    static byte[] hmacSha256(byte[] key, byte[] data) throws IOException {
        return mac("HmacSHA256", key, data);
    }

    private static byte[] mac(String alg, byte[] key, byte[] data) throws IOException {
        try {
            Mac m = Mac.getInstance(alg);
            m.init(new SecretKeySpec(key, alg));
            return m.doFinal(data);
        } catch (Exception e) {
            throw new IOException(alg + " 不可用：" + e, e);
        }
    }

    /** SP800-108 Counter Mode KDF（SMB3 会话密钥派生） */
    static byte[] kdf(byte[] key, String label, String context) throws IOException {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            m.update(new byte[]{0, 0, 0, 1});
            m.update(label.getBytes("UTF-8"));
            m.update((byte) 0);
            m.update(context.getBytes("UTF-8"));
            m.update((byte) 0);
            m.update(new byte[]{0, 0, 0, (byte) 128});   // L = 128 bit
            byte[] out = m.doFinal();
            return Arrays.copyOf(out, 16);
        } catch (Exception e) {
            throw new IOException("KDF 失败：" + e, e);
        }
    }

    /** AES-128-CMAC（RFC 4493），SMB3 报文签名用它 */
    static byte[] aesCmac(byte[] key, byte[] data) throws IOException {
        try {
            byte[] k = key.length == 16 ? key : Arrays.copyOf(key, 16);
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"));

            byte[] l = c.doFinal(new byte[16]);
            byte[] k1 = dbl(l);
            byte[] k2 = dbl(k1);

            int n = (data.length + 15) / 16;
            boolean lastComplete = data.length > 0 && (data.length % 16 == 0);
            if (n == 0) {
                n = 1;
                lastComplete = false;
            }
            byte[] x = new byte[16];
            for (int i = 0; i < n - 1; i++) {
                byte[] blk = Arrays.copyOfRange(data, i * 16, i * 16 + 16);
                x = c.doFinal(xor(x, blk));
            }
            byte[] last = new byte[16];
            int startOff = (n - 1) * 16;
            int remain = data.length - startOff;
            if (remain > 0) System.arraycopy(data, startOff, last, 0, Math.min(remain, 16));
            if (lastComplete) {
                last = xor(last, k1);
            } else {
                last[remain] = (byte) 0x80;
                last = xor(last, k2);
            }
            byte[] t = c.doFinal(xor(x, last));
            return t;
        } catch (Exception e) {
            throw new IOException("AES-CMAC 失败：" + e, e);
        }
    }

    private static byte[] dbl(byte[] b) {
        byte[] out = new byte[16];
        int carry = 0;
        for (int i = 15; i >= 0; i--) {
            int v = (b[i] & 0xFF) << 1 | carry;
            out[i] = (byte) v;
            carry = (v >> 8) & 1;
        }
        if ((b[0] & 0x80) != 0) out[15] ^= 0x87;
        return out;
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) out[i] = (byte) (a[i] ^ b[i]);
        return out;
    }

    /** RC4：NTLM 会话密钥加密用 */
    static byte[] rc4(byte[] key, byte[] data) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xFF)) & 0xFF;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
        }
        byte[] out = new byte[data.length];
        int i = 0;
        j = 0;
        for (int n = 0; n < data.length; n++) {
            i = (i + 1) & 0xFF;
            j = (j + s[i]) & 0xFF;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
            out[n] = (byte) (data[n] ^ s[(s[i] + s[j]) & 0xFF]);
        }
        return out;
    }

    /** MD4：NTLM 的 NT 哈希（javax.crypto 不提供） */
    static byte[] md4(byte[] msg) {
        int[] h = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476};
        int origLen = msg.length;
        int padLen = ((origLen + 8) / 64 + 1) * 64;
        byte[] buf = new byte[padLen];
        System.arraycopy(msg, 0, buf, 0, origLen);
        buf[origLen] = (byte) 0x80;
        long bits = (long) origLen * 8;
        for (int i = 0; i < 8; i++) {
            buf[padLen - 8 + i] = (byte) (bits >>> (8 * i));
        }
        int[] x = new int[16];
        for (int block = 0; block < padLen; block += 64) {
            for (int i = 0; i < 16; i++) {
                x[i] = (buf[block + i * 4] & 0xFF)
                        | ((buf[block + i * 4 + 1] & 0xFF) << 8)
                        | ((buf[block + i * 4 + 2] & 0xFF) << 16)
                        | ((buf[block + i * 4 + 3] & 0xFF) << 24);
            }
            int a = h[0], b = h[1], c = h[2], d = h[3];

            // round 1
            int[] o1 = {3, 7, 11, 19};
            for (int i = 0; i < 16; i++) {
                int k = i;
                int s = o1[i % 4];
                int f = (b & c) | (~b & d);
                int tmp = a + f + x[k];
                a = d;
                d = c;
                c = b;
                b = Integer.rotateLeft(tmp, s);
            }
            // round 2
            int[] order2 = {0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15};
            int[] o2 = {3, 5, 9, 13};
            for (int i = 0; i < 16; i++) {
                int k = order2[i];
                int s = o2[i % 4];
                int g = (b & c) | (b & d) | (c & d);
                int tmp = a + g + x[k] + 0x5A827999;
                a = d;
                d = c;
                c = b;
                b = Integer.rotateLeft(tmp, s);
            }
            // round 3
            int[] order3 = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};
            int[] o3 = {3, 9, 11, 15};
            for (int i = 0; i < 16; i++) {
                int k = order3[i];
                int s = o3[i % 4];
                int g = b ^ c ^ d;
                int tmp = a + g + x[k] + 0x6ED9EBA1;
                a = d;
                d = c;
                c = b;
                b = Integer.rotateLeft(tmp, s);
            }
            h[0] += a;
            h[1] += b;
            h[2] += c;
            h[3] += d;
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 4; i++) {
            out[i * 4] = (byte) h[i];
            out[i * 4 + 1] = (byte) (h[i] >>> 8);
            out[i * 4 + 2] = (byte) (h[i] >>> 16);
            out[i * 4 + 3] = (byte) (h[i] >>> 24);
        }
        return out;
    }

    /* ============================================================
     *  字节工具
     * ============================================================ */

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static void le16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }

    static void le32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    static void le64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }

    static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    static byte[] utf16le(String s) {
        if (s == null) s = "";
        byte[] out = new byte[s.length() * 2];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out[i * 2] = (byte) c;
            out[i * 2 + 1] = (byte) (c >>> 8);
        }
        return out;
    }

    static String utf16leDecode(byte[] b, int off, int len) {
        if (len <= 0) return "";
        StringBuilder sb = new StringBuilder(len / 2);
        for (int i = 0; i + 1 < len; i += 2) {
            sb.append((char) ((b[off + i] & 0xFF) | ((b[off + i + 1] & 0xFF) << 8)));
        }
        return sb.toString();
    }

    private static String upper(String s) {
        // NTLM 要求用户名大写（ASCII 上转即可，避免土耳其语 i 之类的地域坑）
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        return sb.toString();
    }

    static byte[] sha256(byte[] data) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }
}
