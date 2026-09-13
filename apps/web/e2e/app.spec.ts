import { expect, test } from '@playwright/test';

test('loads the application shell', async ({ page }) => {
  const response = await page.goto('/');

  expect(response?.status()).toBe(200);
  await expect(page).toHaveTitle('Hortinis');
  await expect(page.getByRole('heading', { level: 1, name: 'Hortinis' })).toBeVisible();
});

test('reloads the application shell offline after the service worker caches it', async ({
  page,
  context,
}) => {
  await page.goto('/');
  await expect(page).toHaveTitle('Hortinis');

  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await page.reload();
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
  await page.waitForFunction(async () => {
    const appCacheName = (await caches.keys()).find((name) => name.includes(':assets:app:cache'));
    if (!appCacheName) {
      return false;
    }

    const appCache = await caches.open(appCacheName);
    const cachedPaths = (await appCache.keys()).map((request) => new URL(request.url).pathname);
    return cachedPaths.includes('/index.html') && cachedPaths.some((path) => path.endsWith('.js'));
  });

  await context.setOffline(true);
  try {
    const response = await page.reload();

    expect(response?.status()).toBe(200);
    expect(response?.fromServiceWorker()).toBe(true);
    await expect(page.getByRole('heading', { level: 1, name: 'Hortinis' })).toBeVisible();
  } finally {
    await context.setOffline(false);
  }
});
