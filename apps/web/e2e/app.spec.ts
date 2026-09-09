import { expect, test } from '@playwright/test';

test('loads the application shell', async ({ page }) => {
  const response = await page.goto('/');

  expect(response?.status()).toBe(200);
  await expect(page).toHaveTitle('Hortinis');
  await expect(page.getByRole('heading', { level: 1, name: 'Hortinis' })).toBeVisible();
});
