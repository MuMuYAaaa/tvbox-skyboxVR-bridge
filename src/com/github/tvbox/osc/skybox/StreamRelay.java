package com.github.tvbox.osc.skybox;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AtomicLong recoveries = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    interface Clock { long nanoTime(); }
    private final Clock clock;
    private final long started;
    private final URL original;
    private volatile boolean closed;
    private volatile String last = "等待 SKYBOX 请求";
    private volatile boolean failed;
    private volatile boolean successfulResponse;
    private volatile int lastHttpStatus;
    private volatile String lastProblem = "无";
    private volatile String closeReason = "尚未结束";
    private volatile long lastActivity;
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");

    public StreamRelay(String url, Map<String, String> requestHeaders) throws IOException {
        this(url, requestHeaders, new Clock() { public long nanoTime() { return System.nanoTime(); } });
    }

    StreamRelay(String url, Map<String, String> requestHeaders, Clock clock) throws IOException {
        this.clock = clock;
        this.started = clock.nanoTime();
        this.lastActivity = started;
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
        boolean counted = false;
        try {
            Socket client = socket;
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
            active.incrementAndGet(); counted = true;
            lastActivity = clock.nanoTime();
            connection = connect(new URL(remote), method, incoming);
            int status = connection.getResponseCode();
            lastHttpStatus = status;
            String type = connection.getContentType();
            last = "上游 HTTP " + status + "；类型 " + safeType(type);
            if (status >= 400) {
                failed = true;
                lastProblem = "上游 HTTP " + status + ((status == 401 || status == 403) ? "，地址或授权可能已过期" : "");
            }
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
                successfulResponse = true;
                lastActivity = clock.nanoTime();
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
                pump(stream, out, connection, incoming, status >= 200 && status < 300, gzip);
            }
            out.flush();
            stream.close();
        } catch (IOException | RuntimeException e) {
            // Client cancellations are normal during probing and seeking.
            if (!(e instanceof SocketException) && !(e instanceof EOFException) && !closed) {
                failed = true;
                last = (sent ? "传输中断：" : "取流失败：") + e.getClass().getSimpleName();
                lastProblem = last;
            }
            if (!sent && !closed) {
                try { send(socket.getOutputStream(), 502, "text/plain", "Upstream unavailable; return to TVBox for diagnostics".getBytes(StandardCharsets.UTF_8), false); }
                catch (IOException ignored) {}
            }
        } finally {
            if (connection != null) { upstreams.remove(connection); connection.disconnect(); }
            if (counted) active.decrementAndGet();
            sockets.remove(socket);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private HttpURLConnection connect(URL target, String method, Map<String, String> incoming) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 3 && !closed; attempt++) {
            try {
                HttpURLConnection connection = connectOnce(target, method, incoming);
                int status = connection.getResponseCode();
                lastHttpStatus = status;
                if ((status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504) && attempt < 2) {
                    lastProblem = "上游 HTTP " + status + "，正在重试";
                    upstreams.remove(connection); connection.disconnect();
                } else {
                    if (attempt > 0) recoveries.incrementAndGet();
                    return connection;
                }
            } catch (IOException error) {
                lastFailure = error;
                lastProblem = "连接异常：" + error.getClass().getSimpleName();
                if (error instanceof javax.net.ssl.SSLHandshakeException) throw error;
            }
            pauseRetry(attempt);
        }
        throw lastFailure == null ? new IOException("Relay closed or retries exhausted") : lastFailure;
    }

    private void pauseRetry(int attempt) throws IOException {
        if (closed) throw new IOException("Relay closed");
        try { Thread.sleep(250L << attempt); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Retry interrupted", error); }
    }

    private HttpURLConnection connectOnce(URL target, String method, Map<String, String> incoming) throws IOException {
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
                // A rotated cookie replaces the original value rather than creating duplicates.
                Map<String, String> merged = new LinkedHashMap<>();
                String initialCookie = c.getRequestProperty("Cookie");
                addCookies(merged, initialCookie);
                for (Map.Entry<String, List<String>> h : saved.entrySet()) {
                    if (h.getKey().equalsIgnoreCase("Cookie")) {
                        for (String value : h.getValue()) addCookies(merged, value);
                    } else for (String value : h.getValue()) c.addRequestProperty(h.getKey(), value);
                }
                if (!merged.isEmpty()) {
                    StringBuilder cookie = new StringBuilder();
                    for (Map.Entry<String, String> h : merged.entrySet()) {
                        if (cookie.length() > 0) cookie.append("; ");
                        cookie.append(h.getKey()).append('=').append(h.getValue());
                    }
                    c.setRequestProperty("Cookie", cookie.toString());
                }
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

    private static void addCookies(Map<String, String> into, String value) {
        if (value == null) return;
        for (String part : value.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0 && !part.trim().startsWith("$")) into.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
        }
    }

    /** Resume only a validated representation and exact byte range; never append a full 200 response. */
    private void pump(InputStream first, OutputStream out, HttpURLConnection initial,
                      Map<String, String> incoming, boolean media, boolean gzip) throws IOException {
        HttpURLConnection current = initial;
        InputStream in = first;
        long expected = gzip ? -1 : contentLength(initial);
        long offset = 0;
        if (initial.getResponseCode() == 206) offset = rangeStart(initial.getHeaderField("Content-Range"));
        String etag = initial.getHeaderField("ETag");
        boolean strongEtag = etag != null && !etag.startsWith("W/");
        String validator = strongEtag ? etag : initial.getHeaderField("Last-Modified");
        long total = rangeTotal(initial.getHeaderField("Content-Range"));
        if (total < 0 && initial.getResponseCode() == 200) total = expected;
        long delivered = 0;
        int retries = 0;
        byte[] buffer = new byte[64 * 1024];
        try {
            while (!closed) {
                int n;
                try {
                    n = in.read(buffer);
                    if (n < 0 && expected >= 0 && delivered < expected) throw new EOFException("Short upstream body");
                } catch (IOException error) {
                    lastProblem = "上游流中断：" + error.getClass().getSimpleName();
                    if (!media || gzip || validator == null || expected < 0 || offset < 0 || delivered >= expected || retries++ >= 3) throw new IOException("Unrecoverable upstream interruption", error);
                    upstreams.remove(current); current.disconnect();
                    Map<String, String> resume = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    resume.putAll(incoming);
                    long next = offset + delivered;
                    resume.put("Range", "bytes=" + next + "-" + (offset + expected - 1));
                    resume.put("If-Range", validator);
                    pauseRetry(Math.min(retries - 1, 2));
                    current = connect(initial.getURL(), "GET", resume);
                    int status = current.getResponseCode();
                    lastHttpStatus = status;
                    boolean same = status == 206 && rangeStart(current.getHeaderField("Content-Range")) == next
                            && rangeTotal(current.getHeaderField("Content-Range")) == total
                            && contentLength(current) == expected - delivered
                            && (strongEtag ? validator.equals(current.getHeaderField("ETag")) : validator.equals(current.getHeaderField("Last-Modified")));
                    if (!same) {
                        lastProblem = "续传校验失败，HTTP " + status + "；未拼接不一致数据";
                        throw new IOException("Resume representation or byte range changed");
                    }
                    in = new BufferedInputStream(current.getInputStream());
                    recoveries.incrementAndGet();
                    last = "网络中断后已按字节位置恢复";
                    continue;
                }
                if (n < 0) break;
                if (expected >= 0 && n > expected - delivered) throw new IOException("Upstream exceeded declared length");
                // Client writes are deliberately outside the retry block: seeking cancels old sockets.
                out.write(buffer, 0, n); out.flush();
                delivered += n;
                bytes.addAndGet(n);
                if (media) successfulResponse = true;
                lastActivity = clock.nanoTime();
                if (expected >= 0 && delivered == expected) break;
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            if (current != initial) { upstreams.remove(current); current.disconnect(); }
        }
    }

    private static long rangeStart(String value) {
        if (value == null) return -1;
        try { return Long.parseLong(value.substring(value.indexOf(' ') + 1, value.indexOf('-'))); }
        catch (RuntimeException ignored) { return -1; }
    }
    private static long rangeTotal(String value) {
        if (value == null) return -1;
        try { return Long.parseLong(value.substring(value.indexOf('/') + 1)); }
        catch (RuntimeException ignored) { return -1; }
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

    public long idleMillis() { return TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - lastActivity); }
    public boolean shouldStopWhenUnattended() { return !successfulResponse && active.get() == 0 && idleMillis() > 120000; }
    public boolean isClosed() { return closed; }
    public boolean needsAttention() { return failed || requests.get() == 0 || bytes.get() == 0; }
    public String diagnostic() {
        return "TVBox SKYBOX 修复版 3\n来源：" + original.getProtocol() + "://" + original.getHost()
                + "\n携带请求头名称：" + headers.keySet()
                + "\nSKYBOX 请求数：" + requests.get() + "\n已发送字节：" + bytes.get()
                + "\n会话秒数：" + TimeUnit.NANOSECONDS.toSeconds(clock.nanoTime() - started)
                + "\n空闲秒数：" + idleMillis()/1000 + "；活动请求：" + active.get()
                + "\n恢复次数：" + recoveries.get() + "；最后 HTTP：" + lastHttpStatus
                + "\n最近异常：" + lastProblem
                + "\n状态：" + last + "\n本机转发：" + (closed ? "已结束：" + closeReason : "运行中；已开始播放的会话不会因缓存空闲自动关闭")
                + "\n（诊断不包含视频地址参数或 Cookie 值）";
    }
    public void close() throws IOException {
        close("主动结束或切换影片");
    }
    public synchronized void close(String reason) throws IOException {
        if (closed) return;
        closeReason = reason;
        closed = true; server.close();
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
        for (HttpURLConnection c : upstreams) c.disconnect();
        workers.shutdownNow();
    }
}
