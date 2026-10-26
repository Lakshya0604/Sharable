package com.sharable.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-Java streaming HTTP file server (no Android dependencies, so it can be tested on a JVM).
 * Same protocol as the Node server: PIN auth, per-piece SHA-256 verified resumable uploads keyed by a
 * session key, prefix rewind, Range downloads, bounded memory.
 */
public class SharableServer {
    public interface Source { long size(); InputStream open(long offset) throws IOException; }
    public interface Assets { byte[] get(String name) throws IOException; }
    public interface Shared { Map<String, Source> list(); }

    private final File dir; private final String pin; private final Assets assets; private final Shared shared;
    private ServerSocket ss; private ExecutorService pool; private volatile boolean running;
    private final AtomicInteger fails = new AtomicInteger();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    public volatile long bytesIn, bytesOut;

    public SharableServer(File dir, String pin, Assets assets, Shared shared) {
        this.dir = dir; this.pin = pin; this.assets = assets; this.shared = shared; dir.mkdirs();
    }
    public static String newPin() { return String.valueOf(100000 + new SecureRandom().nextInt(900000)); }
    public String pin() { return pin; }

    public int start(int port) throws IOException {
        try { ss = new ServerSocket(port, 64); } catch (BindException e) { ss = new ServerSocket(0, 64); }
        ss.setReuseAddress(true);
        pool = Executors.newCachedThreadPool(); running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try { final Socket s = ss.accept(); s.setTcpNoDelay(true); s.setSoTimeout(60000); pool.execute(() -> handle(s)); }
                catch (IOException e) { if (running) continue; else break; }
            }
        }, "sharable-accept");
        t.setDaemon(true); t.start();
        return ss.getLocalPort();
    }
    public void stop() { running = false; try { if (ss != null) ss.close(); } catch (IOException ignored) {} if (pool != null) pool.shutdownNow(); }

    // ---- HTTP plumbing
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(); int c;
        while ((c = in.read()) != -1) { if (c == '\n') break; if (c != '\r') sb.append((char) c); if (sb.length() > 16384) throw new IOException("line too long"); }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }
    private void handle(Socket s) {
        try (Socket sock = s) {
            InputStream in = new BufferedInputStream(sock.getInputStream(), 1 << 16);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 1 << 16);
            String line = readLine(in); if (line == null || line.isEmpty()) return;
            String[] p = line.split(" "); if (p.length < 2) return;
            String method = p[0], target = p[1];
            Map<String, String> h = new HashMap<>();
            String hl; while ((hl = readLine(in)) != null && !hl.isEmpty()) { int i = hl.indexOf(':'); if (i > 0) h.put(hl.substring(0, i).trim().toLowerCase(Locale.ROOT), hl.substring(i + 1).trim()); }
            String path = target, qs = ""; int qi = target.indexOf('?'); if (qi >= 0) { path = target.substring(0, qi); qs = target.substring(qi + 1); }
            Map<String, String> q = parseQuery(qs);
            route(method, path, q, h, in, out);
            out.flush();
        } catch (Exception ignored) { }
    }
    private static Map<String, String> parseQuery(String qs) {
        Map<String, String> m = new HashMap<>();
        for (String kv : qs.split("&")) { if (kv.isEmpty()) continue; int i = kv.indexOf('='); try { String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), "UTF-8"); String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), "UTF-8"); m.put(k, v); } catch (Exception ignored) {} }
        return m;
    }
    private static void respond(OutputStream out, int code, String type, byte[] body, String... extra) throws IOException {
        StringBuilder sb = new StringBuilder("HTTP/1.1 " + code + " " + reason(code) + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n");
        for (int i = 0; i + 1 < extra.length; i += 2) sb.append(extra[i]).append(": ").append(extra[i + 1]).append("\r\n");
        out.write(sb.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1)); out.write(body);
    }
    private static String reason(int c) { switch (c) { case 200: return "OK"; case 206: return "Partial Content"; case 400: return "Bad Request"; case 401: return "Unauthorized"; case 404: return "Not Found"; case 409: return "Conflict"; case 413: return "Payload Too Large"; case 416: return "Range Not Satisfiable"; case 422: return "Unprocessable Entity"; case 429: return "Too Many Requests"; default: return "Error"; } }
    private static String js(String s) { StringBuilder b = new StringBuilder("\""); for (char c : s.toCharArray()) { if (c == '"' || c == '\\') b.append('\\').append(c); else if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); } return b.append('"').toString(); }
    private static void json(OutputStream out, int code, String body, String... extra) throws IOException { respond(out, code, "application/json", body.getBytes(StandardCharsets.UTF_8), extra); }
    private static String err(String m) { return "{\"error\":" + js(m) + "}"; }
    private static String safe(String n) {
        if (n == null) return null; n = n.replace('\\', '/'); n = n.substring(n.lastIndexOf('/') + 1);
        if (n.isEmpty() || n.equals(".") || n.equals("..")) return null;
        for (char c : n.toCharArray()) if (c < 0x20) return null;
        return n.length() > 200 ? null : n;
    }
    private boolean authed(Map<String, String> h, Map<String, String> q) {
        String given = h.containsKey("x-pin") ? h.get("x-pin") : q.getOrDefault("pin", "");
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), pin.getBytes(StandardCharsets.UTF_8));
    }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    private void route(String method, String path, Map<String, String> q, Map<String, String> h, InputStream in, OutputStream out) throws Exception {
        if (method.equals("GET") && !path.startsWith("/api") && !path.startsWith("/f/")) {
            String f = path.equals("/") ? "index.html" : path.substring(path.lastIndexOf('/') + 1);
            byte[] b = null; try { b = assets.get(f); } catch (IOException ignored) {}
            if (b == null) { json(out, 404, err("not found")); return; }
            String type = f.endsWith(".html") ? "text/html; charset=utf-8" : f.endsWith(".js") ? "text/javascript" : f.endsWith(".css") ? "text/css" : "application/octet-stream";
            respond(out, 200, type, b); return;
        }
        if (!authed(h, q)) { if (fails.incrementAndGet() > 50) { json(out, 429, err("too many attempts")); return; } json(out, 401, err("bad pin")); return; }
        if (path.equals("/api/files")) { json(out, 200, listFiles()); return; }
        if (path.equals("/api/session")) { session(out, q); return; }
        if (path.startsWith("/f/") && (method.equals("GET") || method.equals("HEAD"))) { download(method, path, h, out); return; }
        if (path.startsWith("/u/")) { upload(method, path, q, h, in, out); return; }
        json(out, 404, err("not found"));
    }

    private String listFiles() {
        StringBuilder b = new StringBuilder("["); boolean first = true;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) {
            if (!f.isFile() || f.getName().endsWith(".part") || f.getName().endsWith(".meta")) continue;
            if (!first) b.append(','); first = false;
            b.append("{\"name\":").append(js(f.getName())).append(",\"size\":").append(f.length()).append(",\"mtime\":").append(f.lastModified()).append('}');
        }
        Map<String, Source> sh = shared == null ? null : shared.list();
        if (sh != null) for (Map.Entry<String, Source> e : sh.entrySet()) {
            if (!first) b.append(','); first = false;
            b.append("{\"name\":").append(js(e.getKey())).append(",\"size\":").append(e.getValue().size()).append(",\"mtime\":").append(System.currentTimeMillis()).append(",\"shared\":true}");
        }
        return b.append(']').toString();
    }

    private void download(String method, String path, Map<String, String> h, OutputStream out) throws Exception {
        String n; try { n = safe(URLDecoder.decode(path.substring(3).replace("+", "%2B"), "UTF-8")); } catch (Exception e) { n = null; }
        if (n == null) { json(out, 404, err("not found")); return; }
        Source src = null; File f = new File(dir, n);
        if (f.isFile() && !n.endsWith(".part") && !n.endsWith(".meta")) { final File ff = f; src = new Source() {
            public long size() { return ff.length(); }
            public InputStream open(long off) throws IOException { FileInputStream fi = new FileInputStream(ff); long s = fi.skip(off); while (s < off) { long k = fi.skip(off - s); if (k <= 0) break; s += k; } return fi; } }; }
        else if (shared != null && shared.list() != null && shared.list().containsKey(n)) src = shared.list().get(n);
        if (src == null) { json(out, 404, err("not found")); return; }
        long size = src.size(), start = 0, end = size - 1; int code = 200;
        String r = h.get("range");
        if (r != null && r.startsWith("bytes=")) {
            String[] pr = r.substring(6).split("-", -1);
            try {
                if (pr[0].isEmpty() && !pr[1].isEmpty()) start = Math.max(0, size - Long.parseLong(pr[1]));
                else { start = pr[0].isEmpty() ? 0 : Long.parseLong(pr[0]); if (!pr[1].isEmpty()) end = Math.min(Long.parseLong(pr[1]), end); }
            } catch (NumberFormatException e) { start = 0; }
            if (start > end || start >= size && size > 0) { json(out, 416, err("range"), "Content-Range", "bytes */" + size); return; }
            code = 206;
        }
        long len = size == 0 ? 0 : end - start + 1;
        String fn = URLEncoder.encode(n, "UTF-8").replace("+", "%20");
        StringBuilder sb = new StringBuilder("HTTP/1.1 " + code + " " + reason(code) + "\r\nContent-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\nContent-Length: " + len + "\r\nConnection: close\r\nContent-Disposition: attachment; filename*=UTF-8''" + fn + "\r\n");
        if (code == 206) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
        out.write(sb.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
        if (method.equals("HEAD") || len == 0) return;
        try (InputStream is = src.open(start)) {
            byte[] buf = new byte[1 << 18]; long left = len;
            while (left > 0) { int k = is.read(buf, 0, (int) Math.min(buf.length, left)); if (k < 0) break; out.write(buf, 0, k); left -= k; bytesOut += k; }
        }
    }

    // ---- upload sessions
    private static class Piece { long off, len; String sha; }
    private static class Meta { String name; long size, verified; List<Piece> pieces = new ArrayList<>(); }
    private Meta loadMeta(File mf, String name, long size, File part) {
        Meta m = new Meta(); m.name = name; m.size = size;
        if (mf.exists() && part.exists()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(mf), StandardCharsets.UTF_8))) {
                Meta x = new Meta(); x.name = r.readLine(); x.size = Long.parseLong(r.readLine()); x.verified = Long.parseLong(r.readLine());
                String l; while ((l = r.readLine()) != null) { String[] t = l.split(" "); Piece pc = new Piece(); pc.off = Long.parseLong(t[0]); pc.len = Long.parseLong(t[1]); pc.sha = t[2]; x.pieces.add(pc); }
                if (name.equals(x.name) && x.size == size) return x;
            } catch (Exception ignored) {}
        }
        return m;
    }
    private void saveMeta(File mf, Meta m) throws IOException {
        StringBuilder b = new StringBuilder(m.name + "\n" + m.size + "\n" + m.verified + "\n");
        for (Piece p : m.pieces) b.append(p.off).append(' ').append(p.len).append(' ').append(p.sha).append('\n');
        File tmp = new File(mf.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(b.toString().getBytes(StandardCharsets.UTF_8)); o.getFD().sync(); }
        if (!tmp.renameTo(mf)) { mf.delete(); tmp.renameTo(mf); }
    }
    private static boolean validKey(String k) { return k != null && k.matches("[a-f0-9]{16,64}"); }

    private void session(OutputStream out, Map<String, String> q) throws IOException {
        String key = q.get("key"); if (!validKey(key)) { json(out, 400, err("key")); return; }
        String n = safe(q.get("name")); long total; try { total = Long.parseLong(q.get("size")); } catch (Exception e) { json(out, 400, err("size")); return; }
        File part = new File(dir, key + ".part"), mf = new File(dir, key + ".meta");
        Meta m = (n == null) ? new Meta() : loadMeta(mf, n, total, part);
        StringBuilder b = new StringBuilder("{\"verified\":" + m.verified + ",\"pieces\":[");
        for (int i = 0; i < m.pieces.size(); i++) { Piece p = m.pieces.get(i); if (i > 0) b.append(','); b.append("{\"off\":" + p.off + ",\"len\":" + p.len + ",\"sha\":\"" + p.sha + "\"}"); }
        json(out, 200, b.append("]}").toString());
    }

    private void upload(String method, String path, Map<String, String> q, Map<String, String> h, InputStream in, OutputStream out) throws Exception {
        String n; try { n = safe(URLDecoder.decode(path.substring(3).replace("+", "%2B"), "UTF-8")); } catch (Exception e) { n = null; }
        if (n == null) { json(out, 400, err("name")); return; }
        String key = q.get("key"); if (!validKey(key)) { json(out, 400, err("key")); return; }
        long total; try { total = Long.parseLong(q.getOrDefault("size", "-1")); } catch (NumberFormatException e) { total = -1; }
        if (total < 0) { json(out, 400, err("size")); return; }
        File part = new File(dir, key + ".part"), mf = new File(dir, key + ".meta");
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            Meta m = loadMeta(mf, n, total, part);
            if (!part.exists()) { m.verified = 0; m.pieces.clear(); }
            if (method.equals("HEAD")) { if (part.exists() && part.length() > m.verified) { try (RandomAccessFile r = new RandomAccessFile(part, "rw")) { r.setLength(m.verified); } } json(out, 200, "{}", "X-Offset", String.valueOf(m.verified)); return; }
            if (method.equals("DELETE")) { part.delete(); mf.delete(); json(out, 200, "{\"ok\":true}"); return; }
            if (!method.equals("PUT")) { json(out, 404, err("not found")); return; }
            long off; try { off = Long.parseLong(q.getOrDefault("offset", "0")); } catch (NumberFormatException e) { json(out, 400, err("offset")); return; }
            String want = h.getOrDefault("x-sha256", "").toLowerCase(Locale.ROOT);
            if (!want.matches("[a-f0-9]{64}")) { json(out, 400, err("x-sha256 required")); return; }
            long cl; try { cl = Long.parseLong(h.getOrDefault("content-length", "-1")); } catch (NumberFormatException e) { cl = -1; }
            if (cl < 0) { json(out, 400, err("content-length required")); return; }
            if (off < m.verified) {
                int k = -1; for (int i = 0; i < m.pieces.size(); i++) if (m.pieces.get(i).off == off) { k = i; break; }
                if (k < 0) { json(out, 409, "{\"error\":\"offset mismatch\",\"offset\":" + m.verified + "}"); return; }
                m.pieces = new ArrayList<>(m.pieces.subList(0, k)); m.verified = off;
            } else if (off != m.verified) { json(out, 409, "{\"error\":\"offset mismatch\",\"offset\":" + m.verified + "}"); return; }
            if (off + cl > total) { json(out, 413, err("exceeds declared size")); return; }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long got = 0; boolean ok = true;
            try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                raf.setLength(off); raf.seek(off);
                byte[] buf = new byte[1 << 18];
                while (got < cl) {
                    int k; try { k = in.read(buf, 0, (int) Math.min(buf.length, cl - got)); } catch (IOException e) { k = -1; }
                    if (k < 0) { ok = false; break; }
                    md.update(buf, 0, k); raf.write(buf, 0, k); got += k; bytesIn += k;
                }
                if (!ok) { raf.setLength(off); return; }
                if (!hex(md.digest()).equals(want)) { raf.setLength(off); json(out, 422, "{\"error\":\"piece hash mismatch, resend\",\"offset\":" + off + "}"); return; }
                raf.getFD().sync();
            }
            long pos = off + got; Piece pc = new Piece(); pc.off = off; pc.len = got; pc.sha = want; m.pieces.add(pc); m.verified = pos; saveMeta(mf, m);
            if ("1".equals(q.get("final"))) {
                if (pos != total) { json(out, 422, "{\"error\":\"incomplete\",\"offset\":" + pos + "}"); return; }
                File dest = new File(dir, n); int dot = n.lastIndexOf('.'); String base = dot > 0 ? n.substring(0, dot) : n, ext = dot > 0 ? n.substring(dot) : ""; int i = 1;
                while (dest.exists()) dest = new File(dir, base + " (" + (i++) + ")" + ext);
                if (!part.renameTo(dest)) { json(out, 500, err("rename failed")); return; }
                mf.delete(); locks.remove(key);
                json(out, 200, "{\"ok\":true,\"name\":" + js(dest.getName()) + ",\"size\":" + total + "}"); return;
            }
            json(out, 200, "{\"ok\":true,\"offset\":" + pos + "}");
        }
    }
}
