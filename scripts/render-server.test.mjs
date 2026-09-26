import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { request } from 'node:http';
import { createStaticServer } from './lib/render-server.mjs';

test('render fixture server works without px.png and confines file access', async t => {
  const root = await mkdtemp(join(tmpdir(), 'yuelin-render-'));
  await writeFile(join(root, 'page.html'), '<h1>fixture</h1>');
  const server = createStaticServer(root);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await rm(root, { recursive: true }); });
  function get(path) {
    return new Promise((resolve, reject) => {
      const req = request({ host: '127.0.0.1', port: server.address().port, path }, res => {
        const parts = [];
        res.on('data', part => parts.push(part));
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(parts) }));
      });
      req.on('error', reject); req.end();
    });
  }
  assert.equal((await get('/page.html')).status, 200);
  const missingImage = await get('/absent.png');
  assert.equal(missingImage.status, 200);
  assert.equal(missingImage.headers['content-type'], 'image/png');
  assert.equal(missingImage.body.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
  assert.equal((await get('/absent.js')).status, 404);
  assert.equal((await get('/%ZZ')).status, 400);
  assert.equal((await get('/%2e%2e/outside.txt')).status, 403);
});
