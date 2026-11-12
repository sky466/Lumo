package com.letvcast.player;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * WebDAV 客户端核心（零第三方依赖，底层 Socket 手写 HTTP 请求）。
 *
 * 为什么不用 HttpURLConnection：
 * Android 4.x 的 HttpURLConnection.setRequestMethod("PROPFIND") 会把非标准方法
 * 污染成 "PROPFIND;"（追加分号），导致服务器报 "Unknown method 'PROPFIND;'"。
 * 这里直接手写 HTTP 请求，彻底绕开该 bug。
 */
public class WebDavClient {

    private String baseUrl;
    private String username;
    private String password;
    private String authHeader;

    // 解析后的主机、端口、路径前缀
    private String host;
    private int port;
    private boolean tls;
    private String pathPrefix;

    public WebDavClient(String baseUrl, String username, String password) {
        baseUrl = cleanUrl(baseUrl);
        while (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;

        if (username != null && !username.isEmpty()) {
            String cred = username + ":" + (password == null ? "" : password);
            String encoded = android.util.Base64.encodeToString(
                    cred.getBytes(), android.util.Base64.NO_WRAP);
            this.authHeader = "Basic " + encoded;
        }

        try {
            parseBaseUrl(baseUrl);
        } catch (Exception e) {
            // 地址解析失败时，host/port 保持默认，后续 request 会抛出更明确的错误
            host = "";
            port = 0;
            pathPrefix = "/";
        }
    }

    private void parseBaseUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        this.host = url.getHost();
        this.tls = "https".equalsIgnoreCase(url.getProtocol());
        int p = url.getPort();
        this.port = (p > 0) ? p : (tls ? 443 : 80);
        String path = url.getPath();
        if (path == null || path.isEmpty()) path = "/";
        this.pathPrefix = path;
    }

    // ============ 对外接口 ============

    public List<FileItem> list(String path) throws Exception {
        String fullPath = joinPath(pathPrefix, path);
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
                + "<D:propfind xmlns:D=\"DAV:\">"
                + "<D:prop>"
                + "<D:resourcetype/>"
                + "<D:displayname/>"
                + "<D:getcontentlength/>"
                + "<D:getlastmodified/>"
                + "</D:prop>"
                + "</D:propfind>";
        HttpResponse resp = request("PROPFIND", fullPath, body,
                new String[]{"Depth", "1", "Content-Type", "application/xml; charset=utf-8"});
        // 跟随重定向（某些服务器对末尾斜杠等做 301 跳转）
        if ((resp.code == 301 || resp.code == 302 || resp.code == 307) && resp.headers != null) {
            String location = resp.headers.get("location");
            if (location != null && !location.isEmpty()) {
                LogBus.mark("DAV", "PROPFIND redirect -> " + location);
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    resp = requestUrl("PROPFIND", location, body,
                            new String[]{"Depth", "1", "Content-Type", "application/xml; charset=utf-8"});
                } else {
                    String newPath = resolveRedirectPath(location);
                    if (newPath != null) {
                        resp = request("PROPFIND", newPath, body,
                                new String[]{"Depth", "1", "Content-Type", "application/xml; charset=utf-8"});
                    }
                }
            }
        }
        if (resp.code != 207 && resp.code != 200) {
            throw new Exception("HTTP " + resp.code);
        }
        return parsePropfind(new String(resp.body, "UTF-8"), path);
    }

    public String buildFileUrl(String path) throws Exception {
        // path 是相对路径（如 /电影/a.mp4，已去掉 pathPrefix），拼成完整可访问 URL
        String p = path;
        if (p == null) p = "";
        // 如果是完整 URL 直接返回
        if (p.startsWith("http://") || p.startsWith("https://")) {
            return p;
        }
        String pp = p;
        while (pp.startsWith("/")) pp = pp.substring(1);
        // 用 getRawPath 语义：baseUrl 保留原始前缀，pp 已经是相对路径
        return baseUrl + "/" + pp;
    }

    /**
     * 下载文件内容（用于图片查看等），返回完整字节
     * @param path WebDAV href（如 /dav/照片/a.jpg）
     */
    public byte[] download(String path) throws Exception {
        String fullPath = joinPath(pathPrefix, path);
        HttpResponse resp = request("GET", fullPath, null, null);
        if ((resp.code == 301 || resp.code == 302 || resp.code == 307) && resp.headers != null) {
            String location = resp.headers.get("location");
            if (location != null && !location.isEmpty()) {
                LogBus.mark("DAV", "GET redirect -> " + location);
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    resp = requestUrl("GET", location, null, null);
                } else {
                    String newPath = resolveRedirectPath(location);
                    if (newPath != null) {
                        resp = request("GET", newPath, null, null);
                    }
                }
            }
        }
        if (resp.code != 200 && resp.code != 206) {
            throw new Exception("下载失败 HTTP " + resp.code
                    + (resp.code == 404 ? "（路径不存在）" : ""));
        }
        if (resp.body == null || resp.body.length == 0) {
            throw new Exception("下载失败：服务器返回 0 字节（可能需要跟随直链/权限不足）");
        }
        return resp.body;
    }

    /** 把重定向的 Location 转成可继续请求的 path（相对 pathPrefix 或完整 URL） */
    private String resolveRedirectPath(String location) {
        if (location == null || location.isEmpty()) return null;
        // 完整 URL：提取 path 部分
        if (location.startsWith("http://") || location.startsWith("https://")) {
            try {
                URL u = new URL(location);
                String p = u.getPath();
                if (p == null || p.isEmpty()) p = "/";
                return p;
            } catch (Exception e) {
                return null;
            }
        }
        // 相对路径：直接使用
        return location.startsWith("/") ? location : "/" + location;
    }

    public void testConnection() throws Exception {
        if (host == null || host.isEmpty() || port <= 0) {
            throw new Exception("地址格式错误，请检查是否以 http:// 或 https:// 开头");
        }
        String fullPath = joinPath(pathPrefix, "");
        String body = "<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\"><D:prop><D:resourcetype/></D:prop></D:propfind>";
        HttpResponse resp;
        try {
            resp = request("PROPFIND", fullPath, body,
                    new String[]{"Depth", "0", "Content-Type", "application/xml; charset=utf-8"});
        } catch (java.net.UnknownHostException e) {
            throw new Exception("无法连接服务器（请确认地址、端口是否正确）");
        } catch (java.net.ConnectException e) {
            throw new Exception("无法连接服务器（网络不通或地址/端口错误）");
        } catch (java.net.SocketTimeoutException e) {
            throw new Exception("连接超时（请确认地址、端口是否正确）");
        } catch (Exception e) {
            String detail = e.getMessage();
            if (detail == null || detail.isEmpty()) detail = e.getClass().getSimpleName();
            throw new Exception("连接出错（" + detail + "）");
        }

        if (resp.code == 207 || resp.code == 200 || resp.code == 301 || resp.code == 302) {
            return;
        } else if (resp.code == 401 || resp.code == 403) {
            throw new Exception("账号或密码错误 (HTTP " + resp.code + ")");
        } else if (resp.code == 404) {
            throw new Exception("路径不存在，请检查地址是否包含 /dav 等正确路径");
        } else {
            throw new Exception("服务器返回错误 (HTTP " + resp.code + ")，可能不是 WebDAV 服务");
        }
    }

    // ============ 底层 HTTP（手写 Socket） ============

    private static class HttpResponse {
        int code;
        byte[] body;
        java.util.Map<String, String> headers;
    }

    private HttpResponse request(String method, String path, String body, String[] headers) throws Exception {
        return requestTo(host, port, tls, path, method, body, headers);
    }

    /** 请求完整 URL（用于跟随跨主机跳转，如 AList 的签名直链） */
    private HttpResponse requestUrl(String method, String urlStr, String body, String[] headers) throws Exception {
        URL u = new URL(urlStr);
        String h = u.getHost();
        int p = u.getPort();
        int pp = (p > 0) ? p : ("https".equalsIgnoreCase(u.getProtocol()) ? 443 : 80);
        String path = u.getPath();
        if (path == null || path.isEmpty()) path = "/";
        // v1.49：query 单独传。以前拼进 path 再交给 encodePathPreserve，
        // '?' 不在安全字符里会被编成 %3F，AList/115 那种签名直链（?sign=..&t=..）必然 404
        return requestTo(h, pp, "https".equalsIgnoreCase(u.getProtocol()), path, u.getQuery(),
                method, body, headers);
    }

    private HttpResponse requestTo(String reqHost, int reqPort, boolean tls, String path,
                                   String method, String body, String[] headers) throws Exception {
        return requestTo(reqHost, reqPort, tls, path, null, method, body, headers);
    }

    private HttpResponse requestTo(String reqHost, int reqPort, boolean tls, String path, String query,
                                   String method, String body, String[] headers) throws Exception {
        // 关键：https 目标（AList 302 出来的 115 CDN 直链）必须走 TLS，
        // 否则服务端只会回 400 Bad Request
        Socket socket = NetUtil.connect(reqHost, reqPort, tls, 8000);
        try {
            socket.setSoTimeout(15000);
            OutputStream os = socket.getOutputStream();

            StringBuilder req = new StringBuilder();
            // 对路径做 URL 编码（保留斜杠和已编码的 %XX），避免中文等非 ASCII 字符
            // 直接写进 HTTP 请求行导致服务器解析失败
            String encodedPath = encodePathPreserve(path);
            String reqLine = encodedPath.isEmpty() ? "/" : encodedPath;
            if (query != null && !query.isEmpty()) reqLine = reqLine + "?" + query;
            req.append(method).append(" ").append(reqLine).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(reqHost);
            if ((tls && reqPort != 443) || (!tls && reqPort != 80)) req.append(":").append(reqPort);
            req.append("\r\n");
            req.append("User-Agent: ").append(NetUtil.USER_AGENT).append("\r\n");
            req.append("Accept: */*\r\n");
            req.append("Accept-Encoding: identity\r\n");
            req.append("Connection: close\r\n");
            // v1.49：只有还是同一台服务器才带 Basic 凭据。
            // AList 之类的 302 会把请求甩到 CDN 的绝对地址，凭据不能跟着过去
            boolean sameServer = reqHost != null && host != null && reqHost.equalsIgnoreCase(host)
                    && reqPort == port;
            if (authHeader != null && sameServer) {
                req.append("Authorization: ").append(authHeader).append("\r\n");
            }
            if (headers != null) {
                for (int i = 0; i + 1 < headers.length; i += 2) {
                    req.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
                }
            }
            if (body != null && body.length() > 0) {
                req.append("Content-Length: ").append(body.getBytes("UTF-8").length).append("\r\n");
            }
            req.append("\r\n");
            os.write(req.toString().getBytes("UTF-8"));
            if (body != null && body.length() > 0) {
                os.write(body.getBytes("UTF-8"));
            }
            os.flush();

            InputStream is = socket.getInputStream();
            HttpResponse resp = readResponse(is);
            is.close();
            String p = path.length() > 60 ? path.substring(0, 60) + "…" : path;
            LogBus.mark("DAV", method + " " + p + " -> " + resp.code
                    + " bytes=" + (resp.body == null ? -1 : resp.body.length)
                    + " @" + (tls ? "tls:" : "") + reqHost + ":" + reqPort);
            return resp;
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private HttpResponse readResponse(InputStream is) throws Exception {
        // 读状态行
        String statusLine = readLine(is);
        if (statusLine == null || statusLine.isEmpty()) {
            throw new Exception("服务器无响应");
        }
        int code = 0;
        try {
            String[] parts = statusLine.split(" ");
            code = Integer.parseInt(parts[1]);
        } catch (Exception e) {
            code = 0;
        }

        // 读 headers
        java.util.Map<String, String> respHeaders = new java.util.LinkedHashMap<String, String>();
        while (true) {
            String line = readLine(is);
            if (line == null || line.isEmpty()) break;
            int idx = line.indexOf(':');
            if (idx > 0) {
                respHeaders.put(line.substring(0, idx).trim().toLowerCase(),
                        line.substring(idx + 1).trim());
            }
        }

        // 读 body
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        String contentLength = respHeaders.get("content-length");
        String transferEncoding = respHeaders.get("transfer-encoding");
        byte[] buf = new byte[8192];
        if (contentLength != null) {
            long len;
            try {
                len = Long.parseLong(contentLength.trim());
            } catch (NumberFormatException e) {
                len = -1; // 非法 Content-Length，回退到读到 EOF
            }
            if (len >= 0) {
                long remaining = len;
                while (remaining > 0) {
                    int n = is.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n == -1) break;
                    bos.write(buf, 0, n);
                    remaining -= n;
                }
            } else {
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
            }
        } else if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
            // 解析 chunked 编码
            while (true) {
                String sizeLine = readLine(is);
                if (sizeLine == null) break;
                sizeLine = sizeLine.trim();
                int semi = sizeLine.indexOf(';');
                if (semi >= 0) sizeLine = sizeLine.substring(0, semi).trim();
                int chunkSize;
                try {
                    chunkSize = Integer.parseInt(sizeLine, 16);
                } catch (Exception e) {
                    break;
                }
                if (chunkSize == 0) {
                    // 读完最后的空行
                    readLine(is);
                    break;
                }
                int read = 0;
                while (read < chunkSize) {
                    int n = is.read(buf, 0, Math.min(buf.length, chunkSize - read));
                    if (n == -1) break;
                    bos.write(buf, 0, n);
                    read += n;
                }
                readLine(is); // 读 chunk 末尾 CRLF
            }
        } else {
            // 无长度，读到 EOF
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        }

        HttpResponse resp = new HttpResponse();
        resp.code = code;
        resp.body = bos.toByteArray();
        resp.headers = respHeaders;
        return resp;
    }

    private static String readLine(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int prev = -1;
        int c;
        while ((c = is.read()) != -1) {
            if (c == '\n') {
                // 去掉前面的 \r
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

    // ============ 工具方法 ============

    private static String joinPath(String prefix, String path) {
        StringBuilder sb = new StringBuilder();
        if (prefix != null && !prefix.isEmpty()) sb.append(prefix);
        if (path != null && !path.isEmpty()) {
            // 去掉 path 前导斜杠，避免与 prefix 末尾斜杠叠加成双斜杠（如 /dav//电影）
            String p = path;
            while (p.startsWith("/")) p = p.substring(1);
            if (p.isEmpty()) {
                // path 全是斜杠（如 "/"），只保证结果以 / 结尾
                if (sb.length() == 0) return "/";
                if (sb.charAt(sb.length() - 1) != '/') sb.append('/');
                return sb.toString();
            }
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '/') sb.append('/');
            sb.append(p);
        }
        return sb.toString();
    }

    private static String cleanUrl(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c < 0x20 || c == 0x7f) continue;
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 对 HTTP 请求路径做编码：保留斜杠 /、已编码的 %XX、以及 ASCII 安全字符，
     * 只对中文等非 ASCII 字符和空格等做百分号编码。
     * 公开供 WebDavStreamProxy 等复用。
     */
    public static String encodePathPreserve(String path) {
        if (path == null || path.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        // 逐字符扫描，对 UTF-8 多字节字符整体编码
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                sb.append('/');
            } else if (c == '%' && i + 2 < path.length()
                    && isHex(path.charAt(i + 1)) && isHex(path.charAt(i + 2))) {
                // 已编码的 %XX 保留
                sb.append(c).append(path.charAt(i + 1)).append(path.charAt(i + 2));
                i += 2;
            } else if (isSafeChar(c)) {
                sb.append(c);
            } else if (Character.isHighSurrogate(c) && i + 1 < path.length()
                    && Character.isLowSurrogate(path.charAt(i + 1))) {
                // 代理对（emoji / 扩展平面汉字）：把高低代理一起编码，避免拆成半个字符变成 '?'
                String s = new String(new char[]{c, path.charAt(i + 1)});
                encodeUtf8(sb, s);
                i++;
            } else {
                // 普通需编码字符：取该字符的 UTF-8 字节做 %XX
                encodeUtf8(sb, String.valueOf(c));
            }
        }
        return sb.toString();
    }

    private static void encodeUtf8(StringBuilder sb, String s) {
        try {
            byte[] bs = s.getBytes("UTF-8");
            for (byte b : bs) {
                sb.append('%');
                String hex = Integer.toHexString(b & 0xFF).toUpperCase();
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
        } catch (Exception e) {
            sb.append(s);
        }
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isSafeChar(char c) {
        // RFC 3986 未保留字符 + 一些保留字符，路径中安全
        if (c >= 'a' && c <= 'z') return true;
        if (c >= 'A' && c <= 'Z') return true;
        if (c >= '0' && c <= '9') return true;
        return "-._~!$&'()*+,;=:@".indexOf(c) >= 0;
    }

    // ============ XML 解析 ============

    private List<FileItem> parsePropfind(String xml, String currentPath) throws Exception {
        List<FileItem> items = new ArrayList<>();

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        // 服务器可能返回带外部实体的 XML，禁掉 DTD，避免拿到本机私有文件
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setExpandEntityReferences(false);
        } catch (Throwable ignored) {
        }
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(new java.io.ByteArrayInputStream(xml.getBytes("UTF-8")));

        NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
        for (int i = 0; i < responses.getLength(); i++) {
            Element resp = (Element) responses.item(i);
            String href = getChildText(resp, "href");
            if (href == null) continue;

            String decodedHref = safeDecode(href);
            // 统一把 href 归一化成「相对 pathPrefix 的路径」：
            // 兼容完整 URL（http://host/dav/电影）、带前缀相对路径（/dav/电影）、
            // 不带前缀相对路径（/电影）三种形式。
            String relPath = toRelativePath(decodedHref);
            String normalizedCurrent = normalizePath(currentPath);

            // 过滤当前目录自身（避免把当前目录当成「同名子目录」重复列出）
            if (relPath.equals(normalizedCurrent)) {
                continue;
            }

            boolean isDir = resp.getElementsByTagNameNS("DAV:", "collection").getLength() > 0;

            String displayName = getChildText(resp, "displayname");
            long size = 0;
            String sizeStr = getChildText(resp, "getcontentlength");
            if (sizeStr != null) {
                try { size = Long.parseLong(sizeStr.trim()); } catch (Exception ignored) {}
            }
            String modified = getChildText(resp, "getlastmodified");

            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = extractNameFromHref(relPath);
            }
            if (displayName == null || displayName.trim().isEmpty() || displayName.equals("/")) {
                continue;
            }

            items.add(new FileItem(displayName.trim(), relPath, isDir, size, modified));
        }
        return items;
    }

    private static String getChildText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagNameNS("DAV:", tagName);
        if (list.getLength() == 0) {
            list = parent.getElementsByTagName(tagName);
        }
        if (list.getLength() > 0) {
            Node n = list.item(0);
            if (n != null) return n.getTextContent();
        }
        return null;
    }

    /**
     * 把 PROPFIND 返回的 href 归一化成「相对 pathPrefix 的路径」（如 /电影）。
     *
     * 兼容三种服务器返回形式：
     * 1. 完整 URL：http://host:port/dav/电影 → /电影
     * 2. 带挂载前缀的相对路径：/dav/电影 → /电影
     * 3. 不带前缀的相对路径：/电影 → /电影（服务器已剥掉前缀）
     */
    private String toRelativePath(String href) {
        if (href == null) return "";
        String h = href.trim();
        if (h.isEmpty()) return "";

        // 1. 完整 URL → 提取 path 部分
        if (h.startsWith("http://") || h.startsWith("https://")) {
            try {
                String path = new URL(h).getPath();
                if (path == null || path.isEmpty()) path = "/";
                h = path;
            } catch (Exception e) {
                // 手动提取：找 "://" 后第一个 '/'
                int idx = h.indexOf("://");
                if (idx >= 0) {
                    String rest = h.substring(idx + 3);
                    int slash = rest.indexOf('/');
                    h = (slash >= 0) ? rest.substring(slash) : "/";
                }
            }
        }

        // 2. 去掉 pathPrefix 前缀
        String prefix = pathPrefix;
        if (prefix != null && !prefix.isEmpty() && !"/".equals(prefix)) {
            String normPrefix = normalizePath(prefix);      // 如 /dav
            String normH = normalizePath(h);
            if (normH.equals(normPrefix)) return "";          // 就是挂载根目录自身
            if (normH.startsWith(normPrefix + "/")) {
                return normH.substring(normPrefix.length());  // /dav/电影 → /电影
            }
            // h 不以 prefix 开头，说明服务器返回的已是相对路径，直接归一化返回
            return normH;
        }

        // prefix 为空或 "/"，直接返回归一化后的 h
        return normalizePath(h);
    }

    private static String extractNameFromHref(String relPath) {
        if (relPath == null) return null;
        String s = relPath;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int idx = s.lastIndexOf('/');
        if (idx >= 0 && idx < s.length() - 1) {
            return s.substring(idx + 1);
        }
        return s.isEmpty() ? null : s;
    }

    private static String safeDecode(String s) {
        try {
            // URLDecoder 会把 '+' 当空格，但 WebDAV href 里的 '+' 是字面加号，
            // 先把它转成 %2B 再解码，避免文件名里的 + 被破坏。
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String normalizePath(String p) {
        if (p == null) return "";
        String s = p;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return "";
        if (!s.startsWith("/")) s = "/" + s;
        return s;
    }
}
