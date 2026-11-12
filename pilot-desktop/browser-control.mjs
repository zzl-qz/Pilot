import { createServer } from 'node:http';

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => {
      if (chunks.length === 0) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (error) {
        reject(error);
      }
    });
    request.on('error', reject);
  });
}

/** 本机浏览器控制口。标签状态和副作用由桌面主进程提供。 */
export function startBrowserControl(token, actions) {
  const routes = new Map([
    ['GET /tabs', actions.state],
    ['POST /tabs', actions.open],
    ['POST /tabs/select', actions.select],
    ['POST /tabs/close', actions.close],
    ['POST /emulate', actions.emulate]
  ]);
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (request.method === 'GET' && url.pathname.startsWith('/marker/')) {
      const id = decodeURIComponent(url.pathname.slice('/marker/'.length));
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      response.end(`<!doctype html><title>新标签</title><body style="margin:0;background:#111"><p id="ready" data-tab="${id}" hidden>pilot-tab-${id}</p></body>`);
      return;
    }
    if (request.headers['x-pilot-token'] !== token) {
      response.writeHead(401);
      response.end('unauthorized');
      return;
    }
    try {
      const action = routes.get(request.method + ' ' + url.pathname);
      if (!action) {
        response.writeHead(404);
        response.end('not found');
        return;
      }
      const result = action(request.method === 'POST' ? await readBody(request) : undefined);
      if (result === null) {
        response.writeHead(404);
        response.end('浏览器标签不存在');
        return;
      }
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify(result));
    } catch (error) {
      response.writeHead(500);
      response.end(String(error.message || error));
    }
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));
}
