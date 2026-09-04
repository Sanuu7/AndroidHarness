import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';

const json = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
    'Pragma': 'no-cache', 'X-Content-Type-Options': 'nosniff' });
  res.end(JSON.stringify(body));
};

// No database, cookies, token logs, redirects, or client secret in responses.
export function createAuthServer({ clientId, clientSecret, redirectUris, fetchImpl = fetch }) {
  if (!clientId || !clientSecret || !redirectUris?.length) throw new Error('Missing OAuth server configuration');
  const allowedRedirects = new Set(redirectUris);
  const requests = new Map();
  const server = createServer(async (req, res) => {
    if (req.method === 'GET' && req.url === '/health') return json(res, 200, { ok: true });
    if (req.method !== 'POST' || !['/exchange', '/refresh'].includes(req.url))
      return json(res, 404, { error: 'not_found' });
    // Use the socket address, never an untrusted X-Forwarded-For header.
    const now = Date.now();
    for (const [ip, item] of requests) if (item.until <= now) requests.delete(ip);
    const ip = req.socket.remoteAddress;
    const rate = requests.get(ip) ?? { count: 0, until: now + 60_000 };
    requests.set(ip, rate);
    if (++rate.count > 30) return json(res, 429, { error: 'try_later' });
    if (!/^application\/json(?:;|$)/i.test(req.headers['content-type'] ?? ''))
      return json(res, 415, { error: 'json_required' });
    try {
      const chunks = [];
      let size = 0;
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 8192) { json(res, 413, { error: 'request_too_large' }); return; }
        chunks.push(chunk);
      }
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString()); }
      catch { return json(res, 400, { error: 'invalid_request' }); }
      if (!body || typeof body !== 'object' || Array.isArray(body))
        return json(res, 400, { error: 'invalid_request' });
      const form = new URLSearchParams({ client_id: clientId, client_secret: clientSecret });
      if (req.url === '/exchange') {
        if (typeof body.code !== 'string' || !/^[A-Za-z0-9_-]{1,512}$/.test(body.code) ||
            typeof body.code_verifier !== 'string' || !/^[A-Za-z0-9._~-]{43,128}$/.test(body.code_verifier) ||
            !allowedRedirects.has(body.redirect_uri)) return json(res, 400, { error: 'invalid_request' });
        form.set('code', body.code);
        form.set('code_verifier', body.code_verifier);
        form.set('redirect_uri', body.redirect_uri);
      } else {
        if (typeof body.refresh_token !== 'string' || !/^[A-Za-z0-9_-]{20,1024}$/.test(body.refresh_token))
          return json(res, 400, { error: 'invalid_request' });
        form.set('grant_type', 'refresh_token');
        form.set('refresh_token', body.refresh_token);
      }
      const upstream = await fetchImpl('https://github.com/login/oauth/access_token', {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(20_000),
        headers: { Accept: 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' }, body: form,
      });
      const result = await upstream.json();
      if (!upstream.ok || result.error || typeof result.access_token !== 'string')
        return json(res, 400, { error: 'authorization_failed' });
      // Explicit allowlist prevents forwarding upstream diagnostics or secrets.
      const output = {};
      for (const key of ['access_token', 'token_type', 'scope', 'refresh_token', 'expires_in', 'refresh_token_expires_in']) {
        if (result[key] !== undefined) output[key] = result[key];
      }
      return json(res, 200, output);
    } catch {
      return json(res, 502, { error: 'github_unavailable' });
    }
  });
  server.requestTimeout = 30_000;
  server.headersTimeout = 10_000;
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createAuthServer({ clientId: process.env.GITHUB_CLIENT_ID, clientSecret: process.env.GITHUB_CLIENT_SECRET,
    redirectUris: (process.env.GITHUB_REDIRECT_URIS ?? '').split(',').map(s => s.trim()).filter(Boolean),
  }).listen(Number(process.env.PORT ?? 8080), '0.0.0.0');
}
