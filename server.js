// Sharable core: zero-dependency streaming file server for Wi-Fi/hotspot transfer.
// Usage: node server.js [dir] [port]   (PIN printed on start)
const http = require('http'), fs = require('fs'), path = require('path'), os = require('os'), crypto = require('crypto');
const DIR = path.resolve(process.argv[2] || path.join(__dirname, 'shared'));
const PORT = +(process.argv[3] || 8080);
const PIN = String(crypto.randomInt(100000, 999999));
fs.mkdirSync(DIR, { recursive: true });
const PUB = path.join(__dirname, 'public');
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
const safe = n => { n = path.basename(decodeURIComponent(n)); return n && n !== '.' && n !== '..' ? n : null; };
const authed = (req, u) => { const p = req.headers['x-pin'] || u.searchParams.get('pin') || ''; const a = Buffer.from(p), b = Buffer.from(PIN); return a.length === b.length && crypto.timingSafeEqual(a, b); };
let fails = 0;
http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const send = (c, o, h = {}) => { res.writeHead(c, { 'Content-Type': 'application/json', ...h }); res.end(JSON.stringify(o)); };
  try {
    if (req.method === 'GET' && !u.pathname.startsWith('/api') && !u.pathname.startsWith('/f/')) {
      const f = u.pathname === '/' ? 'index.html' : path.basename(u.pathname);
      const fp = path.join(PUB, f);
      if (!fs.existsSync(fp)) return send(404, { error: 'not found' });
      res.writeHead(200, { 'Content-Type': TYPES[path.extname(f)] || 'application/octet-stream' });
      return fs.createReadStream(fp).pipe(res);
    }
    if (!authed(req, u)) { if (++fails > 50) return send(429, { error: 'too many attempts' }); return send(401, { error: 'bad pin' }); }
    if (u.pathname === '/api/files') {
      const list = fs.readdirSync(DIR).filter(n => !(n.endsWith('.part') || n.endsWith('.meta'))).map(n => { const s = fs.statSync(path.join(DIR, n)); return s.isFile() ? { name: n, size: s.size, mtime: s.mtimeMs } : null; }).filter(Boolean);
      return send(200, list);
    }
    if (u.pathname.startsWith('/f/') && (req.method === 'GET' || req.method === 'HEAD')) {
      const n = safe(u.pathname.slice(3)); const fp = n && path.join(DIR, n);
      if (!fp || !fs.existsSync(fp)) return send(404, { error: 'not found' });
      const size = fs.statSync(fp).size; let start = 0, end = size - 1, code = 200;
      const m = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '');
      if (m) { if (m[1] === '' && m[2]) { start = Math.max(0, size - +m[2]); } else { start = +m[1] || 0; if (m[2]) end = Math.min(+m[2], end); } if (start > end) return send(416, { error: 'range' }, { 'Content-Range': `bytes */${size}` }); code = 206; }
      const h = { 'Content-Type': 'application/octet-stream', 'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1, 'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(n)}` };
      if (code === 206) h['Content-Range'] = `bytes ${start}-${end}/${size}`;
      res.writeHead(code, h);
      if (req.method === 'HEAD' || size === 0) return res.end();
      return fs.createReadStream(fp, { start, end, highWaterMark: 1 << 20 }).pipe(res);
    }
    // Resumable upload, keyed by a client session key (hash of name|size|mtime), so different files
    // with the same name never share a partial. Each piece carries its SHA-256 (X-Sha256) computed
    // from the source; a piece is committed only if the bytes received match. Resume offset is the
    // last verified byte, anything beyond it is truncated.
    if (u.pathname.startsWith('/u/')) {
      const n = safe(u.pathname.slice(3)); if (!n) return send(400, { error: 'name' });
      const key = u.searchParams.get('key') || ''; if (!/^[a-f0-9]{16,64}$/.test(key)) return send(400, { error: 'key' });
      const total = +(u.searchParams.get('size') || -1); if (!(total >= 0)) return send(400, { error: 'size' });
      const part = path.join(DIR, key + '.part'), metaP = path.join(DIR, key + '.meta');
      let meta = { name: n, size: total, verified: 0 };
      if (fs.existsSync(metaP)) { try { meta = JSON.parse(fs.readFileSync(metaP, 'utf8')); } catch {} }
      if (meta.name !== n || meta.size !== total) meta = { name: n, size: total, verified: 0 };
      if (!fs.existsSync(part)) meta.verified = 0;
      const saveMeta = () => fs.writeFileSync(metaP, JSON.stringify(meta));
      if (req.method === 'HEAD') {
        if (fs.existsSync(part) && fs.statSync(part).size > meta.verified) fs.truncateSync(part, meta.verified);
        res.writeHead(200, { 'X-Offset': meta.verified }); return res.end();
      }
      if (req.method === 'DELETE') { fs.rmSync(part, { force: true }); fs.rmSync(metaP, { force: true }); return send(200, { ok: true }); }
      if (req.method === 'PUT') {
        const off = +(u.searchParams.get('offset') || 0), want = String(req.headers['x-sha256'] || '').toLowerCase();
        if (!/^[a-f0-9]{64}$/.test(want)) return send(400, { error: 'x-sha256 required' });
        if (off !== meta.verified) return send(409, { error: 'offset mismatch', offset: meta.verified });
        if (off + (+req.headers['content-length'] || 0) > total) return send(413, { error: 'exceeds declared size' });
        const fd = fs.openSync(part, fs.existsSync(part) ? 'r+' : 'w+'); fs.ftruncateSync(fd, off);
        const h = crypto.createHash('sha256'); let pos = off, failed = false;
        const abort = () => { try { fs.ftruncateSync(fd, off); fs.closeSync(fd); } catch {} };
        req.on('data', c => { h.update(c); try { fs.writeSync(fd, c, 0, c.length, pos); pos += c.length; } catch (e) { failed = e; } });
        req.on('aborted', abort);
        req.on('end', () => {
          if (failed) { abort(); return send(500, { error: String(failed.message) }); }
          if (pos > total) { abort(); return send(413, { error: 'exceeds declared size' }); }
          if (h.digest('hex') !== want) { abort(); return send(422, { error: 'piece hash mismatch, resend', offset: off }); }
          fs.fsyncSync(fd); fs.closeSync(fd); meta.verified = pos; saveMeta();
          if (u.searchParams.get('final') === '1') {
            if (pos !== total) return send(422, { error: 'incomplete', offset: pos });
            let dest = path.join(DIR, n); const ext = path.extname(n), base = path.basename(n, ext); let i = 1;
            while (fs.existsSync(dest)) dest = path.join(DIR, `${base} (${i++})${ext}`);
            fs.renameSync(part, dest); fs.rmSync(metaP, { force: true });
            return send(200, { ok: true, name: path.basename(dest), size: total });
          }
          send(200, { ok: true, offset: pos });
        });
        return;
      }
    }
    send(404, { error: 'not found' });
  } catch (e) { if (!res.headersSent) send(500, { error: e.message }); else res.destroy(); }
}).listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat().filter(i => i.family === 'IPv4' && !i.internal).map(i => i.address);
  console.log(`Sharable serving ${DIR}\nPIN: ${PIN}\n` + ips.map(i => `Open on phone/laptop (PIN prefilled): http://${i}:${PORT}/#pin=${PIN}`).join('\n'));
});
