import { expect, test } from '@playwright/test';
import {
  json,
  mockDashboard,
  mockSites,
  supervisor,
  waitForApp,
} from './support';

test('supervisor receives the operational tabs and dashboard', async ({ page }) => {
  await page.route('**/api/v1/me', (route) => json(route, supervisor));
  await mockSites(page);
  await mockDashboard(page, 'SUPERVISOR', 'SUPERVISOR');

  const dashboardRequest = page.waitForRequest((request) => request.url().includes('/api/v1/dashboard/SUPERVISOR'));
  await page.goto('/');

  await waitForApp(page, 'stage=HOME;tab=DASHBOARD;role=SUPERVISOR');
  await waitForApp(page, 'tabs=DASHBOARD,INSPECTIONS,ACTIVE_PROBLEMS,REPORTS,MAINTENANCE,MORE');
  await waitForApp(page, 'comparison=2');
  await waitForApp(page, 'sites=2');

  const request = await dashboardRequest;
  expect(request.url()).toContain('/api/v1/dashboard/SUPERVISOR');
  expect(new URL(request.url()).searchParams.has('from')).toBe(true);
  expect(new URL(request.url()).searchParams.has('to')).toBe(true);
});
