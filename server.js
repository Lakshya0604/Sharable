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
      const list = fs.readdirSync(DIR).filter(n => !n.endsWith('.part')).map(n => { const s = fs.statSync(path.join(DIR, n)); return s.isFile() ? { name: n, size: s.size, mtime: s.mtimeMs } : null; }).filter(Boolean);
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
    // Resumable upload: HEAD /u/name -> X-Offset ; PUT /u/name?offset=N&final=1 streams body to disk.
    if (u.pathname.startsWith('/u/')) {
      const n = safe(u.pathname.slice(3)); if (!n) return send(400, { error: 'name' });
      const part = path.join(DIR, n + '.part'); const have = fs.existsSync(part) ? fs.statSync(part).size : 0;
      if (req.method === 'HEAD') { res.writeHead(200, { 'X-Offset': have }); return res.end(); }
      if (req.method === 'DELETE') { fs.rmSync(part, { force: true }); return send(200, { ok: true }); }
      if (req.method === 'PUT') {
        const off = +(u.searchParams.get('offset') || 0);
        if (off !== have) return send(409, { error: 'offset mismatch', offset: have });
        const ws = fs.createWriteStream(part, { flags: off ? 'a' : 'w', highWaterMark: 1 << 20 });
        req.pipe(ws);
        ws.on('finish', () => {
          const total = fs.statSync(part).size;
          if (u.searchParams.get('final') === '1') {
            let dest = path.join(DIR, n); const ext = path.extname(n), base = path.basename(n, ext); let i = 1;
            while (fs.existsSync(dest)) dest = path.join(DIR, `${base} (${i++})${ext}`);
            fs.renameSync(part, dest); return send(200, { ok: true, name: path.basename(dest), size: total });
          }
          send(200, { ok: true, offset: total });
        });
        ws.on('error', e => send(500, { error: e.message }));
        req.on('aborted', () => ws.destroy());
        return;
      }
    }
    send(404, { error: 'not found' });
  } catch (e) { if (!res.headersSent) send(500, { error: e.message }); else res.destroy(); }
}).listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat().filter(i => i.family === 'IPv4' && !i.internal).map(i => i.address);
  console.log(`Sharable serving ${DIR}\nPIN: ${PIN}\n` + ips.map(i => `Open on phone/laptop (PIN prefilled): http://${i}:${PORT}/#pin=${PIN}`).join('\n'));
});
