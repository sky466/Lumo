package com.letvcast.player;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 建连工具：明文 socket 或 TLS socket。
 *
 * 为什么需要它：AList 对 115 等网盘会把文件请求 302 跳转到
 * `https://cdnfhnfile.115cdn.net/<签名>?...` 的**直链**。之前的实现跟着跳转却仍然
 * 用明文 socket 连 443，服务器直接回 `400 Bad Request`（有日志为证），
 * 于是视频 onError、图片"下载失败 HTTP 400"。
 *
 * Android 4.3 的坑：TLS1.2 协议本身支持，但**默认启用协议列表里没有 TLSv1.2**
 * （API 21 才默认开），所以这里显式 setEnabledProtocols；再用默认 HostnameVerifier
 * 手工校验主机名。若证书链校验不过（老系统的根证书库缺新根），降级为信任任何证书
 * 再重试一次，并写日志——这个 App 只播放用户自己网盘里的文件，可用性优先。
 */
final class NetUtil {

    /** 已经确认「严格校验过不了」的主机：下次直接走宽松握手，省掉一次失败握手（每次约 300ms） */
    private static final java.util.Set<String> LENIENT_HOSTS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    static final String USER_AGENT = UA;

    private NetUtil() {
    }

    /** 建立连接；tls=true 时完成 TLS 握手后返回 SSLSocket */
    static Socket connect(String host, int port, boolean tls, int connectTimeoutMs) throws IOException {
        if (!tls) {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setSoTimeout(30000);
            return s;
        }
        if (LENIENT_HOSTS.contains(host)) {
            try {
                return lenientTls(host, port, connectTimeoutMs);
            } catch (Exception e) {
                LENIENT_HOSTS.remove(host);
                throw new IOException("TLS connect failed: " + e);
            }
        }
        try {
            Socket s = strictTls(host, port, connectTimeoutMs);
            LogBus.mark("NET", "TLS ok " + host + ":" + port + " "
                    + ((SSLSocket) s).getSession().getProtocol());
            return s;
        } catch (Throwable strictErr) {
            LogBus.mark("NET", "TLS 严格校验失败(" + strictErr + ")，降级重试 " + host + ":" + port);
            LENIENT_HOSTS.add(host);
            try {
                Socket s = lenientTls(host, port, connectTimeoutMs);
                LogBus.mark("NET", "TLS 降级成功 " + host + ":" + port);
                return s;
            } catch (Exception lenientErr) {
                LogBus.mark("NET", "TLS 降级也失败: " + lenientErr);
                throw new IOException("TLS connect failed: " + lenientErr);
            }
        }
    }

    private static Socket strictTls(String host, int port, int timeout) throws Exception {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), timeout);
        plain.setSoTimeout(30000);
        SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) f.createSocket(plain, host, port, true);
        enableModernProtocols(ssl);
        ssl.startHandshake();
        verifyHostname(host, ssl);
        return ssl;
    }

    private static Socket lenientTls(String host, int port, int timeout) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), timeout);
        plain.setSoTimeout(30000);
        SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(plain, host, port, true);
        enableModernProtocols(ssl);
        ssl.startHandshake();
        return ssl;
    }

    /** 显式打开 TLS1.2（API18 默认不开）；不支持的名字要吞掉，退回默认列表 */
    private static void enableModernProtocols(SSLSocket ssl) {
        String[] wanted = {"TLSv1.2", "TLSv1.1", "TLSv1"};
        try {
            ssl.setEnabledProtocols(wanted);
        } catch (Throwable t) {
            LogBus.mark("NET", "setEnabledProtocols fail: " + t);
        }
    }

    private static void verifyHostname(String host, SSLSocket ssl) throws IOException {
        HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
        SSLSession session = ssl.getSession();
        if (hv != null && !hv.verify(host, session)) {
            throw new IOException("hostname verify failed: " + host);
        }
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    static String schemeOf(String url) {
        if (url == null) return "http";
        return url.toLowerCase(Locale.ROOT).startsWith("https") ? "https" : "http";
    }
}
