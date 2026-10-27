// DB layer: MongoDB Atlas in production; in-memory ONLY for tests (MEMORY_DB=1).
const crypto = require('crypto');
function memory() {
  const cols = {};
  const col = n => cols[n] || (cols[n] = []);
  const match = (d, q) => Object.entries(q).every(([k, v]) => v && v.$gt !== undefined ? d[k] > v.$gt : d[k] === v);
  return { name: 'memory', col: n => ({
    async findOne(q) { return col(n).find(d => match(d, q)) || null; },
    async insertOne(d) { if (n === 'users' && col(n).some(x => x.email === d.email)) { const e = new Error('dup'); e.code = 11000; throw e; } col(n).push({ ...d }); },
    async find(q) { return col(n).filter(d => match(d, q)).map(d => ({ ...d })); },
    async deleteOne(q) { const i = col(n).findIndex(d => match(d, q)); if (i >= 0) col(n).splice(i, 1); return { deletedCount: i >= 0 ? 1 : 0 }; },
    async deleteMany(q) { cols[n] = col(n).filter(d => !match(d, q)); },
    async countDocuments(q) { return col(n).filter(d => match(d, q)).length; } }) };
}
async function connect() {
  if (process.env.MEMORY_DB === '1') return memory();
  const uri = process.env.MONGODB_URI;
  if (!uri) throw new Error('MONGODB_URI is not set');
  const { MongoClient } = require('mongodb');
  const client = new MongoClient(uri, { serverSelectionTimeoutMS: 10000 });
  await client.connect();
  const db = client.db(process.env.MONGODB_DB || 'sharable');
  await db.collection('users').createIndex({ email: 1 }, { unique: true });
  await db.collection('sessions').createIndex({ expires: 1 }, { expireAfterSeconds: 0 });
  await db.command({ ping: 1 });
  return { name: 'mongodb', col: n => {
    const c = db.collection(n);
    return { findOne: q => c.findOne(q), insertOne: d => c.insertOne(d), find: q => c.find(q).toArray(), deleteOne: q => c.deleteOne(q), deleteMany: q => c.deleteMany(q), countDocuments: q => c.countDocuments(q) };
  } };
}
module.exports = { connect };
