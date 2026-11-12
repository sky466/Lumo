package com.letvcast.player;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SMB 视频流本地 HTTP 代理。
 *
 * <p>把 smb:// 文件桥接成 http://127.0.0.1:port/ 流，供 ijk / 系统 MediaPlayer / VLC 播放，
 * 支持 Range 请求（进度条拖动）。
 *
 * <p>底层走 {@link SmbClient} 的统一句柄，SMB2/SMB1 都能用，协议细节与上层无关。
 */
public class SmbStreamProxy implements IStreamProxy {

    private static final String TAG = "SmbStreamProxy";

    private ServerSocket serverSocket;
    private Thread serverThread;
    private AtomicBoolean running = new AtomicBoolean(false);

    private final String host;
    private final int port;
    private final String share;
    private final String path;
    private final String username;
    private final String password;
    private String contentType = "video/mp4";

    /** 共享客户端：多个请求复用同一条 SMB 会话，少一轮协商 */
    private SmbClient client;

    /**
     * @param smbUrl smb://host[:port]/share/path（百分号编码）
     */
    public SmbStreamProxy(String smbUrl, String user, String password) {
        String[] parts = SmbClient.parseUrl(smbUrl);
        if (parts == null) {
            this.host = "";
            this.port = 445;
            this.share = "";
            this.path = "";
        } else {
            this.host = parts[0];
            int p = 445;
            try {
                p = Integer.parseInt(parts[1]);
            } catch (Exception ignored) {
            }
            this.port = p;
            this.share = parts[2];
            this.path = parts[3];
        }
        this.username = user;
        this.password = password;
    }

    public SmbStreamProxy(String host, int port, String share, String path, String user, String password) {
        this.host = host;
        this.port = port;
        this.share = share;
        this.path = path;
        this.username = user;
        this.password = password;
    }

    public void setContentType(String type) {
        if (type != null) this.contentType = type;
    }

    @Override
    public String start() throws IOException {
        if (host == null || host.isEmpty()) {
            throw new IOException("SMB 地址解析失败");
        }
        serverSocket = new ServerSocket(0);
        int listenPort = serverSocket.getLocalPort();
        running.set(true);

        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running.get()) {
                    try {
                        final Socket clientSocket = serverSocket.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(clientSocket);
                            }
                        }, "smb-http").start();
                    } catch (IOException e) {
                        if (running.get()) Log.e(TAG, "accept error", e);
                    }
                }
            }
        }, "smb-proxy");
        serverThread.start();

        return "http://127.0.0.1:" + listenPort + "/";
    }

    private synchronized SmbClient client() {
        if (client == null) {
            client = new SmbClient(host, port, share, username, password);
        }
        return client;
    }

    private void handleClient(Socket sock) {
        SmbHandle h = null;
        try {
            sock.setSoTimeout(30000);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) {
                closeQuietly(sock);
                return;
            }
            boolean isHead = requestLine.toUpperCase(Locale.ROOT).startsWith("HEAD");
            String range = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("range:")) {
                    range = line.substring(6).trim();
                }
            }

            h = client().open(path);
            final long fileLength = h.size();

            long start = 0;
            long end = fileLength - 1;
            boolean hasRange = false;
            if (range != null && range.startsWith("bytes=")) {
                String val = range.substring(6);
                int dash = val.indexOf('-');
                if (dash >= 0) {
                    String startStr = val.substring(0, dash).trim();
                    String endStr = val.substring(dash + 1).trim();
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
                        end = fileLength - 1;
                        hasRange = false;
                    }
                }
            }
            if (hasRange && (start >= fileLength || end < start)) {
                start = 0;
                end = fileLength - 1;
                hasRange = false;
            }
            if (end >= fileLength) end = fileLength - 1;

            if (hasRange) {
                out.write("HTTP/1.1 206 Partial Content\r\n".getBytes());
                out.write(("Content-Range: bytes " + start + "-" + end + "/" + fileLength + "\r\n").getBytes());
                out.write(("Content-Length: " + (end - start + 1) + "\r\n").getBytes());
            } else {
                out.write("HTTP/1.1 200 OK\r\n".getBytes());
                out.write(("Content-Length: " + fileLength + "\r\n").getBytes());
            }
            out.write(("Content-Type: " + contentType + "\r\n").getBytes());
            out.write("Accept-Ranges: bytes\r\n".getBytes());
            out.write("Connection: close\r\n\r\n".getBytes());

            if (isHead) {
                out.flush();
                return;
            }

            h.seek(start);
            byte[] buf = new byte[65536];
            long remaining = end - start + 1;
            while (remaining > 0 && running.get()) {
                int toRead = (int) Math.min(buf.length, remaining);
                int n = h.read(buf, 0, toRead);
                if (n <= 0) break;
                out.write(buf, 0, n);
                remaining -= n;
            }
            out.flush();
        } catch (Exception e) {
            Log.e(TAG, "handle client error", e);
        } finally {
            if (h != null) h.close();
            closeQuietly(sock);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        synchronized (this) {
            if (client != null) {
                client.close();
                client = null;
            }
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }

    /** 读取一行（正确处理 \n / \r\n / 连接关闭返回 null） */
    private static String readLine(InputStream is) throws IOException {
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
}
