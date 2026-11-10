import { expect, test } from '@playwright/test';
import {
  json,
  manager,
  mockDashboard,
  mockSites,
  report,
  waitForApp,
} from './support';

test('manager loads daily reports from the reports tab', async ({ page }) => {
  await page.route('**/api/v1/me', (route) => json(route, manager));
  await mockSites(page);
  await mockDashboard(page, 'MANAGER', 'MANAGER');
  await page.route(/\/api\/v1\/reports\?limit=100$/, (route) =>
    json(route, [
      report('report-1', 'RPT-RTG-A-20261108-DAY', 'SUBMITTED'),
      report('report-2', 'RPT-RTG-A-20261107-DAY', 'APPROVED'),
    ]),
  );

  const reportsRequest = page.waitForRequest((request) => request.url().includes('/api/v1/reports?limit=100'));
  await page.goto('/');
  await waitForApp(page, 'stage=HOME;tab=DASHBOARD;role=MANAGER');

  await page.mouse.click(40, 340);
  await waitForApp(page, 'tab=REPORTS;role=MANAGER');
  await waitForApp(page, 'reports=2');

  const request = await reportsRequest;
  expect(request.method()).toBe('GET');
});
