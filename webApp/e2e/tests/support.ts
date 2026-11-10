import { expect, type Page, type Route } from '@playwright/test';

export const manager = {
  id: 'user-manager',
  username: 'manager',
  fullName: 'Manager One',
  employeeId: 'E-100',
  role: 'MANAGER',
  siteIds: ['site-a', 'site-b'],
  mustChangePassword: false,
  active: true,
  version: 0,
};

export const supervisor = {
  ...manager,
  id: 'user-supervisor',
  username: 'supervisor',
  fullName: 'Supervisor One',
  employeeId: 'E-200',
  role: 'SUPERVISOR',
};

export const sites = [
  {
    id: 'site-a',
    code: 'RTG-A',
    name: 'Rutongo A',
    state: 'ACTIVE',
    timezone: 'Africa/Kigali',
    version: 0,
  },
  {
    id: 'site-b',
    code: 'RTG-B',
    name: 'Rutongo B',
    state: 'ACTIVE',
    timezone: 'Africa/Kigali',
    version: 0,
  },
];

export function dashboard(kind: string, role: string) {
  return {
    kind,
    role,
    from: '2026-11-02T00:00:00Z',
    to: '2026-11-09T00:00:00Z',
    generatedAt: '2026-11-09T00:00:00Z',
    equipment: { total: 12, operational: 9, underRepair: 2, down: 1 },
    inspections: { expected: 24, completed: 20, missing: 2, completionPercent: 83.3 },
    breakdowns: { open: 2, downtimeMinutes: 145 },
    maintenance: { recorded: 8, dueSoon: 1, overdue: 2 },
    parts: { openRequests: 3, lowStockParts: 1 },
    reports: { awaitingApproval: 1, approved: 4 },
    sites: sites.map((site, index) => ({
      siteId: site.id,
      siteCode: site.code,
      siteName: site.name,
      equipment: { total: 6 + index, operational: 4 + index, underRepair: 1, down: index },
      inspections: { expected: 12, completed: 10, missing: index },
      breakdowns: { open: index },
      reports: { approved: 2 + index },
    })),
  };
}

export function report(id: string, reportRef: string, state: string) {
  return {
    id,
    reportRef,
    siteId: 'site-a',
    siteName: 'Rutongo A',
    reportDate: '2026-11-08',
    period: 'DAY',
    versionId: `${id}-v1`,
    versionNo: 1,
    state,
    preparedBy: 'user-supervisor',
    preparedByName: 'Supervisor One',
    createdAt: '2026-11-08T16:00:00Z',
    updatedAt: '2026-11-08T17:00:00Z',
    dataCutoffAt: '2026-11-08T18:00:00Z',
    snapshot: {
      siteId: 'site-a',
      siteName: 'Rutongo A',
      reportDate: '2026-11-08',
      period: 'DAY',
      dataCutoffAt: '2026-11-08T18:00:00Z',
    },
  };
}

export async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

export async function waitForApp(page: Page, fragment: string) {
  await expect(page.locator('#app-status')).toContainText(fragment);
}

export async function signIn(page: Page, username = 'manager', password = 'Password1!') {
  await waitForApp(page, 'stage=LOGIN');
  await page.waitForTimeout(750);
  await page.mouse.click(640, 345);
  await page.keyboard.type(username);
  await page.keyboard.press('Tab');
  await page.keyboard.type(password);
  await page.keyboard.press('Enter');
}

export function mockSites(page: Page) {
  return page.route('**/api/v1/sites', (route) => json(route, sites));
}

export function mockDashboard(page: Page, kind: string, role: string) {
  return page.route('**/api/v1/dashboard/**', (route) => json(route, dashboard(kind, role)));
}
