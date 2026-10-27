// Sharable site: landing page + accounts. File bytes never touch this server.
const http = require('http'), fs = require('fs'), path = require('path'), crypto = require('crypto');
const { connect } = require('./db');
const PUB = path.join(__dirname, 'public');
const TYPES = { '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript', '.svg': 'image/svg+xml', '.png': 'image/png' };
const scrypt = (pw, salt) => new Promise((res, rej) => crypto.scrypt(pw, salt, 64, { N: 16384 }, (e, k) => e ? rej(e) : res(k)));
const hashPw = async pw => { const s = crypto.randomBytes(16); return s.toString('hex') + ':' + (await scrypt(pw, s)).toString('hex'); };
const checkPw = async (pw, stored) => { const [s, h] = stored.split(':'); const k = await scrypt(pw, Buffer.from(s, 'hex')); return crypto.timingSafeEqual(k, Buffer.from(h, 'hex')); };
const EMAIL = /^[^\s@]{1,64}@[^\s@]{1,255}\.[^\s@]{2,}$/;
const sha = t => crypto.createHash('sha256').update(t).digest('hex');
function createApp(db) {
  const users = db.col('users'), sessions = db.col('sessions');
  const tries = new Map();
  const limited = ip => { const n = (tries.get(ip) || 0) + 1; tries.set(ip, n); return n > 20; };
  setInterval(() => tries.clear(), 15 * 60 * 1000).unref();
  const send = (res, code, obj, extra = {}) => { const b = JSON.stringify(obj); res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...extra }); res.end(b); };
  const body = req => new Promise((res, rej) => { let d = ''; req.on('data', c => { d += c; if (d.length > 10000) { rej(new Error('big')); req.destroy(); } }); req.on('end', () => { try { res(JSON.parse(d || '{}')); } catch (e) { rej(e); } }); });
  const cookieOpts = req => 'HttpOnly; SameSite=Strict; Path=/; Max-Age=1209600' + (req.headers['x-forwarded-proto'] === 'https' ? '; Secure' : '');
  const getUser = async req => {
    const m = /(?:^|; )sid=([a-f0-9]{64})/.exec(req.headers.cookie || ''); if (!m) return null;
    const s = await sessions.findOne({ _id: sha(m[1]) }); if (!s || s.expires < new Date()) return null;
    return users.findOne({ _id: s.uid });
  };
  return async (req, res) => {
    try {
      res.setHeader('X-Content-Type-Options', 'nosniff');
      res.setHeader('Referrer-Policy', 'same-origin');
      res.setHeader('X-Frame-Options', 'DENY');
      res.setHeader('Content-Security-Policy', "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
      const url = new URL(req.url, 'http://x'); const p = url.pathname;
      if (p.startsWith('/api/')) {
        const ip = (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();
        if (req.method === 'POST' && req.headers.origin && new URL(req.headers.origin).host !== req.headers.host) return send(res, 403, { error: 'bad origin' });
        if (p === '/api/health') return send(res, 200, { ok: true, db: db.name });
        if (p === '/api/signup' && req.method === 'POST') {
          if (limited(ip)) return send(res, 429, { error: 'Too many attempts. Try later.' });
          const b = await body(req); const email = String(b.email || '').trim().toLowerCase(), pw = String(b.password || ''), name = String(b.name || '').trim().slice(0, 60);
          if (!EMAIL.test(email)) return send(res, 400, { error: 'Enter a valid email.' });
          if (pw.length < 8 || pw.length > 200) return send(res, 400, { error: 'Password must be 8 or more characters.' });
          const uid = crypto.randomUUID();
          try { await users.insertOne({ _id: uid, email, name: name || email.split('@')[0], pw: await hashPw(pw), created: new Date() }); }
          catch (e) { if (e.code === 11000) return send(res, 409, { error: 'That email already has an account.' }); throw e; }
          return startSession(req, res, uid);
        }
        if (p === '/api/login' && req.method === 'POST') {
          if (limited(ip)) return send(res, 429, { error: 'Too many attempts. Try later.' });
          const b = await body(req); if (typeof b.password !== 'string' || b.password.length > 200) return send(res, 400, { error: 'Invalid credentials.' }); const u = await users.findOne({ email: String(b.email || '').trim().toLowerCase() });
          const ok = u && await checkPw(String(b.password || ''), u.pw);
          if (!ok) return send(res, 401, { error: 'Wrong email or password.' });
          return startSession(req, res, u._id);
        }
        if (p === '/api/logout' && req.method === 'POST') {
          const m = /(?:^|; )sid=([a-f0-9]{64})/.exec(req.headers.cookie || ''); if (m) await sessions.deleteOne({ _id: sha(m[1]) });
          return send(res, 200, { ok: true }, { 'Set-Cookie': 'sid=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0' });
        }
        const u = await getUser(req); if (!u) return send(res, 401, { error: 'Please log in.' });
        if (p === '/api/me') return send(res, 200, { name: u.name, email: u.email });
        return send(res, 404, { error: 'not found' });
      }
      let f = p === '/' ? '/index.html' : p === '/app' ? '/app.html' : p;
      const fp = path.normalize(path.join(PUB, f)); if (!fp.startsWith(PUB) || !fs.existsSync(fp) || fs.statSync(fp).isDirectory()) { res.writeHead(404); return res.end('Not found'); }
      res.writeHead(200, { 'Content-Type': TYPES[path.extname(fp)] || 'application/octet-stream', 'X-Content-Type-Options': 'nosniff' }); fs.createReadStream(fp).pipe(res);
    } catch (e) { console.error(e.message); if (!res.headersSent && !res.destroyed) send(res, e instanceof SyntaxError ? 400 : 500, { error: e instanceof SyntaxError ? 'Invalid request.' : 'Server error' }); }
  };
  async function startSession(req, res, uid) {
    const sid = crypto.randomBytes(32).toString('hex');
    await sessions.insertOne({ _id: sha(sid), uid, expires: new Date(Date.now() + 14 * 864e5) });
    send(res, 200, { ok: true }, { 'Set-Cookie': 'sid=' + sid + '; ' + cookieOpts(req) });
  }
}
module.exports = { createApp };
if (require.main === module) connect().then(db => { http.createServer(createApp(db)).listen(process.env.PORT || 8080, () => console.log('Sharable site on', process.env.PORT || 8080, 'db:', db.name)); }).catch(e => { console.error('DB connect failed:', e.message); process.exit(1); });
