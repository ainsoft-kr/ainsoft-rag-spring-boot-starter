import { expect, test } from '@playwright/test';

test('loads sample data and shows search hits', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByTestId('sample-banner')).toContainText('tenant-web-demo');
  await page.getByTestId('load-sample-button').click();

  await expect(page.getByTestId('notifications')).toContainText('샘플 데이터를 준비했습니다.');
  await page.getByTestId('search-submit').click();

  await expect(page.getByTestId('search-results')).toBeVisible();
  await expect(page.getByTestId('search-hit').first()).toContainText('retrieval');
});

test('uploads a file and retrieves it through search', async ({ page }) => {
  await page.goto('/');

  await page.getByTestId('upload-doc-id').fill('playwright-upload');
  await page.getByTestId('upload-file-input').setInputFiles({
    name: 'playwright-upload.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('Playwright upload verification text for Ainsoft RAG demo')
  });

  await page.getByTestId('upload-submit').click();
  await expect(page.getByTestId('notifications')).toContainText('파일이 업로드되어 색인되었습니다.');

  await page.getByTestId('search-query').fill('Playwright upload verification');
  await page.getByTestId('search-submit').click();

  await expect(page.getByTestId('search-results')).toBeVisible();
  await expect(page.getByTestId('search-hit').first()).toContainText('playwright-upload');
});
