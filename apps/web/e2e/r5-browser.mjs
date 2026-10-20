// Test-only HTTPS edge and fresh replacement browser evidence; no traces or secret diagnostics.
import { createServer, request } from 'node:https';
import { readFile, stat } from 'node:fs/promises';
import { createHash, X509Certificate } from 'node:crypto';
import { resolve, extname, dirname } from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const origin = process.env.R5_ORIGIN;
const backend = process.env.R5_BACKEND;
const pfx = await readFile(process.env.R5_TLS);
const passphrase = process.env.R5_TLS_PASSWORD;
const cert = await readFile(resolve(dirname(process.env.R5_TLS), 'runtime.pem'));
const certificate = new X509Certificate(cert);
const spki = createHash('sha256')
  .update(certificate.publicKey.export({ type: 'spki', format: 'der' }))
  .digest('base64');
const root = resolve('apps/web/dist/web/browser');
const email = 'Gardener+tag@Example.test';
const password = ' exact +&=🌱 synthetic password ';
const replacement = ' new +&=🌷 synthetic password ';
let step = 'startup';
setInterval(() => console.log('Replacement browser phase: ' + step), 15000).unref();
process.on('unhandledRejection', () => {
  console.error('R5 asynchronous fixture failure at: ' + step);
  process.exit(1);
});
let edgeFault;
const releases = [];
const server = createServer({ pfx, passphrase }, async (req, res) => {
  const url = new URL(req.url, origin);
  if (url.pathname.startsWith('/api/')) {
    const upstream = request(
      new URL(url.pathname + url.search, backend),
      {
        method: req.method,
        headers: { ...req.headers, host: new URL(backend).host },
        ca: cert,
      },
      (reply) => {
        const fault = edgeFault?.path === url.pathname ? edgeFault : undefined;
        if (!fault) {
          res.writeHead(reply.statusCode, reply.headers);
          reply.pipe(res);
          return;
        }
        edgeFault = undefined;
        const chunks = [];
        reply.on('data', (chunk) => chunks.push(chunk));
        reply.on('end', async () => {
          fault.received(reply.statusCode);
          if (fault.drop) {
            res.destroy();
            return;
          }
          await fault.release;
          if (!res.destroyed) {
            res.writeHead(reply.statusCode, reply.headers);
            res.end(Buffer.concat(chunks));
          }
        });
      },
    );
    upstream.on('error', () => res.writeHead(502).end());
    req.pipe(upstream);
    return;
  }
  try {
    let path = resolve(root, '.' + url.pathname);
    if (!path.startsWith(root + '/') && path !== root) {
      res.writeHead(403).end();
      return;
    }
    if (['/', '/accept-invitation', '/reset-password'].includes(url.pathname))
      path = resolve(root, 'index.html');
    const info = await stat(path);
    if (!info.isFile()) throw new Error();
    const types = {
      '.html': 'text/html',
      '.js': 'text/javascript',
      '.css': 'text/css',
      '.json': 'application/json',
      '.webmanifest': 'application/manifest+json',
    };
    res.writeHead(200, {
      'Content-Type': types[extname(path)] ?? 'application/octet-stream',
      'Cache-Control': 'no-store',
      'Referrer-Policy': 'no-referrer',
    });
    res.end(await readFile(path));
  } catch {
    res.writeHead(404).end();
  }
});
await new Promise((resolve) => server.listen(Number(new URL(origin).port), resolve));
function control(action) {
  return new Promise((resolve, reject) => {
    request(new URL('/__r5/control?action=' + action, backend), { ca: cert }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => {
        try {
          resolve(JSON.parse(Buffer.concat(chunks).toString()));
        } catch {
          reject(new Error('Synthetic fixture control failed'));
        }
      });
    })
      .on('error', reject)
      .end();
  });
}
const browser = await chromium.launch({ args: ['--ignore-certificate-errors-spki-list=' + spki] });
let count = 0;
const contexts = [];
const storageSnapshots = [];
const diagnostics = [];
const tokens = [];
const csrfCanaries = [];
const cookieCanaries = [];
const observations = [];
async function storageSnapshot(page) {
  return page.evaluate(async () => {
    if (!(await indexedDB.databases()).some((db) => db.name === 'hortinis')) {
      return JSON.stringify([{ ...localStorage }, { ...sessionStorage }]);
    }
    const db = await new Promise((resolve) => {
      const request = indexedDB.open('hortinis');
      request.onsuccess = () => resolve(request.result);
    });
    try {
      const rows = await Promise.all(
        Array.from(
          db.objectStoreNames,
          (name) =>
            new Promise((resolve) => {
              const tx = db.transaction(name);
              const request = tx.objectStore(name).getAll();
              tx.oncomplete = () => resolve(request.result);
            }),
        ),
      );
      return JSON.stringify([rows, { ...localStorage }, { ...sessionStorage }]);
    } finally {
      db.close();
    }
  });
}

async function fresh(status = 'active') {
  for (const previous of contexts.splice(0)) {
    for (const page of previous.pages()) storageSnapshots.push(await storageSnapshot(page));
    await previous.close();
  }
  await control('fresh-' + status);
  const context = await browser.newContext({ serviceWorkers: 'block' });
  context.setDefaultTimeout(15000);
  contexts.push(context);
  const page = await context.newPage();
  page.on('console', (message) => diagnostics.push(message.text()));
  page.on('response', (response) => {
    if (!response.url().includes('/api/')) return;
    observations.push(
      response
        .headerValue('set-cookie')
        .then((value) => {
          const session = /^__Host-hortinis-session=([^;]+)/.exec(value ?? '')?.[1];
          if (session) cookieCanaries.push(session);
        })
        .catch(() => undefined),
    );
    if (response.url().endsWith('/auth/csrf'))
      observations.push(
        response
          .json()
          .then((value) => {
            if (typeof value.token === 'string') csrfCanaries.push(value.token);
          })
          .catch(() => undefined),
      );
  });
  page.on('request', (req) => {
    if (req.url().includes('/api/')) {
      if (tokens.some((token) => req.url().includes(token))) throw new Error('Secret URL leak');
    }
  });
  await page.goto(origin);
  await expect(page.getByRole('heading', { name: 'Hortinis', exact: true })).toBeVisible();
  return { page, context };
}
async function open(page) {
  await expect(page.getByRole('heading', { name: 'Hortinis', exact: true })).toBeVisible();
  const button = page.getByRole('button', { name: 'Open account access', exact: true });
  if (await button.count()) await button.click();
  else await page.getByRole('button', { name: 'Check session status' }).click();
  await expect(page.getByRole('button', { name: 'Check session status' })).toBeEnabled();
}
async function login(page, candidate = password) {
  await page.locator('#account-email').fill(email);
  await page.locator('#account-password').fill(candidate);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible();
}
async function csrf(page) {
  return page.evaluate(async () => (await fetch('/api/v1/auth/csrf')).json());
}
async function api(page, path, body, accept = 'application/json') {
  const token = await csrf(page);
  return page.evaluate(
    async ({ path, body, accept, token }) => {
      const response = await fetch('/api/v1/auth' + path, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: accept,
          [token.headerName]: token.token,
        },
        body: JSON.stringify(body),
      });
      return {
        status: response.status,
        type: response.headers.get('Content-Type'),
        cache: response.headers.get('Cache-Control'),
        body: await response.json(),
      };
    },
    { path, body, accept, token },
  );
}
async function data(page, store) {
  return page.evaluate(async (store) => {
    const db = await new Promise((resolve, reject) => {
      const r = indexedDB.open('hortinis');
      r.onsuccess = () => resolve(r.result);
      r.onerror = reject;
    });
    try {
      return await new Promise((resolve, reject) => {
        const tx = db.transaction(store);
        const r = tx.objectStore(store).getAll();
        tx.oncomplete = () => resolve(r.result);
        tx.onabort = reject;
      });
    } finally {
      db.close();
    }
  }, store);
}
async function seed(page) {
  const operation = {
    operationId: crypto.randomUUID(),
    recordId: crypto.randomUUID(),
    kind: 'create',
    value: 'synthetic offline garden',
  };
  await page.evaluate(async (operation) => {
    const db = await new Promise((resolve) => {
      const r = indexedDB.open('hortinis');
      r.onsuccess = () => resolve(r.result);
    });
    try {
      await new Promise((resolve, reject) => {
        const tx = db.transaction(['technicalRecords', 'outboxOperations'], 'readwrite');
        tx.objectStore('technicalRecords').put({
          recordId: operation.recordId,
          value: operation.value,
          lastAcceptedRevision: null,
        });
        tx.objectStore('outboxOperations').put(operation);
        tx.oncomplete = resolve;
        tx.onabort = reject;
      });
    } finally {
      db.close();
    }
  }, operation);
  return operation;
}
async function retained(page) {
  // Establish real binding, then real pull removes first-exchange admission.
  await expect(page.getByRole('region', { name: 'Server connection' })).toContainText('connected');
  await expect.poll(async () => (await data(page, 'localInitialAdmissions')).length).toBe(0);
  await expect
    .poll(async () => (await data(page, 'synchronizationLeases'))[0]?.expiresAt ?? 0)
    .toBeLessThan(Date.now() + 1);
}
try {
  step = 'native form encoding and exact password';
  {
    const { page } = await fresh();
    await open(page);
    const sent = page.waitForRequest('**/api/v1/auth/login');
    await login(page);
    expect((await sent).postData()).toBe(new URLSearchParams({ email, password }).toString());
    await retained(page);
    count++;
  }
  step = 'pending disabled and unverified denial';
  for (const status of ['pending', 'disabled', 'unverified']) {
    step = 'native denial ' + status;
    const { page } = await fresh(status);
    await open(page);
    await page.locator('#account-email').fill(email);
    await page.locator('#account-password').fill(password);
    const result = page.waitForResponse('**/api/v1/auth/login');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    expect((await result).status()).toBe(401);
    await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toHaveCount(0);
    count++;
  }
  step = 'inert invitation fragment explicit acceptance and replay';
  {
    const { page } = await fresh('pending');
    const issued = await control('invitation');
    tokens.push(issued.token);
    await page.goto(origin + '/accept-invitation#token=' + issued.token);
    await expect(page.locator('#action-password')).toBeVisible();
    expect(new URL(page.url()).hash).toBe('');
    expect((await control('observe')).status).toBe('pending');
    await expect(page.getByText(/Whoever controls it can reset/)).toBeVisible();
    await page.locator('#action-password').fill(replacement);
    await page.getByRole('button', { name: 'Accept invitation', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();
    await expect(
      page.getByRole('region', { name: 'Account access' }).getByRole('status'),
    ).toContainText('Invitation accepted');
    expect((await control('observe')).status).toBe('active');
    expect(
      (await api(page, '/invitations/accept', { token: issued.token, newPassword: replacement }))
        .status,
    ).toBe(409);
    count++;
  }
  step = 'reset expiry replacement lost success and cleared-fragment reload';
  for (const mode of ['expire', 'replacement', 'lost-success']) {
    step = 'reset ' + mode + ' fixture';
    const { page } = await fresh();
    const issued = await control('reset');
    tokens.push(issued.token);
    if (mode === 'expire') await control('expire');
    if (mode === 'replacement') await control('reset');
    step = 'reset ' + mode + ' open';
    await page.goto(origin + '/reset-password#token=' + issued.token);
    await expect(page.locator('#action-password')).toBeVisible();
    expect(new URL(page.url()).hash).toBe('');
    let lostResponse;
    if (mode === 'lost-success')
      lostResponse = new Promise((resolve) => {
        edgeFault = { path: '/api/v1/auth/password-reset/complete', drop: true, received: resolve };
      });
    step = 'reset ' + mode + ' submit';
    await page.locator('#action-password').fill(replacement);
    await page.getByRole('button', { name: 'Reset password', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Account access' })).toContainText(
      /could not be completed|no longer current/,
    );
    step = 'reset ' + mode + ' replay';
    if (mode === 'lost-success') {
      const committed = await lostResponse;
      step = 'reset lost-success committed HTTP ' + committed;
      expect(committed).toBe(200);
      const replay = await api(page, '/password-reset/complete', {
        token: issued.token,
        newPassword: replacement,
      });
      step = 'reset lost-success replay HTTP ' + replay.status;
      expect(replay.status).toBe(409);
    }
    step = 'reset ' + mode + ' reload';
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Hortinis', exact: true })).toBeVisible();
    await expect(page.getByText(/No usable link/)).toBeVisible();
    count++;
  }
  step = 'generic reset UI eligibility quota and SMTP failure';
  let generic;
  for (const status of [
    'active',
    'pending',
    'disabled',
    'unknown',
    'quota',
    'global-quota',
    'smtp-failure',
  ]) {
    const { page } = await fresh(status === 'pending' || status === 'disabled' ? status : 'active');
    if (status === 'smtp-failure') await control('smtp-failure');
    if (status === 'global-quota') await control('fill-reset-allocation');
    await open(page);
    await page.getByText('Forgot your password?', { exact: true }).click();
    await page.locator('#reset-email').fill(status === 'unknown' ? 'unknown@example.test' : email);
    if (status === 'quota') await api(page, '/password-reset/request', { email });
    const result = page.waitForResponse('**/api/v1/auth/password-reset/request');
    await page.getByRole('button', { name: 'Request reset instructions' }).click();
    expect((await result).status()).toBe(202);
    const notice = page.getByRole('region', { name: 'Account access' }).getByRole('status');
    await expect(notice).toContainText('does not confirm');
    const text = await notice.innerText();
    generic ??= text;
    expect(text).toBe(generic);
    count++;
  }
  step = 'R6 unknown reset flood preserves real ledger and subsequent SMTP delivery';
  {
    const { page } = await fresh();
    for (let index = 0; index < 4; index++) {
      const response = await api(page, '/password-reset/request', {
        email: 'unknown' + index + '@example.test',
      });
      expect(response.status).toBe(202);
      expect(response.body).toEqual({ accepted: true });
    }
    expect((await control('observe')).reservations).toBe(0);
    expect((await control('observe')).deliveries).toBe(0);
    const response = await api(page, '/password-reset/request', { email });
    expect(response.status).toBe(202);
    expect((await control('observe')).reservations).toBe(1);
    expect((await control('observe')).deliveries).toBe(1);
    count++;
  }
  step = 'password change and negotiated fixed errors';
  {
    const { page } = await fresh();
    await open(page);
    await login(page);
    await retained(page);
    const second = await page.context().newPage();
    await second.goto(origin);
    step = 'password change second tab status';
    await open(second);
    step = 'password change input';
    await page.locator('#change-current-password').fill(password);
    await page.locator('#change-new-password').fill(replacement);
    step = 'password change submit';
    await page.getByRole('button', { name: 'Change password', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();
    expect(
      await page.evaluate(async () => (await (await fetch('/api/v1/auth/session')).json()).state),
    ).toBe('anonymous');
    step = 'password change fixed Accept errors';
    for (const accept of ['text/html', 'malformed/;accept']) {
      const response = await api(
        page,
        '/password-reset/complete',
        { token: 'A'.repeat(43), newPassword: replacement },
        accept,
      );
      expect(response.status).toBe(409);
      expect(response.type).toContain('application/json');
      expect(response.cache).toBe('no-store');
    }
    step = 'password change revoked tab status';
    await open(second);
    await expect(second.getByRole('button', { name: 'Sign out', exact: true })).toHaveCount(0);
    count++;
  }
  step = 'offline sign-out tabs retained work reconnect and matching-account resumption';
  {
    const { page, context } = await fresh();
    await open(page);
    await login(page);
    await retained(page);
    const binding = await data(page, 'localServerBindings');
    const second = await context.newPage();
    await second.goto(origin);
    await open(second);
    await context.setOffline(true);
    const operation = await seed(page);
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(second.getByRole('region', { name: 'Account access' })).toContainText(
      'Check the current session',
    );
    let logouts = 0;
    context.on('request', (req) => {
      if (req.url().endsWith('/auth/logout')) logouts++;
    });
    await context.setOffline(false);
    await open(page);
    step = 'offline reconnect never replays logout';
    expect(logouts).toBe(0);
    step = 'offline work remains before explicit login';
    expect((await data(page, 'outboxOperations'))[0].operationId).toBe(operation.operationId);
    expect(await data(page, 'localServerBindings')).toEqual(binding);
    await page.locator('#relogin-email').fill(email);
    await page.locator('#relogin-password').fill(password);
    await page.getByRole('button', { name: 'Sign in again', exact: true }).click();
    step = 'matching account resumes retained outbox';
    await expect
      .poll(async () =>
        (await data(page, 'acceptedOperationResults')).some(
          (row) => row.operationId === operation.operationId,
        ),
      )
      .toBe(true);
    expect(logouts).toBe(0);
    count++;
  }
  step = 'logout cancelled by offline/reconnect during native CSRF bootstrap';
  {
    const { page, context } = await fresh();
    await open(page);
    await login(page);
    let logouts = 0;
    context.on('request', (req) => {
      if (req.url().endsWith('/auth/logout')) logouts++;
    });
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
      releases.push(resolve);
    });
    let received;
    const accepted = new Promise((resolve) => {
      received = resolve;
    });
    edgeFault = { path: '/api/v1/auth/csrf', received, release: held };
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await Promise.race([
      accepted,
      new Promise((_, reject) => setTimeout(() => reject(new Error()), 15000)),
    ]);
    await context.setOffline(true);
    await expect.poll(() => page.evaluate(() => navigator.onLine)).toBe(false);
    await context.setOffline(false);
    release();
    await expect(page.getByRole('button', { name: 'Check session status' })).toBeEnabled();
    expect(logouts).toBe(0);
    await expect(page.getByRole('region', { name: 'Account access' })).toContainText(
      'server session may still exist',
    );
    await open(page);
    await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible();
    expect(logouts).toBe(0);
    count++;
  }
  step = 'retained account mismatch';
  {
    const { page, context } = await fresh();
    await open(page);
    await login(page);
    await retained(page);
    await context.setOffline(true);
    const operation = await seed(page);
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await control('different-account');
    await context.setOffline(false);
    await open(page);
    await login(page);
    await expect(page.getByRole('region', { name: 'Server connection' })).toContainText(
      'destination has changed',
    );
    expect((await data(page, 'outboxOperations'))[0].operationId).toBe(operation.operationId);
    count++;
  }
  for (const loss of ['revoke', 'expire-session']) {
    step = 'remote authority loss ' + loss;
    const { page, context } = await fresh();
    await open(page);
    await login(page);
    await retained(page);
    await context.setOffline(true);
    const operation = await seed(page);
    await control(loss);
    let submissions = 0;
    context.on('request', (req) => {
      if (req.url().endsWith('/sync/operations')) submissions++;
    });
    await context.setOffline(false);
    await page.reload();
    await expect(page.getByRole('region', { name: 'Server connection' })).toContainText(
      'Authentication is required',
    );
    expect((await data(page, 'outboxOperations'))[0].operationId).toBe(operation.operationId);
    expect(submissions).toBe(0);
    await open(page);
    await login(page);
    await expect
      .poll(async () =>
        (await data(page, 'acceptedOperationResults')).some(
          (row) => row.operationId === operation.operationId,
        ),
      )
      .toBe(true);
    expect(submissions).toBe(1);
    count++;
  }
  step = 'CSRF rejection has no automatic unsafe retry';
  {
    const { page } = await fresh();
    await open(page);
    let requests = 0;
    await page.route('**/api/v1/auth/password-reset/request', (route) => {
      requests++;
      return route.continue({
        headers: { ...route.request().headers(), 'x-csrf-token': 'invalid' },
      });
    });
    await page.getByText('Forgot your password?', { exact: true }).click();
    await page.locator('#reset-email').fill(email);
    const rejected = page.waitForResponse('**/api/v1/auth/password-reset/request');
    await page.getByRole('button', { name: 'Request reset instructions' }).click();
    const response = await rejected;
    expect(response.status()).toBe(403);
    expect(response.headers()['cache-control']).toBe('no-store');
    expect(await response.json()).toEqual({ code: 'ACCESS_DENIED', message: 'Access is denied.' });
    await expect(page.getByRole('button', { name: 'Check session status' })).toBeEnabled();
    expect(requests).toBe(1);
    count++;
  }
  step = 'in-flight accepted synchronization cannot commit across sign-out';
  {
    const { page, context } = await fresh();
    step = 'in-flight first-tab authentication';
    await open(page);
    await login(page);
    await retained(page);
    step = 'in-flight initial other-tab session';
    const second = await context.newPage();
    const statuses = [];
    second.on('response', (response) => {
      if (response.url().includes('/api/v1/auth/')) statuses.push(response.status());
    });
    const initialSession = second.waitForResponse('**/api/v1/auth/session', { timeout: 10000 });
    await second.goto(origin);
    step = 'in-flight opening other-tab account panel';
    await open(second);
    await initialSession;
    step = 'in-flight initial other-tab status, auth HTTP ' + statuses.join(',');
    await expect(second.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible();
    await context.setOffline(true);
    const operation = await seed(page);
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
      releases.push(resolve);
    });
    let received;
    const accepted = new Promise((resolve) => {
      received = resolve;
    });
    edgeFault = { path: '/api/v1/sync/operations', received, release: held };
    await context.setOffline(false);
    await page.reload();
    await open(page);
    await Promise.race([
      accepted,
      new Promise((_, reject) => setTimeout(() => reject(new Error()), 15000)),
    ]);
    step = 'in-flight other tab sign-out, auth statuses ' + statuses.join(',');
    await second.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(second.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();
    release();

    await expect
      .poll(async () => (await data(page, 'localExchangeState'))[0].value.state)
      .toBe('auth_required');
    expect(
      (await data(page, 'acceptedOperationResults')).some(
        (row) => row.operationId === operation.operationId,
      ),
    ).toBe(false);
    expect((await data(page, 'outboxOperations'))[0].operationId).toBe(operation.operationId);
    count++;
  }
  step = 'delayed login cannot override a newer tab local sign-out';
  {
    const { page, context } = await fresh();
    await open(page);
    await login(page);
    await retained(page);
    const second = await context.newPage();
    await second.goto(origin);
    await open(second);
    let release;
    const held = new Promise((resolve) => {
      release = resolve;
      releases.push(resolve);
    });
    let received;
    const accepted = new Promise((resolve) => {
      received = resolve;
    });
    edgeFault = { path: '/api/v1/auth/login', received, release: held };
    await page.locator('#relogin-email').fill(email);
    await page.locator('#relogin-password').fill(password);
    await page.getByRole('button', { name: 'Sign in again', exact: true }).click();
    await Promise.race([
      accepted,
      new Promise((_, reject) => setTimeout(() => reject(new Error()), 15000)),
    ]);
    await second.getByRole('button', { name: 'Sign out locally', exact: true }).click();
    release();
    await expect(page.getByRole('button', { name: 'Check session status' })).toBeEnabled();
    await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toHaveCount(0);
    expect((await data(page, 'localExchangeState'))[0].value.logoutPending).toBe(true);
    await open(second);
    await second.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(second.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();
    count++;
  }
  step = 'sensitive diagnostics and browser persistence';
  await Promise.all(observations);
  expect(csrfCanaries.length > 0).toBe(true);
  expect(cookieCanaries.length > 0).toBe(true);
  for (const context of contexts)
    for (const page of context.pages()) {
      storageSnapshots.push(await storageSnapshot(page));
    }
  for (const stored of storageSnapshots)
    for (const secret of [password, replacement, ...tokens, ...csrfCanaries, ...cookieCanaries]) {
      expect(stored.includes(secret)).toBe(false);
    }

  for (const secret of [password, replacement, ...tokens, ...csrfCanaries, ...cookieCanaries])
    expect(diagnostics.join('\n').includes(secret)).toBe(false);
  console.log(`R5 browser scenarios passed: ${count}`);
} catch (error) {
  const label = String(error.message)
    .split('\n')[0]
    .replace(/[A-Za-z0-9_+\/=-]{32,}/g, '[redacted]')
    .slice(0, 150);
  const locator = /waiting for (getByRole[^\n]*)/.exec(String(error.message))?.[1] ?? '';
  console.error('R5 browser scenario failed at: ' + step + ' / ' + label + ' ' + locator);
  process.exitCode = 1;
} finally {
  for (const release of releases) release();
  await browser.close();
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
}
