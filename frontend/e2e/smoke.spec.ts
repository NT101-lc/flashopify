import { expect, test } from '@playwright/test';

test('storefront and admin shells render', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('link', { name: 'Flashopify' })).toBeVisible();

  await page.goto('/admin');
  await expect(page.getByRole('navigation', { name: 'Admin' })).toBeVisible();
});
