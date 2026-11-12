package com.letvcast.player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

/**
 * SMB 客户端（门面）。
 *
 * <p>两套协议共存，自动择优：
 * <ul>
 *   <li>SMB2/SMB3 —— 自己实现的 {@link Smb2Client}，现代 Windows / macOS / NAS 都只开这个；</li>
 *   <li>SMB1(CIFS) —— jcifs 1.3.17，只给十几年不升级的老 NAS/XP 用。</li>
 * </ul>
 *
 * <p>v1.33 起默认先试 SMB2：macOS 13 之后系统共享已经把 SMB1 服务端彻底删掉，
 * 旧版本只带 jcifs（只会 SMB1），所以连 Mac 必然失败。
 */
public class SmbClient {

    /** 协议偏好：0 自动（先 SMB2 再 SMB1），1 只用 SMB2，2 只用 SMB1 */
    public static final int PROTO_AUTO = 0;
    public static final int PROTO_SMB2 = 1;
    public static final int PROTO_SMB1 = 2;

    /** 由 App 启动时从设置里读出来写这里，避免每次调用都传 Context（volatile：设置页在主线程改，后台线程读） */
    public static volatile int protocolPreference = PROTO_AUTO;

    static {
        try {
            jcifs.Config.setProperty("jcifs.smb.client.connTimeout", "6000");
            jcifs.Config.setProperty("jcifs.smb.client.responseTimeout", "15000");
            jcifs.Config.setProperty("jcifs.smb.client.soTimeout", "15000");
            jcifs.Config.setProperty("jcifs.netbios.retryTimeout", "1000");
            jcifs.Config.setProperty("jcifs.netbios.retryCount", "1");
            // 老 NAS 常见：中文文件名用 GBK
            jcifs.Config.setProperty("jcifs.encoding", "GBK");
        } catch (Throwable ignored) {
        }
    }

    private final String host;      // 192.168.1.100
    private final int port;         // 默认 445
    private final String share;     // 共享名
    private final String user;
    private final String pass;

    private Smb2Client smb2;        // 复用的 SMB2 会话
    private String protoInfo = "";  // 实际用的协议（诊断显示用）

    /** 指定端口（非 445 的 NAS / 测试环境） */
    public SmbClient(String host, int port, String share, String user, String password) {
        this.host = host == null ? "" : host.trim();
        this.port = port > 0 ? port : 445;
        this.share = share == null ? "" : share.trim();
        this.user = user == null ? "" : user.trim();
        this.pass = password == null ? "" : password;
    }

    public SmbClient(String host, String share, String user, String password) {
        String h = host == null ? "" : host.trim();
        int p = 445;
        // 支持 "192.168.1.10:1445" 这种非标准端口（测试 / 特殊 NAS 都会用）
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < 0) {
            String portStr = h.substring(colon + 1);
            if (portStr.matches("\\d{1,5}")) {
                try {
                    p = Integer.parseInt(portStr);
                    h = h.substring(0, colon);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        this.host = h;
        this.port = p;
        this.share = share == null ? "" : share.trim();
        this.user = user == null ? "" : user.trim();
        this.pass = password == null ? "" : password;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getShare() {
        return share;
    }

    /* ============================================================
     *  SMB2 会话管理
     * ============================================================ */

    private synchronized Smb2Client smb2() throws IOException {
        if (smb2 != null) return smb2;
        Smb2Client c = new Smb2Client(host, port, share, user, pass, 0x0302);
        try {
            c.connect();
            // 探一下：SMB3 签名算法不对时，这里就会 ACCESS_DENIED，好过等到播放中途才暴露
            c.list("");
            smb2 = c;
            protoInfo = c.dialectName();
            return c;
        } catch (Smb2Client.Smb2Exception e) {
            boolean downgrade = c.negotiatedDialect() >= 0x0300 && e.status == 0xC0000022;
            try {
                c.close();
            } catch (Throwable ignored) {
            }
            if (!downgrade) throw e;
            // 3.x 签名被拒 → 退回 2.1（HMAC-SHA256 签名，兼容性最好）
            Smb2Client d = new Smb2Client(host, port, share, user, pass, 0x0210);
            try {
                d.connect();
            } catch (IOException e2) {
                try {
                    d.close();
                } catch (Throwable ignored) {
                }
                throw e2;
            }
            smb2 = d;
            protoInfo = d.dialectName() + "（SMB3 签名被拒，已自动降级）";
            return d;
        } catch (IOException e) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
            throw e;
        }
    }

    /** 实际生效的协议描述，连上也顺便告诉用户走的是哪一版 */
    public String info() {
        if (smb2 != null && !protoInfo.isEmpty()) return protoInfo;
        return "SMB1";
    }

    private void dropSmb2() {
        if (smb2 != null) {
            try {
                smb2.close();
            } catch (Throwable ignored) {
            }
            smb2 = null;
        }
    }

    public void close() {
        dropSmb2();
    }

    /** SMB2 是否可用（不被设置禁用） */
    private boolean smb2Allowed() {
        return protocolPreference != PROTO_SMB1;
    }

    private boolean smb1Allowed() {
        return protocolPreference != PROTO_SMB2;
    }

    /* ============================================================
     *  对外 API
     * ============================================================ */

    /** 列出目录 */
    public List<FileItem> list(String path) throws Exception {
        String p = path == null ? "" : path;
        if (smb2Allowed()) {
            try {
                return withSmb2Retry(new Smb2Op<List<FileItem>>() {
                    @Override
                    public List<FileItem> run(Smb2Client c) throws Exception {
                        return c.list(p);
                    }
                });
            } catch (Exception e) {
                if (!smb1Allowed() || !shouldFallbackToSmb1(e)) throw enrich(e);
            }
        }
        return listSmb1(p);
    }

    /** 测试连接（能列出共享根目录即视为通过） */
    public void testConnection() throws Exception {
        if (smb2Allowed()) {
            try {
                withSmb2Retry(new Smb2Op<Void>() {
                    @Override
                    public Void run(Smb2Client c) throws Exception {
                        c.list("");
                        return null;
                    }
                });
                return;
            } catch (Exception e) {
                if (!smb1Allowed() || !shouldFallbackToSmb1(e)) throw enrich(e);
            }
        }
        // SMB1 兜底：老设备可能只认它
        SmbFile dir = new SmbFile(buildSmbUrl(""), getAuth());
        SmbFile[] children = dir.listFiles();
        if (children == null) {
            throw new Exception("无法列出目录，请检查共享名、账号或权限");
        }
    }

    /** 打开文件用于随机读取（播放代理用） */
    public SmbHandle open(final String path) throws Exception {
        String p = path == null ? "" : path;
        if (smb2Allowed()) {
            try {
                return withSmb2Retry(new Smb2Op<SmbHandle>() {
                    @Override
                    public SmbHandle run(Smb2Client c) throws Exception {
                        return c.open(p);
                    }
                });
            } catch (Exception e) {
                if (!smb1Allowed() || !shouldFallbackToSmb1(e)) throw enrich(e);
            }
        }
        SmbFile f = new SmbFile(buildSmbUrl(p), getAuth());
        return new Smb1Handle(new SmbRandomAccessFile(f, "r"), f.length());
    }

    /** 读取整个小文件（字幕） */
    public InputStream getInputStream(String path) throws Exception {
        SmbHandle h = open(path);
        try {
            long size = h.size();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(size > 0 && size < (1 << 20) ? (int) size : 8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = h.read(buf, 0, buf.length)) > 0) {
                bos.write(buf, 0, n);
            }
            return new ByteArrayInputStream(bos.toByteArray());
        } finally {
            h.close();
        }
    }

    /** 文件大小 */
    public long size(String path) throws Exception {
        final String p = path == null ? "" : path;
        if (smb2Allowed()) {
            try {
                return withSmb2Retry(new Smb2Op<Long>() {
                    @Override
                    public Long run(Smb2Client c) throws Exception {
                        return c.size(p);
                    }
                });
            } catch (Exception e) {
                if (!smb1Allowed() || !shouldFallbackToSmb1(e)) throw enrich(e);
            }
        }
        return new SmbFile(buildSmbUrl(p), getAuth()).length();
    }

    /** 生成 smb:// URL（百分号编码，供内部解析 / 日志显示） */
    public String getFileUrl(String path) throws Exception {
        return buildSmbUrl(path == null ? "" : path);
    }

    /* ============================================================
     *  SMB1（jcifs）实现
     * ============================================================ */

    private List<FileItem> listSmb1(String path) throws Exception {
        List<FileItem> items = new ArrayList<>();
        SmbFile dir = new SmbFile(buildSmbUrl(path), getAuth());
        SmbFile[] children = dir.listFiles();
        if (children == null) {
            throw new Exception("SMB1 无法列出目录，请检查共享名、账号或权限");
        }
        for (SmbFile f : children) {
            String name = f.getName();
            if (name == null || name.isEmpty()) continue;
            boolean isDir = f.isDirectory();
            long size = isDir ? 0 : f.length();
            long lastMod = f.lastModified();
            String modified = lastMod > 0
                    ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(lastMod))
                    : "";
            String cleanName = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            if (cleanName.isEmpty() || ".".equals(cleanName) || "..".equals(cleanName)) continue;
            String childPath = (path == null || path.isEmpty() || "/".equals(path))
                    ? "/" + cleanName
                    : (path.endsWith("/") ? path + cleanName : path + "/" + cleanName);
            items.add(new FileItem(cleanName, childPath, isDir, size, modified));
        }
        return items;
    }

    private NtlmPasswordAuthentication getAuth() {
        if (user != null && !user.isEmpty()) {
            return new NtlmPasswordAuthentication(null, user, pass);
        }
        return NtlmPasswordAuthentication.ANONYMOUS;
    }

    private String buildSmbUrl(String path) throws Exception {
        String base = "smb://" + host + (port != 445 ? ":" + port : "") + "/" + encodeSegment(share);
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return base + "/";
        }
        StringBuilder sb = new StringBuilder(base);
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            sb.append('/').append(encodeSegment(seg));
        }
        sb.append('/');
        return sb.toString();
    }

    private static String encodeSegment(String seg) throws Exception {
        return java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20");
    }

    /* ============================================================
     *  内部工具
     * ============================================================ */

    private interface Smb2Op<T> {
        T run(Smb2Client c) throws Exception;
    }

    /** SMB2 操作：连接断了（NAS 休眠/空闲超时）就重连重试一次 */
    private <T> T withSmb2Retry(Smb2Op<T> op) throws Exception {
        boolean hadSession = smb2 != null;
        try {
            return op.run(smb2());
        } catch (Smb2Client.Smb2Exception e) {
            throw e;   // 协议级错误（认证/权限/路径）不重试
        } catch (IOException io) {
            // 首次连接就失败不要重试：会把「连不上」的等待时间翻倍，用户以为卡死了
            if (!hadSession) throw io;
            dropSmb2();
            try {
                return op.run(smb2());
            } catch (Exception again) {
                dropSmb2();
                throw again;
            }
        }
    }

    /** 只有「对方不认 SMB2」才值得退回 SMB1；认证失败退回也没用 */
    private static boolean shouldFallbackToSmb1(Exception e) {
        if (e instanceof Smb2Client.Smb2Exception) {
            int st = ((Smb2Client.Smb2Exception) e).status;
            return st == 0xC00000BB          // NOT_SUPPORTED
                    || st == 0xC0000010      // INVALID_DEVICE_REQUEST
                    || st == 0xC000000D      // INVALID_PARAMETER
                    || st == 0xC0000002      // NOT_IMPLEMENTED
                    || st == 0xC00000C2;     // INVALID_SMB
        }
        // 连接被拒 / 协商阶段断连 → 可能真是 SMB1 老设备
        return true;
    }

    /** 把底层异常翻译成人话 + 给出下一步怎么做 */
    private Exception enrich(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof java.net.ConnectException) {
            return new Exception("连不上 " + host + ":" + port
                    + " —— 请确认机器已开机、和电视在同一局域网、且开了文件共享（macOS："
                    + "系统设置 → 通用 → 共享 → 文件共享 打开）", e);
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return new Exception(host + " 响应超时 —— 网络太慢或防火墙拦了 445 端口", e);
        }
        if (e instanceof java.net.UnknownHostException) {
            return new Exception("找不到主机 " + host + " —— 建议直接填 IP 地址，不要用机器名", e);
        }
        // jcifs 的英文原文对用户没意义，翻成能直接照做的中文
        String low = msg.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("invalid operation for") || low.contains("smbservice")) {
            return new Exception("SMB1 无法访问该共享 —— 服务器很可能已经关闭 SMB1"
                    + "（macOS 13+/Win10+ 默认如此），请把「设置 → SMB 协议」改回「自动」", e);
        }
        if (low.contains("logon failure") || low.contains("account is not authorized")
                || low.contains("0xc000006d") || low.contains("0xc000006a")) {
            return new Exception("用户名或密码不对（SMB1）", e);
        }
        if (low.contains("access is denied") || low.contains("0xc0000022")) {
            return new Exception("拒绝访问 —— 账号没有该共享的权限", e);
        }
        if (low.contains("the network name cannot be found") || low.contains("0xc00000cc")) {
            return new Exception("共享名不存在：" + share, e);
        }
        return new Exception(msg, e);
    }

    /** 解析 smb:// URL → 供播放代理使用 */
    public static String[] parseUrl(String smbUrl) {
        // smb://host[:port]/share/a/b%20c.mkv → {host, port, share, path}
        if (smbUrl == null) return null;
        String s = smbUrl;
        if (s.startsWith("smb://")) s = s.substring(6);
        int slash = s.indexOf('/');
        if (slash < 0) return null;
        String hostPart = s.substring(0, slash);
        String rest = s.substring(slash + 1);
        String port = "445";
        int colon = hostPart.lastIndexOf(':');
        if (colon > 0) {
            port = hostPart.substring(colon + 1);
            hostPart = hostPart.substring(0, colon);
        }
        String[] parts = rest.split("/");
        if (parts.length == 0) return null;
        String shareName;
        StringBuilder path = new StringBuilder();
        int startIdx = 0;
        shareName = decode(parts[0]);
        startIdx = 1;
        for (int i = startIdx; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            path.append('/').append(decode(parts[i]));
        }
        return new String[]{hostPart, port, shareName, path.toString()};
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
