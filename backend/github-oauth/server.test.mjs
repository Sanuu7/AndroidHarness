import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createAuthServer } from './server.mjs';

const redirect = 'com.androidharness.app.oauth://github/callback';
const payload = { code: 'example-code', code_verifier: 'a'.repeat(43), redirect_uri: redirect };
async function fixture(t, upstream) {
  const calls = [];
  const server = createAuthServer({ clientId: 'public-id', clientSecret: 'server-only-secret', redirectUris: [redirect],
    fetchImpl: async (url, options) => { calls.push({ url, options }); return upstream(); } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const post = (path, body) => fetch(`http://127.0.0.1:${server.address().port}${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  return { post, calls };
}

test('exchange binds the registered redirect and PKCE, keeping secret server-side', async t => {
  const { post, calls } = await fixture(t, () => Response.json({ access_token: 'oauth-token', token_type: 'bearer', debug: 'private' }));
  const response = await post('/exchange', payload);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.deepEqual(await response.json(), { access_token: 'oauth-token', token_type: 'bearer' });
  assert.equal(calls[0].options.body.get('client_secret'), 'server-only-secret');
  assert.equal(calls[0].options.body.get('code_verifier'), payload.code_verifier);
  assert.equal(calls[0].options.body.get('redirect_uri'), redirect);
  assert.equal(calls[0].options.redirect, 'error');
});

test('rejects unregistered redirects and missing PKCE before contacting GitHub', async t => {
  const { post, calls } = await fixture(t, () => { throw Error('must not call'); });
  for (const body of [{ ...payload, redirect_uri: 'https://attacker.example' }, { ...payload, code_verifier: '' }, null]) {
    assert.equal((await post('/exchange', body)).status, 400);
  }
  assert.equal(calls.length, 0);
});

test('refresh forwards rotated credentials and uses only configured client ID', async t => {
  const result = { access_token: 'new-token', refresh_token: 'new-refresh', expires_in: 28800 };
  const { post, calls } = await fixture(t, () => Response.json(result));
  assert.deepEqual(await (await post('/refresh', { refresh_token: 'r'.repeat(40), client_id: 'attacker' })).json(), result);
  assert.equal(calls[0].options.body.get('client_id'), 'public-id');
  assert.equal(calls[0].options.body.get('grant_type'), 'refresh_token');
});

test('upstream errors never expose diagnostic content', async t => {
  const { post } = await fixture(t, () => Response.json({ error: 'bad_verification_code', error_description: 'secret diagnostic' }));
  const response = await post('/exchange', payload);
  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), { error: 'authorization_failed' });
});

test('network failures return a retryable generic response', async t => {
  const { post } = await fixture(t, () => { throw Error('sensitive internals'); });
  const response = await post('/exchange', payload);
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: 'github_unavailable' });
});
