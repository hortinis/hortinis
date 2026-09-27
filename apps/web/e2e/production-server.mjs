import { createReadStream, promises as fs } from 'node:fs';
import { createServer, request as createProxyRequest } from 'node:http';
import { extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('../dist/web/browser/', import.meta.url)));
const host = '127.0.0.1';
const port = 4200;
const apiOrigin = process.env['HORTINIS_E2E_API_ORIGIN'];
const contentTypes = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.ico', 'image/x-icon'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.manifest', 'application/manifest+json; charset=utf-8'],
  ['.png', 'image/png'],
  ['.svg', 'image/svg+xml'],
  ['.webmanifest', 'application/manifest+json; charset=utf-8'],
]);

const server = createServer(async (request, response) => {
  const requestUrl = new URL(request.url ?? '/', `http://${host}`);
  if (apiOrigin && requestUrl.pathname.startsWith('/api/')) {
    const upstreamUrl = new URL(`${requestUrl.pathname}${requestUrl.search}`, apiOrigin);
    const upstream = createProxyRequest(
      upstreamUrl,
      { method: request.method, headers: { ...request.headers, host: upstreamUrl.host } },
      (upstreamResponse) => {
        response.writeHead(upstreamResponse.statusCode ?? 502, upstreamResponse.headers);
        upstreamResponse.pipe(response);
      },
    );
    upstream.on('error', () => response.writeHead(502).end());
    request.pipe(upstream);
    return;
  }
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    response.writeHead(405).end();
    return;
  }

  let pathname;
  try {
    pathname = decodeURIComponent(requestUrl.pathname);
  } catch {
    response.writeHead(400).end();
    return;
  }

  const relativePath = pathname === '/' ? 'index.html' : pathname.slice(1);
  const filePath = resolve(root, relativePath);
  if (filePath !== root && !filePath.startsWith(`${root}${sep}`)) {
    response.writeHead(403).end();
    return;
  }

  try {
    const file = await fs.stat(filePath);
    if (!file.isFile()) {
      response.writeHead(404).end();
      return;
    }

    const headers = {
      'Content-Type': contentTypes.get(extname(filePath)) ?? 'application/octet-stream',
      'Content-Length': file.size,
      'Cache-Control': /^(?:ngsw\.json|ngsw-worker\.js|safety-worker\.js)$/.test(pathname.slice(1))
        ? 'no-cache, no-store, must-revalidate'
        : 'no-cache',
    };
    response.writeHead(200, headers);
    if (request.method === 'HEAD') {
      response.end();
    } else {
      createReadStream(filePath).pipe(response);
    }
  } catch {
    response.writeHead(404).end();
  }
});

server.listen(port, host);
