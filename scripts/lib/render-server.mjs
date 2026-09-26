import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, resolve, sep } from 'node:path';

// Test fixture only: mock API responses reference images absent from the repository.
const PLACEHOLDER = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=',
  'base64');
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };

export function createStaticServer(root) {
  const base = resolve(root);
  return createServer(async (req, res) => {
    let requested;
    try { requested = decodeURIComponent(req.url.split('?')[0]); }
    catch { res.writeHead(400).end('invalid URL'); return; }
    const file = resolve(base, '.' + requested);
    if (!file.startsWith(base + sep)) { res.writeHead(403).end('forbidden'); return; }
    try {
      const body = await readFile(file);
      res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
      res.end(body);
    } catch (error) {
      if (error.code === 'ENOENT' && /\.(png|jpg|jpeg|gif|ico|svg)$/i.test(file)) {
        res.writeHead(200, { 'Content-Type': 'image/png' });
        res.end(PLACEHOLDER);
      } else {
        res.writeHead(error.code === 'ENOENT' || error.code === 'EISDIR' ? 404 : 500).end('not found');
      }
    }
  });
}
