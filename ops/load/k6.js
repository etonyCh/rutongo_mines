import http from 'k6/http';
import { check, fail } from 'k6';

const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const USERS = Number(__ENV.LOAD_USERS || 100);
const READ_VUS = Number(__ENV.LOAD_READ_VUS || 90);
const WRITE_VUS = Number(__ENV.LOAD_WRITE_VUS || 10);
const DURATION = __ENV.LOAD_DURATION || '3m';
const PDF_ENABLED = (__ENV.LOAD_PDF || 'true') !== 'false';
const PASSWORD = __ENV.LOAD_PASSWORD || 'LoadTestPassword1';
const REPORT_DATE = __ENV.LOAD_REPORT_DATE || '2001-01-01';

const scenarios = {
  read: {
    executor: 'constant-vus',
    vus: READ_VUS,
    duration: DURATION,
    exec: 'read',
  },
};
if (WRITE_VUS > 0) {
  scenarios.write = {
    executor: 'constant-vus',
    vus: WRITE_VUS,
    duration: DURATION,
    exec: 'write',
  };
}
if (PDF_ENABLED) {
  scenarios.pdf = {
    executor: 'per-vu-iterations',
    vus: 1,
    iterations: 1,
    exec: 'pdf',
  };
}

const thresholds = {
  checks: ['rate>0.99'],
  'http_req_failed{scenario:read}': ['rate<0.01'],
  'http_req_duration{scenario:read}': ['p(95)<1000', 'p(99)<2500'],
};
if (WRITE_VUS > 0) {
  thresholds['http_req_failed{scenario:write}'] = ['rate<0.01'];
  thresholds['http_req_duration{scenario:write}'] = ['p(95)<1500'];
}
if (PDF_ENABLED) {
  thresholds['http_req_failed{scenario:pdf}'] = ['rate<0.01'];
  thresholds['http_req_duration{scenario:pdf}'] = ['p(95)<15000'];
}

export const options = {
  setupTimeout: '10m',
  scenarios,
  thresholds,
};

function authHeaders(token) {
  return { Authorization: `Bearer ${token}` };
}

function pick(items, index) {
  return items[index % items.length];
}

function login(username) {
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username, password: PASSWORD, clientType: 'ANDROID' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  if (response.status !== 200) {
    fail(`login failed for ${username}: ${response.status} ${response.body}`);
  }
  return response.json('accessToken');
}

function get(path, token, endpoint) {
  const response = http.get(`${BASE_URL}${path}`, {
    headers: authHeaders(token),
    tags: { endpoint },
  });
  check(response, { [`${endpoint} returns 200`]: (r) => r.status === 200 });
  return response;
}

export function setup() {
  if (!Number.isInteger(USERS) || USERS < 1) {
    fail(`LOAD_USERS must be a positive integer, got ${__ENV.LOAD_USERS}`);
  }
  const users = [];
  for (let i = 1; i <= USERS; i += 1) {
    const username = `load-supervisor-${String(i).padStart(3, '0')}`;
    users.push({ username, token: login(username) });
  }

  const adminToken = users[0].token;
  const sitesResponse = get('/api/v1/sites', adminToken, 'setup-sites');
  const sites = sitesResponse.json();
  const siteData = sites.map((site) => {
    const equipmentResponse = get(
      `/api/v1/equipment?siteId=${site.id}&limit=200`,
      adminToken,
      'setup-equipment',
    );
    const equipment = equipmentResponse.json();
    return {
      id: site.id,
      code: site.code,
      name: site.name,
      equipmentIds: equipment.map((item) => item.id),
      operationalIds: equipment
        .filter((item) => item.operationalStatus === 'OPERATIONAL')
        .map((item) => item.id),
    };
  });
  const operationalIds = siteData.flatMap((site) => site.operationalIds);
  const pdfSite = siteData.reduce((best, site) =>
    site.equipmentIds.length > best.equipmentIds.length ? site : best,
  );
  if (pdfSite.equipmentIds.length < 150) {
    fail(
      `PDF fixture site ${pdfSite.code} has ${pdfSite.equipmentIds.length} equipment, need at least 150`,
    );
  }
  if (WRITE_VUS > 0 && operationalIds.length < WRITE_VUS) {
    fail(`need at least ${WRITE_VUS} operational equipment, found ${operationalIds.length}`);
  }

  return {
    users,
    sites: siteData,
    operationalIds,
    pdfSite: { id: pdfSite.id, code: pdfSite.code },
  };
}

export function read(data) {
  const user = pick(data.users, __VU - 1);
  const site = pick(data.sites, Math.floor(Math.random() * data.sites.length));
  const equipmentId = pick(site.equipmentIds, Math.floor(Math.random() * site.equipmentIds.length));
  const now = new Date();
  const from = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000).toISOString();
  const to = now.toISOString();
  const roll = Math.random();

  if (roll < 0.18) {
    get(`/api/v1/equipment?siteId=${site.id}&limit=100`, user.token, 'equipment-list');
  } else if (roll < 0.32) {
    get('/api/v1/equipment?q=LOAD-EQ-&limit=50', user.token, 'equipment-search');
  } else if (roll < 0.48) {
    get(`/api/v1/equipment/${equipmentId}`, user.token, 'equipment-detail');
  } else if (roll < 0.62) {
    get(`/api/v1/active-problems?siteId=${site.id}&limit=100`, user.token, 'active-problems');
  } else if (roll < 0.78) {
    get(
      `/api/v1/dashboard/supervisor?site=${site.id}&from=${from}&to=${to}`,
      user.token,
      'dashboard',
    );
  } else if (roll < 0.9) {
    get(`/api/v1/inspections/expected?siteId=${site.id}`, user.token, 'expected-inspections');
  } else {
    get(
      `/api/v1/reports/preview?siteId=${site.id}&reportDate=${REPORT_DATE}&period=FULL_DAY`,
      user.token,
      'report-preview',
    );
  }
}

export function write(data) {
  const user = pick(data.users, __VU - 1);
  const equipmentId = pick(data.operationalIds, __VU - 1);
  const detail = http.get(`${BASE_URL}/api/v1/equipment/${equipmentId}`, {
    headers: authHeaders(user.token),
    tags: { endpoint: 'write-equipment-detail' },
  });
  if (!check(detail, { 'write equipment detail returns 200': (r) => r.status === 200 })) {
    return;
  }
  const version = detail.json('version');
  const key = `load-status-${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(
    `${BASE_URL}/api/v1/equipment/${equipmentId}/status`,
    JSON.stringify({
      status: 'OPERATIONAL',
      remarks: 'Load-test status update',
      occurredAt: new Date().toISOString(),
    }),
    {
      headers: {
        ...authHeaders(user.token),
        'Content-Type': 'application/json',
        'If-Match': String(version),
        'Idempotency-Key': key,
      },
      tags: { endpoint: 'status-update' },
    },
  );
  if (
    !check(response, {
      'status update returns 201': (r) => r.status === 201,
      'status update has no version conflict': (r) => r.status !== 409,
    })
  ) {
    console.log(`status update failed: ${response.status} ${response.body}`);
  }
}

export function pdf(data) {
  const user = data.users[0];
  const site = data.pdfSite;
  const preview = get(
    `/api/v1/reports/preview?siteId=${site.id}&reportDate=${REPORT_DATE}&period=FULL_DAY`,
    user.token,
    'pdf-preview',
  );
  if (preview.status !== 200) return;
  const snapshot = preview.json();
  check(snapshot, {
    'PDF snapshot has at least 150 equipment': (s) => s.equipment.length >= 150,
  });

  const created = http.post(
    `${BASE_URL}/api/v1/reports`,
    JSON.stringify({
      siteId: site.id,
      reportDate: REPORT_DATE,
      period: 'FULL_DAY',
      narrative: {
        executiveSummary: 'Load-test daily report',
        safetyObservations: 'No safety findings from the load fixture.',
        maintenanceSummary: 'Fixture maintenance recorded.',
        partsSummary: 'Fixture parts available.',
        recommendations: 'None.',
        remarks: 'Generated by the load-test harness.',
      },
    }),
    {
      headers: { ...authHeaders(user.token), 'Content-Type': 'application/json' },
      tags: { endpoint: 'report-create' },
    },
  );
  if (
    !check(created, {
      'PDF report create returns 201': (r) => r.status === 201,
    })
  ) {
    console.log(`report create failed: ${created.status} ${created.body}`);
    return;
  }
  const report = created.json();
  const submitted = http.post(
    `${BASE_URL}/api/v1/reports/${report.id}/submit`,
    null,
    {
      headers: {
        ...authHeaders(user.token),
        'If-Match': String(report.versionNo),
      },
      tags: { endpoint: 'report-submit' },
    },
  );
  if (
    !check(submitted, {
      'PDF report submit returns 200': (r) => r.status === 200,
    })
  ) {
    console.log(`report submit failed: ${submitted.status} ${submitted.body}`);
    return;
  }
  const downloaded = http.get(
    `${BASE_URL}/api/v1/reports/${report.id}/pdf?version=${report.versionNo}`,
    {
      headers: authHeaders(user.token),
      responseType: 'binary',
      tags: { endpoint: 'report-pdf' },
    },
  );
  check(downloaded, {
    'PDF download returns 200': (r) => r.status === 200,
    'PDF content type is application/pdf': (r) =>
      (r.headers['Content-Type'] || '').includes('application/pdf'),
    'PDF body is non-empty': (r) => r.body && r.body.byteLength > 1000,
  });
}
