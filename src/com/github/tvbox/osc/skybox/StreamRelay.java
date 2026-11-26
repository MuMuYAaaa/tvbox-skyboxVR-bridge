package com.github.tvbox.osc.skybox;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;

/** Session-scoped, loopback-only HTTP media relay. No Android or third-party dependencies. */
public final class StreamRelay implements Closeable {
    private final ServerSocket server;
    private final String prefix = "/" + UUID.randomUUID().toString() + "/";
    private final Map<String, String> routes = new ConcurrentHashMap<>();
    private final Map<String, String> reverseRoutes = new ConcurrentHashMap<>();
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private final Set<HttpURLConnection> upstreams = Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>());
    private final ExecutorService workers;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final URL original;
    private volatile boolean closed;
    private volatile String last = "等待 SKYBOX 请求";
    private volatile boolean failed;
    private volatile long lastActivity = System.currentTimeMillis();
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");

    public StreamRelay(String url, Map<String, String> requestHeaders) throws IOException {
        original = new URL(url);
        if (!isHttp(original)) throw new IOException("HTTP media required");
        if (requestHeaders != null) for (Map.Entry<String, String> h : requestHeaders.entrySet()) {
            String k = h.getKey(), value = h.getValue();
            if (k != null && value != null && k.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                    && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) headers.put(k, value.trim());
        }
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
        workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(32),
                new ThreadFactory() { public Thread newThread(Runnable task) {
                    Thread t = new Thread(task, "skybox-stream"); t.setDaemon(true); return t;
                } });
        Thread accept = new Thread(new Runnable() { public void run() { accept(); } }, "skybox-listen");
        accept.setDaemon(true);
        accept.start();
    }

    private static boolean isHttp(URL url) {
        return "http".equalsIgnoreCase(url.getProtocol()) || "https".equalsIgnoreCase(url.getProtocol());
    }

    public synchronized String mediaUrl() throws IOException { return route(original); }

    private synchronized String route(URL url) throws IOException {
        if (!isHttp(url)) throw new IOException("Unsupported playlist protocol");
        String value = url.toExternalForm();
        String known = reverseRoutes.get(value);
        if (known != null) return known;
        if (routes.size() >= 50000) throw new IOException("Playlist route limit");
        String path = url.getPath().toLowerCase(Locale.ROOT);
        String ext = ".mp4";
        for (String candidate : new String[]{".m3u8", ".m3u", ".mkv", ".ts", ".m4s", ".aac", ".mp3", ".vtt", ".key", ".mp4"}) {
            if (path.endsWith(candidate)) { ext = candidate; break; }
        }
        String local = prefix + sequence.incrementAndGet() + "/media" + ext;
        String result = "http://127.0.0.1:" + server.getLocalPort() + local;
        routes.put(local, value);
        reverseRoutes.put(value, result);
        return result;
    }

    private void accept() {
        while (!closed) {
            Socket accepted = null;
            try {
                accepted = server.accept();
                accepted.setSoTimeout(30000);
                sockets.add(accepted);
                final Socket client = accepted;
                workers.execute(new Runnable() { public void run() { serve(client); } });
            } catch (IOException | RejectedExecutionException e) {
                if (accepted != null) { sockets.remove(accepted); try { accepted.close(); } catch (IOException ignored) {} }
            }
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1;) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("HTTP line too long");
        }
        return b.toString("ISO-8859-1");
    }

    private void serve(Socket socket) {
        HttpURLConnection connection = null;
        boolean sent = false;
        try (Socket client = socket) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            String[] request = line(in).split(" ");
            if (request.length < 2) return;
            String method = request[0];
            Map<String, String> incoming = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int n = 0; n < 100; n++) {
                String h = line(in);
                if (h.length() == 0) break;
                int colon = h.indexOf(':');
                if (colon > 0) incoming.put(h.substring(0, colon).trim(), h.substring(colon + 1).trim());
                if (n == 99) throw new IOException("Too many HTTP headers");
            }
            String remote = routes.get(request[1]);
            if (remote == null) { send(out, 404, "text/plain", new byte[0], false); return; }
            if (!method.equals("GET") && !method.equals("HEAD")) { send(out, 405, "text/plain", new byte[0], false); return; }
            requests.incrementAndGet();
            lastActivity = System.currentTimeMillis();
            connection = connect(new URL(remote), method, incoming);
            int status = connection.getResponseCode();
            String type = connection.getContentType();
            last = "上游 HTTP " + status + "；类型 " + safeType(type);
            if (status >= 400) failed = true;
            if (status == 204 || status == 304 || method.equals("HEAD")) {
                head(out, status, type == null ? "application/octet-stream" : type,
                        contentLength(connection), connection, false);
                sent = true;
                out.flush();
                return;
            }
            InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (body == null) { send(out, status, "text/plain", new byte[0], false); return; }
            boolean gzip = "gzip".equalsIgnoreCase(connection.getContentEncoding());
            if (gzip) body = new GZIPInputStream(body);
            PushbackInputStream stream = new PushbackInputStream(new BufferedInputStream(body), 512);
            ByteArrayOutputStream sniff = new ByteArrayOutputStream();
            // Do not wait for 512 bytes from a live source; one buffered read is sufficient.
            byte[] probe = new byte[512];
            int count = 0;
            while (count < 64) {
                int n = stream.read(probe, count, 64 - count);
                if (n < 0) break;
                count += n;
            }
            if (count > 0) { sniff.write(probe, 0, count); stream.unread(probe, 0, count); }
            String beginning = new String(sniff.toByteArray(), StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
            boolean playlist = beginning.startsWith("#EXTM3U");
            if (playlist && status >= 200 && status < 300) {
                String manifest = new String(readLimited(stream, 4 * 1024 * 1024), StandardCharsets.UTF_8);
                byte[] rewritten = rewrite(manifest, connection.getURL()).getBytes(StandardCharsets.UTF_8);
                last = "M3U8 已转发；分片及密钥链接已接入转发";
                sent = true;
                send(out, 200, "application/vnd.apple.mpegurl", rewritten, false);
                bytes.addAndGet(rewritten.length);
            } else {
                if (beginning.startsWith("<!DOCTYPE html") || beginning.startsWith("<html")) {
                    failed = true;
                    last = "上游返回网页，未取得视频；HTTP " + status;
                    send(out, 502, "text/plain", "Upstream returned HTML".getBytes(StandardCharsets.UTF_8), false);
                    return;
                }
                if (type == null || type.contains("octet-stream") || type.startsWith("image/")) type = guessType(connection.getURL());
                head(out, status, type, gzip ? -1 : contentLength(connection), connection, gzip);
                sent = true;
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = stream.read(buffer)) != -1;) {
                    out.write(buffer, 0, n);
                    out.flush();
                    bytes.addAndGet(n);
                    lastActivity = System.currentTimeMillis();
                }
            }
            out.flush();
            stream.close();
        } catch (IOException | RuntimeException e) {
            // Client cancellations are normal during probing and seeking.
            if (!(e instanceof SocketException) && !(e instanceof EOFException) && !closed) {
                failed = true;
                last = (sent ? "传输中断：" : "取流失败：") + e.getClass().getSimpleName();
            }
        } finally {
            if (connection != null) { upstreams.remove(connection); connection.disconnect(); }
            sockets.remove(socket);
        }
    }

    private HttpURLConnection connect(URL target, String method, Map<String, String> incoming) throws IOException {
        for (int redirects = 0; redirects < 10; redirects++) {
            HttpURLConnection c = (HttpURLConnection) target.openConnection();
            upstreams.add(c);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                String key = h.getKey();
                if (key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Connection")
                        || key.equalsIgnoreCase("Content-Length") || key.equalsIgnoreCase("Accept-Encoding")
                        || key.equalsIgnoreCase("Range") || key.equalsIgnoreCase("Transfer-Encoding")) continue;
                if ((key.equalsIgnoreCase("Authorization") || key.equalsIgnoreCase("Cookie"))
                        && !(target.getHost().equalsIgnoreCase(original.getHost())
                        && target.getProtocol().equalsIgnoreCase(original.getProtocol())
                        && effectivePort(target) == effectivePort(original))) continue;
                c.setRequestProperty(key, h.getValue());
            }
            try {
            synchronized (cookies) {
                Map<String, List<String>> saved = cookies.get(target.toURI(), Collections.<String, List<String>>emptyMap());
                for (Map.Entry<String, List<String>> h : saved.entrySet())
                    for (String value : h.getValue()) c.addRequestProperty(h.getKey(), value);
            }
            c.setRequestProperty("Accept-Encoding", "identity");
            for (String key : new String[]{"Range", "If-Range", "If-None-Match", "If-Modified-Since"})
                if (incoming.containsKey(key)) c.setRequestProperty(key, incoming.get(key));
                int status = c.getResponseCode();
                synchronized (cookies) { cookies.put(target.toURI(), c.getHeaderFields()); }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = c.getHeaderField("Location");
                    if (location == null) return c;
                    target = new URL(target, location);
                    if (!isHttp(target)) throw new IOException("Unsupported redirect scheme");
                    upstreams.remove(c); c.disconnect();
                    continue;
                }
                return c;
            } catch (URISyntaxException e) { upstreams.remove(c); c.disconnect(); throw new IOException("Invalid upstream URI", e); }
            catch (IOException | RuntimeException e) { upstreams.remove(c); c.disconnect(); throw e; }
        }
        throw new IOException("Redirect limit");
    }

    private String rewrite(String manifest, URL base) throws IOException {
        if (manifest.startsWith("\uFEFF")) manifest = manifest.substring(1);
        StringBuilder result = new StringBuilder();
        for (String text : manifest.split("\r?\n", -1)) {
            String trimmed = text.trim();
            if (trimmed.startsWith("#")) {
                Matcher matcher = URI_ATTRIBUTE.matcher(text);
                StringBuffer replaced = new StringBuffer();
                while (matcher.find()) {
                    String ref = matcher.group(1);
                    String value = ref.startsWith("data:") || ref.startsWith("skd:") ? ref : route(new URL(base, ref));
                    matcher.appendReplacement(replaced, Matcher.quoteReplacement("URI=\"" + value + "\""));
                }
                matcher.appendTail(replaced);
                result.append(replaced);
            } else if (trimmed.length() > 0) result.append(route(new URL(base, trimmed)));
            result.append('\n');
        }
        return result.toString();
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        for (int n; (n = in.read(block)) != -1;) {
            if (buffer.size() + n > limit) throw new IOException("Playlist size limit");
            buffer.write(block, 0, n);
        }
        return buffer.toByteArray();
    }

    private static int effectivePort(URL url) { return url.getPort() == -1 ? url.getDefaultPort() : url.getPort(); }
    private static long contentLength(HttpURLConnection connection) {
        try { return Long.parseLong(connection.getHeaderField("Content-Length")); }
        catch (NumberFormatException ignored) { return -1; }
    }
    private static String safeType(String type) { return type == null ? "application/octet-stream" : type.replaceAll("[^A-Za-z0-9/;=.+_ -]", ""); }
    private static String guessType(URL url) {
        String path = url.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".ts")) return "video/mp2t";
        if (path.endsWith(".mkv")) return "video/x-matroska";
        if (path.endsWith(".m4s")) return "video/iso.segment";
        if (path.endsWith(".aac")) return "audio/aac";
        if (path.endsWith(".vtt")) return "text/vtt";
        return "application/octet-stream";
    }
    private static void head(OutputStream out, int status, String type, long length, HttpURLConnection upstream, boolean transformed) throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 " + status + " Relay\r\nConnection: close\r\nContent-Type: " + safeType(type) + "\r\nCache-Control: no-store\r\n");
        if (length >= 0) h.append("Content-Length: ").append(length).append("\r\n");
        if (upstream != null && !transformed) for (String key : new String[]{"Content-Range", "Accept-Ranges", "ETag", "Last-Modified"}) {
            String value = upstream.getHeaderField(key);
            if (value != null && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) h.append(key).append(": ").append(value).append("\r\n");
        }
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
    }
    private static void send(OutputStream out, int status, String type, byte[] body, boolean headOnly) throws IOException {
        head(out, status, type, body.length, null, true);
        if (!headOnly) out.write(body);
        out.flush();
    }

    public long idleMillis() { return System.currentTimeMillis() - lastActivity; }
    public boolean needsAttention() { return failed || requests.get() == 0 || bytes.get() == 0; }
    public String diagnostic() {
        return "TVBox SKYBOX 修复版 2\n来源：" + original.getProtocol() + "://" + original.getHost()
                + "\n携带请求头名称：" + headers.keySet()
                + "\nSKYBOX 请求数：" + requests.get() + "\n已发送字节：" + bytes.get()
                + "\n状态：" + last + "\n本机转发：" + (closed ? "已结束" : "运行中")
                + "\n（诊断不包含视频地址参数或 Cookie 值）";
    }
    public void close() throws IOException {
        closed = true; server.close();
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
        for (HttpURLConnection c : upstreams) c.disconnect();
        workers.shutdownNow();
    }
}
