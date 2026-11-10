import { expect, test } from '@playwright/test';
import {
  json,
  manager,
  mockDashboard,
  mockSites,
  signIn,
  waitForApp,
} from './support';

test('manager signs in and sees the site comparison dashboard', async ({ page }) => {
  let authenticated = false;
  let loginPayload: Record<string, string> | undefined;

  await page.route('**/api/v1/auth/refresh', (route) => json(route, { code: 'UNAUTHORIZED', message: 'No session' }, 401));
  await page.route('**/api/v1/me', (route) =>
    authenticated
      ? json(route, manager)
      : json(route, { code: 'UNAUTHORIZED', message: 'No session' }, 401),
  );
  await page.route('**/api/v1/auth/login', async (route) => {
    loginPayload = route.request().postDataJSON();
    authenticated = true;
    await json(route, {
      accessToken: 'test-access-token',
      tokenType: 'Bearer',
      expiresIn: 3600,
      mustChangePassword: false,
    });
  });
  await mockSites(page);
  await mockDashboard(page, 'MANAGER', 'MANAGER');

  const dashboardRequest = page.waitForRequest((request) => request.url().includes('/api/v1/dashboard/MANAGER'));
  await page.goto('/');
  await signIn(page);

  await waitForApp(page, 'stage=HOME;tab=DASHBOARD;role=MANAGER');
  await waitForApp(page, 'tabs=DASHBOARD,SITES,EQUIPMENT,REPORTS,MORE');
  await waitForApp(page, 'sites=2');
  await waitForApp(page, 'comparison=2');

  const request = await dashboardRequest;
  expect(request.url()).toContain('/api/v1/dashboard/MANAGER');
  expect(loginPayload).toMatchObject({
    username: 'manager',
    password: 'Password1!',
    clientType: 'WEB',
  });
});
