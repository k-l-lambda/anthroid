'use strict';
/**
 * Read SMS on this phone from Node, via the Anthroid app.
 *
 * Filter in your script and print only what you need, so message bodies do not end up
 * in an agent's context:
 *
 *   const sms = require('anthroid-sms');
 *   const msgs = await sms.query({ sinceMinutes: 10, address: '95588' });
 *
 * Each message: { id, thread_id, address, date (epoch ms), date_sent, type, read, body }.
 * The app answers on a Unix socket only this app's uid can connect to; it needs the SMS
 * permission granted in the app.
 */
const net = require('net');

const SOCKET_PATH = process.env.ANTHROID_SMS_SOCKET ||
  '/data/data/com.anthroid/files/apps/com.anthroid/sms/sms.sock';
const DEFAULT_TIMEOUT_MS = 15000;

class SmsError extends Error {}

function call(request, timeoutMs = DEFAULT_TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    const sock = net.createConnection(SOCKET_PATH);
    sock.setTimeout(timeoutMs, () => sock.destroy(new SmsError('TIMEOUT: no reply from the app')));
    sock.on('connect', () => sock.end(JSON.stringify(request)));
    sock.on('data', (chunk) => chunks.push(chunk));
    sock.on('error', (err) => reject(err instanceof SmsError ? err
      : new SmsError(`APP_UNREACHABLE: ${err.message} (is Anthroid running?)`)));
    sock.on('close', (hadError) => {
      if (hadError) return;
      const raw = Buffer.concat(chunks).toString('utf8');
      if (!raw) return reject(new SmsError('EMPTY_RESPONSE: the app closed the connection'));
      let response;
      try { response = JSON.parse(raw); } catch (e) { return reject(new SmsError(`BAD_RESPONSE: ${e.message}`)); }
      if (!response.ok) return reject(new SmsError(response.error || 'UNKNOWN_ERROR'));
      resolve(response);
    });
  });
}

/**
 * Messages newest first.
 * @param {object} f since/until (epoch ms), sinceMinutes, address, keyword,
 *   box ('inbox'|'sent'|'all'), unreadOnly, limit (1..200, default 20), offset, timeoutMs
 */
async function query(f = {}) {
  const request = { op: 'query', box: f.box || 'inbox', limit: f.limit ?? 20, offset: f.offset ?? 0 };
  if (f.since != null) request.since = Number(f.since);
  if (f.until != null) request.until = Number(f.until);
  if (f.sinceMinutes != null) request.since_minutes = Number(f.sinceMinutes);
  if (f.address) request.address = String(f.address);
  if (f.keyword) request.keyword = String(f.keyword);
  if (f.unreadOnly) request.unread_only = true;
  return (await call(request, f.timeoutMs)).messages;
}

/** Resolves to { ok: true, permission: boolean }. */
function status(timeoutMs) {
  return call({ op: 'status' }, timeoutMs);
}

module.exports = { query, status, SmsError, SOCKET_PATH };
