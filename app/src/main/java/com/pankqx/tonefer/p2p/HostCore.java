package com.pankqx.tonefer.p2p;

import com.pankqx.tonefer.crypto.SecretBox;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Phone to phone: this phone acts like the laptop does. It serves the Tonefer phone page to
 * another phone (any browser, iPhone included) and speaks the same encrypted protocol
 * (tonefer/docs/PROTOCOL.md): WebSocket for live notes, chunked uploads, framed downloads,
 * folders as zip. Plain Java with no Android imports, so it can be run and tested anywhere.
 *
 * One session = one random 32-byte key, handed over in the QR code's #fragment.
 */
public final class HostCore {

    // ---------------------------------------------------------------- types
    public interface Listener {
        void onItem(Item item);
        void onDeleted(String id);
        void onGuests(int connected);
        /** A transfer moved. total may be 0 when unknown. */
        void onProgress(String id, String label, String name, long done, long total, double bytesPerSecond);
        void onProgressDone(String id);
    }

    public interface Assets {
        byte[] read(String path) throws IOException;
    }

    /** Where the bytes of a file the host offers come from (a file, or a picked photo read in place). */
    public interface Source {
        InputStream open() throws IOException;
        /** False once the file is gone (deleted, permission revoked). */
        default boolean available() { return true; }
    }

    private static Source fileSource(File f) {
        return new Source() {
            public InputStream open() throws IOException { return new FileInputStream(f); }
            public boolean available() { return f.exists(); }
        };
    }

    public static final class Item {
        public final String id, kind, origin, text, name, mime;
        public final long ts, size;
        public final int files;
        /** Received files: where they were saved. Null for notes and for files this phone offers. */
        public final File file;
        final Source source;

        Item(String id, String kind, String origin, String text, String name, String mime, long size, int files, File file) {
            this(id, kind, origin, text, name, mime, size, files, file, file == null ? null : fileSource(file));
        }

        Item(String id, String kind, String origin, String text, String name, String mime, long size, int files, File file, Source source) {
            this.id = id; this.kind = kind; this.origin = origin; this.text = text; this.name = name; this.mime = mime;
            this.size = size; this.files = files; this.file = file; this.source = source; this.ts = System.currentTimeMillis();
        }

        public boolean isText() { return "text".equals(kind); }
        public boolean fromGuest() { return "phone".equals(origin); }

        String json() {
            StringBuilder b = new StringBuilder("{");
            b.append("\"id\":").append(Json.quote(id)).append(",\"kind\":").append(Json.quote(kind))
                .append(",\"origin\":").append(Json.quote(origin)).append(",\"ts\":").append(ts);
            if (text != null) b.append(",\"text\":").append(Json.quote(text));
            if (name != null) b.append(",\"name\":").append(Json.quote(name)).append(",\"size\":").append(size);
            if (mime != null) b.append(",\"mime\":").append(Json.quote(mime));
            if (files > 0) b.append(",\"files\":").append(files);
            return b.append('}').toString();
        }
    }

    // ---------------------------------------------------------------- constants
    static final int CHUNK = 4 * 1024 * 1024;     // upload chunk (plaintext)
    static final int DCHUNK = 1024 * 1024;        // download frame (plaintext)
    static final int HDR = 21;                    // tid(16) | index(4) | flags(1)
    static final int FRAME_OVERHEAD = 4 + SecretBox.OVERHEAD + HDR;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Set<String> STORED = new HashSet<>(java.util.Arrays.asList(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "mp4", "mov", "m4v", "mkv", "webm", "3gp",
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "zip", "7z", "rar", "gz", "apk", "docx", "xlsx", "pptx", "pdf"));

    // ---------------------------------------------------------------- state
    private final File dir;
    private final Assets assets;
    private final Listener listener;
    private final String hostName;
    private final SecureRandom random = new SecureRandom();
    private volatile byte[] key;
    private volatile String token;

    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;
    private final Set<Ws> sockets = ConcurrentHashMap.newKeySet();
    private final List<Item> items = new CopyOnWriteArrayList<>();
    private final Map<String, Incoming> uploads = new ConcurrentHashMap<>();
    private final Map<String, ZipBuild> zips = new ConcurrentHashMap<>();
    private final Map<String, byte[]> assetCache = new ConcurrentHashMap<>();

    public HostCore(File dir, Assets assets, Listener listener, String hostName) {
        this.dir = dir;
        this.assets = assets;
        this.listener = listener;
        this.hostName = hostName;
        newKey();
    }

    // ---------------------------------------------------------------- public API
    /** Starts listening on the first free port from {@code port}. Returns the port used. */
    public synchronized int start(int port) throws IOException {
        if (running) return server.getLocalPort();
        IOException last = null;
        for (int p = port; p < port + 10; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p), 64);
                server = s;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) throw last != null ? last : new IOException("no free port");
        new File(dir, "received").mkdirs();
        new File(dir, "partial").mkdirs();
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tonefer-host");
            t.setDaemon(true);
            return t;
        });
        running = true;
        pool.execute(this::acceptLoop);
        pool.execute(this::pingLoop);
        return server.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        for (Ws w : sockets) w.close(1001, "session ended");
        sockets.clear();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
        if (pool != null) pool.shutdownNow();
        for (ZipBuild z : zips.values()) z.abort();
        for (Incoming i : uploads.values()) i.abort();
        zips.clear();
        uploads.clear();
        listener.onGuests(0);
    }

    public boolean isRunning() { return running; }
    public int port() { ServerSocket s = server; return s == null ? 0 : s.getLocalPort(); }

    /** The key in the form the QR link carries (#k=...). */
    public String keyB64Url() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    /** A fresh key: everyone connected is cut off and must scan the new code. */
    public synchronized void newKey() {
        byte[] k = new byte[SecretBox.KEY_BYTES];
        random.nextBytes(k);
        key = k;
        token = tokenFor(k);
        for (Ws w : sockets) w.close(4001, "new code");
        sockets.clear();
        if (listener != null) listener.onGuests(0);
    }

    public List<Item> items() { return Collections.unmodifiableList(items); }
    public int guests() { return sockets.size(); }

    public Item sendText(String text) {
        Item it = new Item(newId(), "text", "laptop", text, null, null, 0, 0, null);
        publish(it);
        return it;
    }

    /** Offers a file to the other phone. */
    public Item sendFile(File file, String name, String mime) {
        return sendSource(name, mime, file.length(), 0, fileSource(file));
    }

    /** Offers bytes read straight from their source (no copy). [size] must be exact. */
    public Item sendSource(String name, String mime, long size, int files, Source source) {
        Item it = new Item(newId(), "file", "laptop", null, safeName(name), mime == null || mime.isEmpty() ? mimeOf(name) : mime, size, files, null, source);
        publish(it);
        return it;
    }

    /** A folder this session may write into (for files the host is about to send). */
    public File outbox() {
        File f = new File(dir, "sent");
        f.mkdirs();
        return f;
    }

    public void delete(String id) {
        for (Item it : items) {
            if (it.id.equals(id)) {
                items.remove(it);
                broadcast("{\"type\":\"deleted\",\"id\":" + Json.quote(id) + "}");
                listener.onDeleted(id);
                return;
            }
        }
    }

    public static String tokenFor(byte[] key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            md.update(key);
            md.update("tonefer-auth".getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.substring(0, 64);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- server loop
    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                s.setSoTimeout(120_000);
                pool.execute(() -> serve(s));
            } catch (IOException e) {
                if (!running) return;
            } catch (Exception e) {
                return; // pool shut down
            }
        }
    }

    private void pingLoop() {
        while (running) {
            try { Thread.sleep(25_000); } catch (InterruptedException e) { return; }
            for (Ws w : sockets) w.ping();
        }
    }

    private static final class Request {
        String method, path, query = "";
        final Map<String, String> headers = new HashMap<>();
        final Map<String, String> params = new HashMap<>();
        long length;

        String header(String n) { return headers.get(n); }
        String param(String n) { return params.get(n); }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            while (running) {
                Request r = readHead(in);
                if (r == null) return;
                boolean keep = !"close".equalsIgnoreCase(r.header("connection"));
                if ("websocket".equalsIgnoreCase(r.header("upgrade")) && r.path.equals("/ws")) {
                    webSocket(r, s, in, out);
                    return;
                }
                try {
                    keep &= route(r, in, out);
                } catch (Fail f) {
                    drain(in, r);
                    respond(out, f.status, "text/plain; charset=utf-8", f.getMessage().getBytes(StandardCharsets.UTF_8), null);
                }
                out.flush();
                if (!keep) return;
            }
        } catch (IOException ignored) {
            // the other phone went away
        }
    }

    private static Request readHead(InputStream in) throws IOException {
        String line = readLine(in);
        while (line != null && line.isEmpty()) line = readLine(in);
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) throw new IOException("bad request");
        Request r = new Request();
        r.method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) {
            r.query = target.substring(q + 1);
            for (String kv : r.query.split("&")) {
                int e = kv.indexOf('=');
                if (e > 0) r.params.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
            }
        }
        for (String h = readLine(in); h != null && !h.isEmpty(); h = readLine(in)) {
            int c = h.indexOf(':');
            if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.US), h.substring(c + 1).trim());
        }
        String cl = r.header("content-length");
        r.length = cl == null ? 0 : Long.parseLong(cl.trim());
        return r;
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16 * 1024) throw new IOException("header too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static byte[] readBody(InputStream in, Request r, long limit) throws IOException {
        if (r.length > limit) throw new Fail(413, "Too big");
        byte[] b = new byte[(int) r.length];
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
        return b;
    }

    private static void drain(InputStream in, Request r) throws IOException {
        long left = r.length;
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) { if (in.read() < 0) return; n = 1; }
            left -= n;
        }
        r.length = 0;
    }

    private static final class Fail extends IOException {
        final int status;
        Fail(int status, String message) { super(message); this.status = status; }
    }

    private static void respond(OutputStream out, int status, String type, byte[] body, Map<String, String> extra) throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\nContent-Length: ").append(body.length)
            .append("\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
    }

    private static void json(OutputStream out, int status, String json) throws IOException {
        respond(out, status, "application/json", json.getBytes(StandardCharsets.UTF_8), null);
    }

    private static String reason(int s) {
        switch (s) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 410: return "Gone";
            case 413: return "Payload Too Large";
            default: return "Error";
        }
    }

    // ---------------------------------------------------------------- routes
    /** Returns false when the connection must close after this response. */
    private boolean route(Request r, InputStream in, OutputStream out) throws IOException {
        String p = r.path;
        if (r.method.equals("GET") && (p.equals("/") || p.equals("/index.html"))) {
            respond(out, 200, "text/html; charset=utf-8", asset("phone/index.html"), null);
            return true;
        }
        if (r.method.equals("GET") && p.startsWith("/static/")) {
            String rel = p.substring("/static/".length());
            if (rel.contains("..") || rel.isEmpty()) throw new Fail(404, "Not found");
            byte[] b;
            try { b = asset("static/" + rel); } catch (IOException e) { throw new Fail(404, "Not found"); }
            respond(out, 200, mimeOf(rel), b, null);
            return true;
        }
        if (!p.startsWith("/api/")) throw new Fail(404, "Not found");
        authorize(r);

        if (p.equals("/api/ping")) {
            json(out, 200, "{\"ok\":true,\"peer\":\"phone\"}");
            return true;
        }
        if (p.equals("/api/speedtest")) {
            if (r.method.equals("POST")) {
                drain(in, r);
                json(out, 200, "{\"ok\":true}");
            } else {
                int n = (int) Math.min(64L << 20, Math.max(0, parseLong(r.param("n"), 0)));
                respond(out, 200, "application/octet-stream", new byte[n], null);
            }
            return true;
        }
        if (p.equals("/api/upload/start") && r.method.equals("POST")) return uploadStart(r, in, out);
        if (p.equals("/api/zip/start") && r.method.equals("POST")) return zipStart(r, in, out);
        String[] seg = p.substring(1).split("/");
        if (seg.length >= 3 && seg[1].equals("upload")) {
            String tid = seg[2];
            if (seg.length == 4 && r.method.equals("POST")) return uploadChunk(r, in, out, tid, (int) parseLong(seg[3], -1));
            if (seg.length == 3 && r.method.equals("GET")) {
                Incoming inc = uploads.get(tid);
                if (inc == null) throw new Fail(404, "Unknown transfer");
                json(out, 200, "{\"next\":" + inc.next + ",\"done\":" + inc.done + ",\"bytes\":" + inc.bytes + "}");
                return true;
            }
            if (seg.length == 3 && r.method.equals("DELETE")) {
                Incoming inc = uploads.remove(tid);
                if (inc != null && inc.zip == null && !inc.done) { inc.abort(); listener.onProgressDone(tid); }
                json(out, 200, "{\"ok\":true}");
                return true;
            }
        }
        if (seg.length >= 3 && seg[1].equals("zip")) {
            String zid = seg[2];
            if (seg.length == 4 && seg[3].equals("finish") && r.method.equals("POST")) return zipFinish(r, in, out, zid);
            if (seg.length == 3 && r.method.equals("DELETE")) {
                ZipBuild z = zips.remove(zid);
                if (z != null) { z.abort(); listener.onProgressDone(zid); }
                json(out, 200, "{\"ok\":true}");
                return true;
            }
        }
        if (seg.length == 3 && seg[1].equals("file") && r.method.equals("GET")) return download(r, out, seg[2]);
        throw new Fail(404, "Not found");
    }

    private void authorize(Request r) throws Fail {
        String t = r.header("x-tonefer-token");
        if (t == null) t = r.param("t");
        if (t == null || !MessageDigest.isEqual(t.getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII)))
            throw new Fail(401, "Not paired. Scan the QR code on the other phone.");
    }

    private Map<String, Object> meta(InputStream in, Request r) throws IOException {
        byte[] body = readBody(in, r, 64 * 1024);
        try {
            byte[] plain = SecretBox.open(key, Base64.getDecoder().decode(new String(body, StandardCharsets.US_ASCII).trim()));
            Object o = Json.parse(new String(plain, StandardCharsets.UTF_8));
            if (o instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                return m;
            }
        } catch (Exception ignored) { }
        throw new Fail(403, "Payload did not decrypt. Scan the code again.");
    }

    // ---------------------------------------------------------------- uploads
    private final class Incoming {
        final String id, name, mime;
        final long size;
        final File part;
        final ZipBuild zip;
        final OutputStream out;
        final long started = System.nanoTime();
        int next;
        long bytes;
        boolean done;

        Incoming(String id, String name, String mime, long size, File part, ZipBuild zip) throws IOException {
            this.id = id; this.name = name; this.mime = mime; this.size = size; this.part = part; this.zip = zip;
            this.out = part == null ? null : new BufferedOutputStream(new FileOutputStream(part), 256 * 1024);
        }

        void write(byte[] b, int off, int len) throws IOException {
            if (zip != null) zip.write(b, off, len); else out.write(b, off, len);
            bytes += len;
        }

        void close() throws IOException {
            if (zip != null) zip.closeEntry(); else out.close();
        }

        void abort() {
            try { if (out != null) out.close(); } catch (IOException ignored) { }
            if (part != null) part.delete();
        }
    }

    private boolean uploadStart(Request r, InputStream in, OutputStream out) throws IOException {
        Map<String, Object> m = meta(in, r);
        String name = safeName(Json.str(m, "name", "file"));
        long size = Json.num(m, "size", -1);
        String mime = Json.str(m, "mime", "");
        if (mime.isEmpty()) mime = mimeOf(name);
        String tid = newId();
        String zid = Json.str(m, "zip", "");
        Incoming inc;
        if (!zid.isEmpty()) {
            ZipBuild z = zips.get(zid);
            if (z == null) throw new Fail(404, "That zip is no longer open. Send the folder again.");
            synchronized (z) {
                Incoming busy = z.owner == null ? null : uploads.get(z.owner);
                if (busy != null && !busy.done) {
                    json(out, 409, "{\"ok\":false,\"busy\":" + Json.quote(busy.id) + ",\"next\":" + busy.next + "}");
                    return true;
                }
                z.openEntry(Json.str(m, "path", name), Json.num(m, "mtime", 0));
                z.owner = tid;
            }
            inc = new Incoming(tid, name, mime, size, null, z);
        } else {
            inc = new Incoming(tid, name, mime, size, new File(new File(dir, "partial"), tid + ".part"), null);
        }
        uploads.put(tid, inc);
        json(out, 200, "{\"id\":" + Json.quote(tid) + ",\"chunk\":" + CHUNK + ",\"next\":0}");
        return true;
    }

    private boolean uploadChunk(Request r, InputStream in, OutputStream out, String tid, int index) throws IOException {
        Incoming inc = uploads.get(tid);
        if (inc == null) throw new Fail(404, "Unknown transfer. Send the file again.");
        byte[] body = readBody(in, r, CHUNK + 4096);
        synchronized (inc) {
            if (index < inc.next) { json(out, 200, "{\"ok\":true,\"next\":" + inc.next + ",\"done\":" + inc.done + "}"); return true; }
            if (index > inc.next || inc.done) { json(out, 409, "{\"ok\":false,\"next\":" + inc.next + ",\"done\":" + inc.done + "}"); return true; }
            byte[] plain;
            try { plain = SecretBox.open(key, body); } catch (Exception e) { throw new Fail(403, "Payload did not decrypt. Scan the code again."); }
            if (plain.length < HDR) throw new Fail(400, "Chunk too short");
            String ctid = new String(plain, 0, 16, StandardCharsets.US_ASCII);
            int cindex = ByteBuffer.wrap(plain, 16, 4).getInt();
            boolean last = (plain[20] & 1) == 1;
            if (!ctid.equals(tid) || cindex != index) throw new Fail(400, "That chunk belongs to another transfer.");
            inc.write(plain, HDR, plain.length - HDR);
            inc.next++;
            ZipBuild z = inc.zip;
            if (z != null) z.bytes += plain.length - HDR;
            progress(z != null ? z.id : inc.id, z != null ? z.name : inc.name, z != null ? z.bytes : inc.bytes,
                z != null ? z.expected : inc.size, z != null ? z.started : inc.started, "Receiving");
            String extra = "";
            if (last) {
                inc.close();
                inc.done = true;
                if (z == null) {
                    File dest = unique(new File(dir, "received"), inc.name);
                    if (!inc.part.renameTo(dest)) throw new Fail(500, "Couldn't save the file");
                    Item it = new Item(newId(), "file", "phone", null, dest.getName(), inc.mime, dest.length(), 0, dest);
                    listener.onProgressDone(inc.id);
                    publish(it);
                    uploads.remove(tid);
                    extra = ",\"item\":" + Json.quote(it.id);
                }
            }
            json(out, 200, "{\"ok\":true,\"next\":" + inc.next + ",\"done\":" + inc.done + extra + "}");
        }
        return true;
    }

    // ---------------------------------------------------------------- zips
    private final class ZipBuild {
        final String id, name;
        final File part;
        final ZipOutputStream zos;
        final long expected;
        final int expectedFiles;
        final long started = System.nanoTime();
        final Set<String> used = new HashSet<>();
        String owner, item;
        boolean entryOpen;
        int count;
        long bytes;

        ZipBuild(String id, String name, long expected, int expectedFiles) throws IOException {
            this.id = id;
            this.name = name.toLowerCase(Locale.US).endsWith(".zip") ? name : name + ".zip";
            this.expected = expected;
            this.expectedFiles = expectedFiles;
            this.part = new File(new File(dir, "partial"), id + ".zip.part");
            this.zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(part), 256 * 1024));
        }

        void openEntry(String path, long mtime) throws IOException {
            closeEntry();
            String arc = cleanPath(path);
            String base = arc;
            for (int n = 1; used.contains(arc); n++) {
                int dot = base.lastIndexOf('.');
                arc = dot > base.lastIndexOf('/') + 0 && dot > 0 ? base.substring(0, dot) + " (" + n + ")" + base.substring(dot) : base + " (" + n + ")";
            }
            used.add(arc);
            ZipEntry e = new ZipEntry(arc);
            e.setTime(mtime > 0 ? mtime : System.currentTimeMillis());
            String ext = arc.contains(".") ? arc.substring(arc.lastIndexOf('.') + 1).toLowerCase(Locale.US) : "";
            zos.setLevel(STORED.contains(ext) ? 0 : 1);   // photos and videos don't shrink; skip the work
            zos.putNextEntry(e);
            entryOpen = true;
        }

        void write(byte[] b, int off, int len) throws IOException { zos.write(b, off, len); }

        void closeEntry() throws IOException {
            if (entryOpen) {
                zos.closeEntry();
                entryOpen = false;
                count++;
            }
            owner = null;
        }

        void abort() {
            try { zos.close(); } catch (IOException ignored) { }
            part.delete();
        }
    }

    private boolean zipStart(Request r, InputStream in, OutputStream out) throws IOException {
        Map<String, Object> m = meta(in, r);
        String zid = newId();
        ZipBuild z = new ZipBuild(zid, safeName(Json.str(m, "name", "Tonefer")), Json.num(m, "size", 0), (int) Json.num(m, "files", 0));
        zips.put(zid, z);
        json(out, 200, "{\"zip\":" + Json.quote(zid) + ",\"name\":" + Json.quote(z.name) + "}");
        return true;
    }

    private boolean zipFinish(Request r, InputStream in, OutputStream out, String zid) throws IOException {
        meta(in, r);   // proves the sender has the key
        ZipBuild z = zips.get(zid);
        if (z == null) throw new Fail(404, "That zip is no longer open. Send the folder again.");
        synchronized (z) {
            if (z.item != null) { json(out, 200, "{\"ok\":true,\"item\":" + Json.quote(z.item) + "}"); return true; }
            Incoming busy = z.owner == null ? null : uploads.get(z.owner);
            if (busy != null && !busy.done) { json(out, 409, "{\"ok\":false,\"busy\":" + Json.quote(busy.id) + ",\"next\":" + busy.next + "}"); return true; }
            z.closeEntry();
            z.zos.close();
            File dest = unique(new File(dir, "received"), z.name);
            if (!z.part.renameTo(dest)) throw new Fail(500, "Couldn't save the zip");
            Item it = new Item(newId(), "file", "phone", null, dest.getName(), "application/zip", dest.length(), z.count, dest);
            z.item = it.id;
            uploads.values().removeIf(i -> i.zip == z);
            listener.onProgressDone(zid);
            publish(it);
            json(out, 200, "{\"ok\":true,\"item\":" + Json.quote(it.id) + "}");
        }
        return true;
    }

    // ---------------------------------------------------------------- downloads
    private boolean download(Request r, OutputStream out, String id) throws IOException {
        Item it = null;
        for (Item x : items) if (x.id.equals(id)) it = x;
        if (it == null || it.source == null) throw new Fail(404, "Not found");
        if (!it.source.available()) throw new Fail(410, "The file was removed on the other phone.");
        long size = it.size;
        if (!"2".equals(r.param("v"))) {
            if (size > 256L << 20) throw new Fail(413, "Too big for this browser. Reload the page.");
            byte[] all = new byte[(int) size];
            try (InputStream f = it.source.open()) { int o = 0; while (o < all.length) { int n = f.read(all, o, all.length - o); if (n < 0) break; o += n; } }
            respond(out, 200, "application/octet-stream", SecretBox.seal(key, all), null);
            return true;
        }
        int frames = (int) Math.max(1, (size + DCHUNK - 1) / DCHUNK);
        int start = (int) Math.max(0, Math.min(frames, parseLong(r.param("from"), 0)));
        long length = Math.max(0, size - (long) start * DCHUNK) + (long) (frames - start) * FRAME_OVERHEAD;
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\n"
            + "X-Tonefer-Format: 2\r\nX-Tonefer-Size: " + size + "\r\nX-Tonefer-Frames: " + frames
            + "\r\nX-Tonefer-Chunk: " + DCHUNK + "\r\nContent-Length: " + length + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        long t0 = System.nanoTime();
        byte[] buf = new byte[DCHUNK];
        byte[] hdr = new byte[HDR];
        byte[] tid = it.id.getBytes(StandardCharsets.US_ASCII);
        long sent = 0;
        try (InputStream f = it.source.open()) {
            long skip = (long) start * DCHUNK;
            while (skip > 0) {   // some content streams can't skip: read past instead
                long n = f.skip(skip);
                if (n <= 0) n = f.read(buf, 0, (int) Math.min(buf.length, skip));
                if (n < 0) throw new EOFException();
                skip -= n;
            }
            for (int i = start; i < frames; i++) {
                int n = 0;
                while (n < DCHUNK) { int k = f.read(buf, n, DCHUNK - n); if (k < 0) break; n += k; }
                System.arraycopy(tid, 0, hdr, 0, Math.min(16, tid.length));
                ByteBuffer.wrap(hdr, 16, 4).putInt(i);
                hdr[20] = (byte) (i == frames - 1 ? 1 : 0);
                byte[] plain = new byte[HDR + n];
                System.arraycopy(hdr, 0, plain, 0, HDR);
                System.arraycopy(buf, 0, plain, HDR, n);
                byte[] sealed = SecretBox.seal(key, plain);
                out.write(ByteBuffer.allocate(4).putInt(sealed.length).array());
                out.write(sealed);
                sent += n;
                if (size > (4L << 20)) progress("out-" + it.id, it.name, sent, size, t0, "Sending");
            }
        } finally {
            if (size > (4L << 20)) listener.onProgressDone("out-" + it.id);
        }
        return true;
    }

    // ---------------------------------------------------------------- websocket
    private final class Ws {
        final Socket socket;
        final OutputStream out;

        Ws(Socket socket, OutputStream out) { this.socket = socket; this.out = out; }

        synchronized void send(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream h = new ByteArrayOutputStream(10);
            h.write(0x80 | opcode);
            int n = payload.length;
            if (n < 126) h.write(n);
            else if (n < 65536) { h.write(126); h.write(n >>> 8); h.write(n & 0xff); }
            else { h.write(127); h.write(ByteBuffer.allocate(8).putLong(n).array(), 0, 8); }
            out.write(h.toByteArray());
            out.write(payload);
            out.flush();
        }

        void text(String s) {
            try { send(0x1, s.getBytes(StandardCharsets.UTF_8)); } catch (IOException e) { sockets.remove(this); }
        }

        void ping() {
            try { send(0x9, new byte[0]); } catch (IOException e) { sockets.remove(this); close(1001, ""); }
        }

        void close(int code, String why) {
            byte[] w = why.getBytes(StandardCharsets.UTF_8);
            ByteBuffer b = ByteBuffer.allocate(2 + w.length).putShort((short) code).put(w);
            try { send(0x8, b.array()); } catch (IOException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void webSocket(Request r, Socket s, InputStream in, OutputStream out) throws IOException {
        String wsKey = r.header("sec-websocket-key");
        if (wsKey == null) { respond(out, 400, "text/plain", new byte[0], null); out.flush(); return; }
        String accept;
        try {
            accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((wsKey.trim() + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (Exception e) { throw new IOException(e); }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
            + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        Ws ws = new Ws(s, out);
        String t = r.param("t");
        if (t == null || !MessageDigest.isEqual(t.getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII))) {
            ws.close(4001, "bad key");   // the page shows "scan again"
            return;
        }
        s.setSoTimeout(0);
        byte[] myKey = key;
        StringBuilder hello = new StringBuilder("{\"type\":\"hello\",\"peer\":\"phone\",\"host\":").append(Json.quote(hostName)).append(",\"items\":[");
        boolean first = true;
        for (Item it : items) { if (!first) hello.append(','); hello.append(it.json()); first = false; }
        hello.append("]}");
        ws.text(seal(hello.toString()));
        sockets.add(ws);
        listener.onGuests(sockets.size());
        try {
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            while (running) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;
                int opcode = b0 & 0x0f;
                boolean fin = (b0 & 0x80) != 0;
                long len = b1 & 0x7f;
                if (len == 126) len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xff); }
                if (len > 8L << 20) break;
                byte[] mask = new byte[4];
                boolean masked = (b1 & 0x80) != 0;
                if (masked) readFully(in, mask);
                byte[] payload = new byte[(int) len];
                readFully(in, payload);
                if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                if (opcode == 0x8) { ws.close(1000, ""); break; }
                if (opcode == 0x9) { ws.send(0xA, payload); continue; }
                if (opcode == 0xA) continue;
                message.write(payload);
                if (!fin) continue;
                String text = message.toString("UTF-8");
                message.reset();
                if (key != myKey) { ws.close(4001, "new code"); break; }
                onMessage(ws, text);
            }
        } catch (IOException ignored) {
        } finally {
            sockets.remove(ws);
            listener.onGuests(sockets.size());
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void onMessage(Ws ws, String sealedB64) {
        Map<String, Object> m;
        try {
            Object o = Json.parse(new String(SecretBox.open(key, Base64.getDecoder().decode(sealedB64.trim())), StandardCharsets.UTF_8));
            if (!(o instanceof Map)) return;
            @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) o;
            m = mm;
        } catch (Exception e) {
            ws.close(4001, "bad key");
            return;
        }
        if ("text".equals(m.get("type"))) {
            String text = Json.str(m, "text", "");
            if (text.length() > 100_000) text = text.substring(0, 100_000);
            if (!text.trim().isEmpty()) publish(new Item(newId(), "text", "phone", text, null, null, 0, 0, null));
        }
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    // ---------------------------------------------------------------- helpers
    private String seal(String json) {
        return Base64.getEncoder().encodeToString(SecretBox.seal(key, json.getBytes(StandardCharsets.UTF_8)));
    }

    private void publish(Item it) {
        items.add(it);
        broadcast("{\"type\":\"item\",\"item\":" + it.json() + "}");
        listener.onItem(it);
    }

    private void broadcast(String json) {
        if (sockets.isEmpty()) return;
        String sealed = seal(json);
        for (Ws w : sockets) {
            ExecutorService p = pool;
            if (p != null) p.execute(() -> w.text(sealed));
        }
    }

    private final Map<String, Long> lastProgress = new ConcurrentHashMap<>();

    private void progress(String id, String name, long done, long total, long started, String label) {
        long now = System.nanoTime();
        Long last = lastProgress.get(id);
        if (last != null && now - last < 200_000_000L && done < total) return;
        lastProgress.put(id, now);
        double secs = (now - started) / 1e9;
        listener.onProgress(id, label, name, done, Math.max(total, 0), secs > 0.2 ? done / secs : 0);
    }

    private byte[] asset(String path) throws IOException {
        byte[] b = assetCache.get(path);
        if (b == null) {
            b = assets.read(path);
            assetCache.put(path, b);
        }
        return b;
    }

    static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static long parseLong(String s, long d) {
        try { return s == null ? d : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return d; }
    }

    static String safeName(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        StringBuilder b = new StringBuilder();
        for (char c : n.toCharArray()) b.append(c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
        String out = b.toString();
        while (out.endsWith(".")) out = out.substring(0, out.length() - 1);
        if (out.length() > 150) out = out.substring(0, 150);
        return out.isEmpty() ? "file" : out;
    }

    static String cleanPath(String path) {
        StringBuilder b = new StringBuilder();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            if (b.length() > 0) b.append('/');
            b.append(safeName(part));
        }
        return b.length() == 0 ? "file" : b.toString();
    }

    private static File unique(File folder, String name) {
        folder.mkdirs();
        File f = new File(folder, name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; f.exists(); n++) f = new File(folder, stem + " (" + n + ")" + ext);
        return f;
    }

    static String mimeOf(String name) {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.US) : "";
        switch (ext) {
            case "html": return "text/html; charset=utf-8";
            case "css": return "text/css; charset=utf-8";
            case "js": return "application/javascript; charset=utf-8";
            case "json": return "application/json";
            case "svg": return "image/svg+xml";
            case "woff2": return "font/woff2";
            case "txt": case "md": return "text/plain";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "heic": return "image/heic";
            case "mp4": return "video/mp4";
            case "mov": return "video/quicktime";
            case "webm": return "video/webm";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "apk": return "application/vnd.android.package-archive";
            default: return "application/octet-stream";
        }
    }

    // ---------------------------------------------------------------- tiny JSON
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String s) {
            Json j = new Json(s);
            Object v = j.value();
            j.ws();
            if (j.i != s.length()) throw new IllegalArgumentException("trailing data");
            return v;
        }

        static String str(Map<String, Object> m, String k, String d) {
            Object v = m.get(k);
            return v instanceof String ? (String) v : d;
        }

        static long num(Map<String, Object> m, String k, long d) {
            Object v = m.get(k);
            return v instanceof Number ? ((Number) v).longValue() : d;
        }

        static String quote(String v) {
            StringBuilder b = new StringBuilder(v.length() + 2).append('"');
            for (int k = 0; k < v.length(); k++) {
                char c = v.charAt(k);
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format(Locale.US, "\\u%04x", (int) c));
                        else b.append(c);
                }
            }
            return b.append('"').toString();
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("end of input");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i);
            if (n.isEmpty()) throw new IllegalArgumentException("bad value at " + st);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected " + c);
            i++;
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            }
        }
    }
}
