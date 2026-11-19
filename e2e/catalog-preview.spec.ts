import { expect, test, type Page } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { previewFixture } from '../test/catalog-preview-fixture';

const rehearsal = resolve(
  process.env['HORTINIS_CATALOG_PREVIEW_DIR'] ??
    '../hortinis-plants/artifacts/releases/reviewed-catalog/content-rehearsal',
);

test('imports a synthetic local preview and keeps facts on their own subjects', async ({
  page,
}) => {
  await openPreview(page);
  await importFixture(page, '1.0.0');
  const subject = page.getByLabel('Browse subjects');
  await subject.selectOption('plant');
  await expect(page.getByRole('heading', { name: 'frost_sensitivity' })).toHaveCount(2);
  await subject.selectOption('cultivar');
  await expect(page.getByRole('heading', { name: 'transplant' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'frost_sensitivity' })).toHaveCount(0);
  await subject.selectOption('growing_type');
  await expect(page.getByText('No facts or rules are recorded for this subject.')).toBeVisible();
  await importFixture(page, '2.0.0');
  await page.getByRole('button', { name: 'Restore previous preview (1.0.0)' }).click();
  await expect(page.getByText('Version 1.0.0 · Schema 1.0.0 · 3 subjects')).toBeVisible();
});

test('imports the full reviewed rehearsal and restores it after an offline reload', async ({
  page,
  context,
}) => {
  test.skip(
    !existsSync(resolve(rehearsal, 'manifest.json')),
    'The reviewed content rehearsal is unavailable.',
  );
  await openPreview(page);
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);

  const manifest = JSON.parse(readFileSync(resolve(rehearsal, 'manifest.json'), 'utf8')) as {
    profile: string;
    requiredArtifacts: { path: string }[];
    optionalArtifacts: { path: string }[];
  };
  expect(manifest.profile).toBe('dev-validation');
  const scope = JSON.parse(readFileSync(resolve(rehearsal, 'candidate-scope.json'), 'utf8'));
  expect(scope.mode).toBe('content-rehearsal');
  const paths = ['manifest.json', ...manifest.requiredArtifacts.map(({ path }) => path)];
  for (const { path } of manifest.optionalArtifacts) {
    if (existsSync(resolve(rehearsal, path))) {
      paths.push(path);
    }
  }

  await page
    .getByLabel('Catalog preview files')
    .setInputFiles(paths.map((path) => resolve(rehearsal, path)));
  const status = page.getByRole('status');
  await expect(status).toHaveText('Catalog preview imported.', { timeout: 30_000 });
  await expect(page.getByText('Version 0.0.0 · Schema 1.0.0 · 163 subjects')).toBeVisible();

  const subject = page.getByLabel('Browse subjects');
  await subject.selectOption({ label: 'Bintje (cultivar)' });
  await expect(page.getByRole('heading', { name: 'harvest_guidance' })).toHaveCount(2);
  const firstClaim = page
    .getByRole('article')
    .filter({ has: page.getByRole('heading', { name: 'harvest_guidance' }) })
    .first();
  await firstClaim.getByText('Scope, content review and evidence').click();
  await expect(firstClaim.getByText(/Rights pending-review/).first()).toBeVisible();
  await subject.selectOption({ label: 'Longum-type hot pepper (growing-type)' });
  await expect(page.getByRole('heading', { name: 'Longum-type hot pepper' })).toBeVisible();

  await importFixture(page, '2.0.0');
  await context.setOffline(true);
  try {
    const response = await page.reload();
    expect(response?.fromServiceWorker()).toBe(true);
    await expect(page.getByText('Version 2.0.0 · Schema 1.0.0 · 3 subjects')).toBeVisible();
    await page.getByRole('button', { name: 'Restore previous preview (0.0.0)' }).click();
    await expect(page.getByText('Version 0.0.0 · Schema 1.0.0 · 163 subjects')).toBeVisible();
    await page.reload();
    await expect(page.getByText('Version 0.0.0 · Schema 1.0.0 · 163 subjects')).toBeVisible();
  } finally {
    await context.setOffline(false);
  }
});

async function openPreview(page: Page): Promise<void> {
  await page.goto('/');
  const preview = page.getByRole('link', { name: 'Internal catalog preview' });
  const setup = page.getByRole('button', { name: 'Nouveau jardin' });
  await expect(preview.or(setup)).toBeVisible();
  if (await setup.isVisible()) {
    await setup.click();
    await page.getByRole('textbox').fill('Playwright preview test garden');
    await page.getByRole('button', { name: 'Créer', exact: true }).click();
  }
  await preview.click();
}

async function importFixture(page: Page, version: string): Promise<void> {
  await page.getByLabel('Catalog preview files').setInputFiles(
    [...previewFixture({ version })].map(([name, buffer]) => ({
      name,
      buffer,
      mimeType: name.endsWith('.gz') ? 'application/gzip' : 'application/json',
    })),
  );
  await expect(page.getByRole('status')).toHaveText('Catalog preview imported.');
  await expect(page.getByText(`Version ${version} · Schema 1.0.0 · 3 subjects`)).toBeVisible();
}
