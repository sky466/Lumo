package com.letvcast.player;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebDAV 视频流本地 HTTP 代理。
 *
 * 把带 Basic 认证的 WebDAV 视频桥接成本地 http://127.0.0.1:port/ 流，供播放内核使用
 * （Android 4.3 的播放器无法带认证头，也不支持 setVideoURI(uri, headers)）。
 * 支持 Range（进度条拖动）、HEAD、以及 301/302/307 跳转（AList 等网盘常用）。
 *
 * 注意（v1.17 修复的关键 bug）：Android 4.4(API19) 之前 java.net.Socket **没有**实现
 * java.io.Closeable。旧代码用 `closeQuietly(java.io.Closeable... cs)` 接收 Socket，
 * 在 API18 上会抛 ArrayStoreException（Socket 无法存进 Closeable[]），且发生在
 * HTTP 请求结束的 finally 里 —— 每个请求炸一次、线程死掉、进程被杀，表现就是
 * 「一播放就闪退回主界面」，与播放引擎无关。这里改为 Object... 再按类型判断关闭。
 */
public class WebDavStreamProxy implements IStreamProxy {

    private static final String TAG = "WebDavProxy";

    private static final int MAX_REDIRECTS = 3;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private AtomicBoolean running = new AtomicBoolean(false);

    private String fileUrl;
    private String host;
    private int port;
    private String filePath;
    private String authHeader;
    private String contentType = "video/mp4";
    /** 原始 URL 的 scheme（https 时走 TLS；302 到 CDN 的 https 也走 TLS） */
    private String originalScheme = "http";
    /** 远端文件名（用于给播放器一个带后缀的 URL，部分内核对无后缀 URL 探测更慢） */
    private String fileName = "";

    public WebDavStreamProxy(String fileUrl, String username, String password) {
        this.fileUrl = fileUrl;
        try {
            URL url = new URL(fileUrl);
            this.originalScheme = url.getProtocol();
            this.host = url.getHost();
            int p = url.getPort();
            this.port = (p > 0) ? p : ("https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80);
            // url.getPath() 返回解码后的路径（中文是真实中文），需重新百分号编码，
            // 否则中文直接写进 HTTP 请求行会导致服务器解析失败。
            String decodedPath = url.getPath();
            this.filePath = WebDavClient.encodePathPreserve(
                    (decodedPath == null || decodedPath.isEmpty()) ? "/" : decodedPath);
            String last = decodedPath == null ? "" : decodedPath;
            int slash = last.lastIndexOf('/');
            if (slash >= 0 && slash < last.length() - 1) {
                this.fileName = WebDavClient.encodePathPreserve(last.substring(slash + 1));
                String lower = fileName.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".mkv")) contentType = "video/x-matroska";
                else if (lower.endsWith(".avi")) contentType = "video/x-msvideo";
                else if (lower.endsWith(".ts")) contentType = "video/mp2t";
                else if (lower.endsWith(".flv")) contentType = "video/x-flv";
                else if (lower.endsWith(".mov")) contentType = "video/quicktime";
                else if (lower.endsWith(".webm")) contentType = "video/webm";
                else if (lower.endsWith(".rmvb") || lower.endsWith(".rm")) contentType = "application/vnd.rn-realmedia";
            }
        } catch (Exception e) {
            LogBus.mark("PROXY", "parse url fail: " + e);
            this.host = "";
            this.port = 0;
            this.filePath = "/";
        }
        if (username != null && !username.isEmpty()) {
            String cred = username + ":" + (password == null ? "" : password);
            String encoded = android.util.Base64.encodeToString(
                    cred.getBytes(), android.util.Base64.NO_WRAP);
            this.authHeader = "Basic " + encoded;
        }
    }

    public String start() throws IOException {
        serverSocket = new ServerSocket(0);
        int localPort = serverSocket.getLocalPort();
        running.set(true);
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running.get()) {
                    try {
                        final Socket client = serverSocket.accept();
                        Thread t = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(client);
                            }
                        }, "dav-proxy");
                        t.start();
                    } catch (IOException e) {
                        if (running.get()) Log.e(TAG, "accept error", e);
                    } catch (Throwable t) {
                        LogBus.mark("PROXY", "accept throwable: " + t);
                    }
                }
            }
        }, "dav-proxy-server");
        serverThread.start();
        // 给播放器一个带"文件名"的本地地址：http://127.0.0.1:port/文件名.mp4
        // 播放内核（尤其系统 MediaPlayer）靠后缀猜容器格式，无后缀时探测会慢甚至失败
        String local = "http://127.0.0.1:" + localPort + "/" + (fileName.isEmpty() ? "" : fileName);
        LogBus.mark("PROXY", "started " + local + " -> " + fileUrl);
        return local;
    }

    /** 一次远端请求的结果 */
    private static class Remote {
        Socket socket;
        InputStream in;
        int code;
        long contentLength = -1;
        String contentTypeRemote;
        String contentRange;
    }

    /**
     * 连接远端并发起请求，自动跟随 301/302/303/307 跳转（最多 3 跳）。
     *
     * 两个必须点：
     * 1. AList 对 115 等网盘返回 302 → `https://xxxx.115cdn.net/...` 签名直链，
     *    所以 https 目标必须走 TLS（明文连 443 只会拿到 400 Bad Request）。
     * 2. 跳到别的主机（CDN）后不能再带 Authorization，否则可能被拒绝/泄露账号。
     */
    private Remote openRemote(String reqHost, int reqPort, boolean reqTls, String reqPath,
                              boolean isHead, boolean hasRange, long start, long end) throws Exception {
        String curHost = reqHost;
        int curPort = reqPort;
        boolean curTls = reqTls;
        String curPath = reqPath;
        Remote last = null;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Remote r = new Remote();
            r.socket = NetUtil.connect(curHost, curPort, curTls, 10000);
            OutputStream ros = r.socket.getOutputStream();

            boolean sameHost = curHost.equalsIgnoreCase(host)
                    && curPort == port && curTls == ("https".equalsIgnoreCase(originalScheme));

            StringBuilder req = new StringBuilder();
            req.append(isHead ? "HEAD" : "GET").append(" ").append(curPath).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(curHost);
            if ((curTls && curPort != 443) || (!curTls && curPort != 80)) {
                req.append(":").append(curPort);
            }
            req.append("\r\n");
            req.append("User-Agent: ").append(NetUtil.USER_AGENT).append("\r\n");
            req.append("Accept: */*\r\n");
            req.append("Accept-Encoding: identity\r\n");
            req.append("Connection: close\r\n");
            if (authHeader != null && sameHost) {
                req.append("Authorization: ").append(authHeader).append("\r\n");
            }
            if (hasRange) {
                req.append("Range: bytes=").append(start).append("-")
                        .append(end >= 0 ? end : "").append("\r\n");
            }
            req.append("\r\n");
            ros.write(req.toString().getBytes("UTF-8"));
            ros.flush();

            r.in = r.socket.getInputStream();
            String statusLine = readLine(r.in);
            if (statusLine == null) {
                closeQuietly(r.in, r.socket);
                return last;
            }
            LogBus.mark("PROXY", "remote " + statusLine + " (hop " + hop + " "
                    + (curTls ? "tls " : "") + curHost + ":" + curPort + " " + shorten(curPath) + ")");
            try {
                r.code = Integer.parseInt(statusLine.split(" ")[1]);
            } catch (Exception ignored) {
            }

            String location = null;
            while (true) {
                String line = readLine(r.in);
                if (line == null || line.isEmpty()) break;
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    try {
                        r.contentLength = Long.parseLong(line.substring(15).trim());
                    } catch (Exception ignored) {
                    }
                } else if (lower.startsWith("content-type:")) {
                    String ct = line.substring(13).trim();
                    if (!ct.isEmpty()) r.contentTypeRemote = ct;
                } else if (lower.startsWith("content-range:")) {
                    r.contentRange = line.substring(14).trim();
                } else if (lower.startsWith("location:")) {
                    location = line.substring(9).trim();
                }
            }

            boolean redirect = (r.code == 301 || r.code == 302 || r.code == 303 || r.code == 307);
            if (redirect && location != null && !location.isEmpty() && hop < MAX_REDIRECTS) {
                LogBus.mark("PROXY", "redirect -> " + shorten(location));
                closeQuietly(r.in, r.socket);
                last = r;
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    URL u = new URL(location);
                    curHost = u.getHost();
                    int p = u.getPort();
                    curTls = "https".equalsIgnoreCase(u.getProtocol());
                    curPort = (p > 0) ? p : (curTls ? 443 : 80);
                    String path = u.getPath();
                    if (path == null || path.isEmpty()) path = "/";
                    if (u.getQuery() != null) path = path + "?" + u.getQuery();
                    curPath = path;
                } else {
                    String p2 = location;
                    if (!p2.startsWith("/")) {
                        int idx = curPath.indexOf('?');
                        String base = idx >= 0 ? curPath.substring(0, idx) : curPath;
                        int slash = base.lastIndexOf('/');
                        p2 = (slash >= 0 ? base.substring(0, slash + 1) : "/") + p2;
                    }
                    curPath = p2;
                }
                continue;
            }
            return r;
        }
        return last;
    }

    /** 日志里只打前 60 个字符，避免签名参数刷屏 */
    private static String shorten(String s) {
        if (s == null) return "-";
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    private void handleClient(Socket client) {
        Remote remote = null;
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            java.io.BufferedOutputStream out =
                    new java.io.BufferedOutputStream(client.getOutputStream(), 128 * 1024);

            String requestLine = readLine(in);
            if (requestLine == null) {
                closeQuietly(client);
                return;
            }
            boolean isHead = requestLine.startsWith("HEAD");
            String range = null;
            while (true) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) break;
                if (line.toLowerCase(Locale.ROOT).startsWith("range:")) {
                    range = line.substring(6).trim();
                }
            }

            long start = 0;
            long end = -1;
            boolean hasRange = false;
            if (range != null && range.startsWith("bytes=")) {
                String rangeVal = range.substring(6);
                int dash = rangeVal.indexOf('-');
                if (dash >= 0) {
                    String startStr = rangeVal.substring(0, dash).trim();
                    String endStr = rangeVal.substring(dash + 1).trim();
                    try {
                        if (!startStr.isEmpty()) {
                            start = Long.parseLong(startStr);
                            hasRange = true;
                        }
                        if (!endStr.isEmpty()) {
                            end = Long.parseLong(endStr);
                        }
                    } catch (NumberFormatException nfe) {
                        start = 0;
                        end = -1;
                        hasRange = false;
                    }
                }
            }

            remote = openRemote(host, port, "https".equalsIgnoreCase(originalScheme), filePath,
                    isHead, hasRange, start, end);
            if (remote == null) {
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
                out.flush();
                return;
            }

            int code = remote.code;
            if (code == 401 || code == 403) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
                out.flush();
                return;
            }
            if (code != 200 && code != 206) {
                LogBus.mark("PROXY", "remote error code " + code);
                out.write(("HTTP/1.1 " + code + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes());
                out.flush();
                return;
            }

            // 仅当远端真正返回 206 时才转发 206 和 Content-Range，
            // 否则会生成「206 但无 Content-Range」的畸形响应，导致 seek 卡死。
            boolean isPartial = (code == 206);
            out.write(("HTTP/1.1 " + (isPartial ? "206 Partial Content" : "200 OK") + "\r\n").getBytes());
            if (isPartial && remote.contentRange != null) {
                out.write(("Content-Range: " + remote.contentRange + "\r\n").getBytes());
            }
            if (remote.contentLength >= 0) {
                out.write(("Content-Length: " + remote.contentLength + "\r\n").getBytes());
            }
            String ct = remote.contentTypeRemote != null ? remote.contentTypeRemote : contentType;
            out.write(("Content-Type: " + ct + "\r\n").getBytes());
            out.write("Accept-Ranges: bytes\r\n".getBytes());
            out.write("Connection: close\r\n\r\n".getBytes());
            out.flush();

            if (isHead) {
                return;
            }

            byte[] buf = new byte[128 * 1024];
            int n;
            long total = 0;
            while ((n = remote.in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            LogBus.mark("PROXY", "stream finished " + total + " bytes");

        } catch (java.net.SocketException se) {
            // 播放器 seek/退出时会主动断开连接，ECONNRESET/EPIPE 属于正常现象
            LogBus.mark("PROXY", "client closed (" + se.getMessage() + ")");
        } catch (Throwable e) {
            // 代理出错只影响本次播放，绝不能让它把整个进程带走
            LogBus.mark("PROXY", "handleClient error: " + e);
        } finally {
            if (remote != null) closeQuietly(remote.in, remote.socket);
            closeQuietly(client);
        }
    }

    /**
     * 安全关闭。参数类型必须是 Object：
     * Android 4.4 之前 Socket 不实现 Closeable，用 Closeable... 会抛
     * ArrayStoreException（就是 v1.16 闪退的真凶）。
     */
    private static void closeQuietly(Object... cs) {
        for (Object c : cs) {
            if (c == null) continue;
            try {
                if (c instanceof java.io.Closeable) {
                    ((java.io.Closeable) c).close();
                } else if (c instanceof Socket) {
                    ((Socket) c).close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String readLine(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int c;
        while ((c = is.read()) != -1) {
            if (c == '\n') {
                byte[] data = bos.toByteArray();
                int len = data.length;
                if (len > 0 && data[len - 1] == '\r') len--;
                return new String(data, 0, len, "UTF-8");
            }
            bos.write(c);
        }
        if (bos.size() == 0) return null;
        byte[] data = bos.toByteArray();
        int len = data.length;
        if (len > 0 && data[len - 1] == '\r') len--;
        return new String(data, 0, len, "UTF-8");
    }

    public void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
