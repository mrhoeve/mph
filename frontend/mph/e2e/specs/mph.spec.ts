import { APIRequestContext, expect, Page, test } from '@playwright/test';

interface FolderItem {
  name: string;
  path: string;
}

interface FolderResponse {
  path: string;
  remembered: boolean;
  children: FolderItem[];
}

async function openChildFolder(page: Page, name: string): Promise<void> {
  await page.getByRole('button', { name, exact: true }).click();
  await expect(page.locator('.current-path strong')).toContainText(name);
}

async function configureFixtureWorkspace(request: APIRequestContext): Promise<void> {
  let response = await request.get('/api/filesystem/current');
  expect(response.ok()).toBeTruthy();
  let folder = (await response.json()) as FolderResponse;
  if (folder.remembered && folder.path.replaceAll('\\', '/').endsWith('/e2e/fixtures/workspace'))
    return;

  for (const name of ['e2e', 'fixtures', 'workspace']) {
    const child = folder.children.find((item) => item.name === name);
    expect(child, `Expected ${name} below ${folder.path}`).toBeDefined();
    response = await request.get('/api/filesystem/folders', { params: { path: child!.path } });
    expect(response.ok()).toBeTruthy();
    folder = (await response.json()) as FolderResponse;
  }

  response = await request.post('/api/filesystem/base', {
    data: { path: folder.path, maxScanDepth: 3 },
  });
  expect(response.ok()).toBeTruthy();
}

async function openAnalyzedWorkspace(page: Page): Promise<void> {
  await configureFixtureWorkspace(page.request);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Projects' })).toBeVisible();
  await expect(
    page.locator('.project-select-button').filter({ hasText: 'sample-parent' }),
  ).toBeVisible();
}

async function openSampleAppDetails(page: Page): Promise<void> {
  await openAnalyzedWorkspace(page);
  await page.getByRole('button', { name: 'Expand sample-parent' }).click();
  await page.getByText('sample-app', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'sample-app' })).toBeVisible();
}

test.describe.serial('MPH full-stack journeys', () => {
  test('selects a fixture workspace and analyzes its Maven projects', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Select a folder' })).toBeVisible();

    await openChildFolder(page, 'e2e');
    await openChildFolder(page, 'fixtures');
    await openChildFolder(page, 'workspace');
    await page.getByRole('button', { name: 'Use this folder' }).click();

    await expect(page.getByRole('heading', { name: 'Projects' })).toBeVisible();
    await expect(
      page.locator('.project-select-button').filter({ hasText: 'sample-parent' }),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Show Build Order' })).toBeEnabled();
    await expect(page.getByRole('button', { name: /Bulk Update \(0\)/ })).toBeDisabled();
    await expect(page.locator('.error-toast')).toHaveCount(0);
  });

  test('expands modules and filters managed properties to local overrides', async ({ page }) => {
    await openSampleAppDetails(page);

    await page.getByRole('button', { name: 'Manage component versions' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: 'Managed Component Versions' })).toBeVisible();
    await expect(dialog.locator('tbody tr')).toHaveCount(2);

    await dialog.getByRole('checkbox', { name: 'Show only overrides' }).check();
    await expect(dialog.locator('tbody tr')).toHaveCount(1);
    await expect(dialog.locator('tbody tr')).toContainText('library.version');
    await expect(dialog.locator('tbody tr')).not.toContainText('shared.version');

    await dialog.getByRole('checkbox', { name: 'Show only overrides' }).uncheck();
    const restoredRows = dialog.locator('tbody tr');
    await expect(restoredRows).toHaveCount(2);
    await expect(restoredRows.filter({ hasText: 'shared.version' })).toHaveCount(1);
    await expect(restoredRows.filter({ hasText: 'library.version' })).toHaveCount(1);

    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
  });

  test('keeps dialog clicks contained and supports keyboard and overlay dismissal', async ({
    page,
  }) => {
    await openSampleAppDetails(page);
    await page.getByRole('button', { name: 'Manage component versions' }).click();

    let dialog = page.getByRole('dialog');
    await dialog.getByRole('heading', { name: 'Managed Component Versions' }).click();
    await expect(dialog).toBeVisible();

    let overlay = page.locator('.modal-overlay');
    await overlay.focus();
    await page.keyboard.press('Enter');
    await expect(dialog).toBeHidden();

    await page.getByRole('checkbox', { name: 'Select All' }).check();
    await page.getByRole('button', { name: /Bulk Update \(1\)/ }).click();
    dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    overlay = page.locator('.modal-overlay');
    await overlay.click({ position: { x: 5, y: 5 } });
    await expect(dialog).toBeHidden();
  });

  test('supports project selection and bulk-update controls without executing changes', async ({
    page,
  }) => {
    await openAnalyzedWorkspace(page);
    await page.getByRole('checkbox', { name: 'Select All' }).check();
    await page.getByRole('button', { name: /Bulk Update \(1\)/ }).click();

    const dialog = page.getByRole('dialog');
    const updateDependents = dialog.getByRole('checkbox', {
      name: 'Update version in dependent projects',
    });
    await expect(updateDependents).toBeChecked();
    await updateDependents.uncheck();
    await expect(updateDependents).not.toBeChecked();
    await dialog.getByRole('radio', { name: 'Remove Prefix' }).check();
    await expect(dialog.getByRole('radio', { name: 'Remove Prefix' })).toBeChecked();
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();

    await page.getByRole('button', { name: /Rebase on develop \(1\)/ }).click();
    const rebaseDialog = page.getByRole('dialog');
    await expect(rebaseDialog.getByRole('heading', { name: 'Rebase on develop' })).toBeVisible();
    await expect(rebaseDialog).toContainText('Stash tracked and untracked changes');
    await expect(rebaseDialog).toContainText('Source-code and structural POM conflicts');
    await rebaseDialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(rebaseDialog).toBeHidden();

    await page.getByRole('button', { name: 'Show Build Order' }).click();
    const buildOrderDialog = page.getByRole('dialog');
    await expect(
      buildOrderDialog.getByRole('heading', { name: 'Project Build Order' }),
    ).toBeVisible();
    await expect(buildOrderDialog.locator('tbody tr')).toContainText('sample-parent');

    const focus = buildOrderDialog.locator('#project-focus');
    const sampleAppOption = focus.locator('option').filter({ hasText: 'sample-app' });
    const sampleAppPath = await sampleAppOption.getAttribute('value');
    expect(sampleAppPath).toBeTruthy();
    await focus.selectOption(sampleAppPath!);
    await expect(focus).toHaveValue(sampleAppPath!);
    await expect(buildOrderDialog.locator('#dependency-graph svg')).toBeVisible();

    await buildOrderDialog.getByRole('button', { name: 'Close' }).last().click();
    await expect(buildOrderDialog).toBeHidden();
  });
});
