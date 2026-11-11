# Rutongo Mines Mechanical Equipment Management System

Kotlin monolith for mining equipment maintenance workflows. The implementation follows the technical specification in the project root and is being delivered sprint by sprint.

## Current scope

Sprint 0 foundation, Sprint 1 identity/security, Sprint 2 organization/equipment/import, Sprint 3 status/history, Sprint 4 inspection backend, Sprint 5 offline Android client, Sprint 6 breakdown management, Sprint 7 maintenance/hours, Sprint 8 parts/personnel, Sprint 9 daily reports, Sprint 10 dashboards/notifications/exports and Sprint 11 web client: Gradle multi-project, Ktor API, PostgreSQL/Flyway, hash-chained audit, login and session rotation, site-scoped roles, organization references, equipment directory and movements, configurable Excel imports, status event history and optimistic status updates, Active Problems, a site-scoped SSE event stream, daily inspection expectations, shift-aware deadlines, immutable inspection submissions, defects, corrections, checklist administration, automatic critical-safety status rules, breakdown reporting and repair transitions, defect-to-breakdown links, breakdown photo evidence, overlap-aware downtime calculation, hour-based service intervals with verified-service due status, supervisor maintenance verification, union-based working-hours reporting, a parts catalogue with immutable stock movements, the part-request approval/issue/use/return workflow, low-stock notifications, versioned technician/operator directories, equipment assignments, personnel transfer history, read-only daily report snapshots, immutable report versions, site-scoped PDF generation, role-based dashboards, outbox-driven notifications and audited CSV/XLSX exports.

The web client is a Compose Multiplatform Wasm application for supervisors, managers, general management and administration. Mechanics use the Android client for inspections, tasks and offline work. The web client provides role-filtered navigation, management and executive dashboards with site comparison, daily report history and PDF download, equipment search, user administration with an audit trail, notification-rule configuration and controlled XLSX imports plus dataset exports. It is online-only and uses the same shared DTOs and navigation rules as Android. The web refresh token stays in an HttpOnly cookie; refresh calls are CSRF-protected and the API CORS allow-list is configured with `WEB_ORIGINS`.

The Android client is offline-first: encrypted tokens and database keys, SQLCipher persistence, a WorkManager outbox with idempotency keys and conflict recovery, cached equipment and maintenance references, status updates, a resumable inspection wizard, engine-hour readings, CameraX photo evidence with client compression, breakdown reporting with repair transitions, maintenance recording and supervisor decisions, service schedules, working-hours summaries, and sync-issue review. The test suites cover inspection validation, submission idempotency, late submissions, critical failures, engine-hour properties, expectation generation, missing marking, shift resolution, photo references, supervisor/manager overrides, offline status failures, stale-status conflicts, photo retry, inspection draft completion, offline breakdown creation and transition ordering, breakdown idempotency and active uniqueness, repair transitions, overlap-aware downtime, defect links, breakdown photo scoping, maintenance idempotency and verification decisions, cost visibility, service catalog versioning, service-due boundaries, higher-service interval reset semantics, union-based working-hours aggregation, Android maintenance outbox caching, parts request idempotency, stock invariants, low-stock deduplication, personnel scoping, assignment replacement, transfer history, daily report snapshots, PDF rendering, report versioning and approval authorization.

## Run locally

Requirements: JDK 21, Gradle 8.13+, Docker Compose.

```sh
docker compose -f ops/docker-compose.yml up --build
```

The API health endpoint is available at `http://localhost:8080/health`, and the Compose `web` service serves the compiled web client at `http://localhost:8081`. The initial site records are seeded by Flyway. Set `POSTGRES_PASSWORD` outside local development.

### Bootstrap the first administrator

Start PostgreSQL, then run the server distribution once with `bootstrap-admin`, setting `ADMIN_USERNAME`, `ADMIN_FULL_NAME`, `ADMIN_EMPLOYEE_ID` and `ADMIN_INITIAL_PASSWORD`. The command refuses to run after an administrator exists. The initial password must be changed at first login. Production deployments must also set `APP_ENV=production`, `JWT_PRIVATE_KEY_B64` (PKCS#8 DER, base64) and `JWT_PUBLIC_KEY_B64` (X.509 DER, base64); keys must be managed outside the repository. Development creates an ephemeral RSA key pair on process start, so development access tokens become invalid after a restart.

Authentication routes are under `/api/v1/auth`. Web refresh tokens are issued as Secure, HttpOnly, SameSite=Strict cookies; refresh requests require `X-CSRF-Token` and `X-Requested-With: rutongo-mmems`. Android clients receive an opaque refresh token in the response body.

FCM HTTP v1 uses the `FCM_CREDENTIALS` environment variable, which may contain the service-account JSON itself or a path to it. `LOGIN_RATE_LIMIT_PER_MINUTE` controls login attempts per minute for each IP and username and defaults to 10. Set `BACKUP_VERIFICATION_MARKER` to a JSON file with `completedAt` (ISO-8601), optional `path` (relative to the marker file) and optional `sha256`; the job rejects missing, stale or checksum-mismatched backups. `BACKUP_VERIFICATION_MAX_AGE_HOURS` defaults to 48. If no marker is configured, the backup job logs a warning without failing the scheduler.

Administrators create import profiles at `/api/v1/import-profiles`, mapping domain fields to the actual spreadsheet headers. Upload `.xlsx` files to `/api/v1/imports` as multipart fields `profileId` and `file`; inspect `/api/v1/imports/{id}`, then commit with `applyUpdates` as needed. Preview is dry-run against business tables, unknown columns are preserved, rejected rows can be downloaded as CSV, and repeat imports classify already-applied rows as unchanged. Files are limited to 20 MiB.

Status updates use `POST /api/v1/equipment/{id}/status` with `If-Match` set to the equipment version and an `Idempotency-Key` header. The stored statuses are `OPERATIONAL`, `UNDER_REPAIR` and `DOWN`; `OPERATIONAL_WITH_DEFECTS` is derived for display and cannot be written. Events are append-only and site-scoped, and a late event is retained in history without regressing the current equipment projection. Repeating an `Idempotency-Key` returns the original event without appending another.

Status history is available at `/api/v1/equipment/{id}/status-events`; active `DOWN` and `UNDER_REPAIR` equipment is listed at `/api/v1/active-problems`, optionally filtered with `siteId`. Authenticated clients can subscribe to `/api/v1/events` using Server-Sent Events for equipment status and notification events; send the previous `Last-Event-ID` to resume. Streams only include events for sites the caller may access; legacy status cursors using `<recordedAt>|<eventId>` remain accepted.

Inspections are listed at `/api/v1/inspections/expected` and `/api/v1/inspections/control`, submitted through `POST /api/v1/inspections`, and loaded with frozen answers and defects at `/api/v1/inspections/{id}`. Drafts use `PUT /api/v1/inspection-drafts/{equipmentId}`. Checklist items, corrections and defects are exposed under `/api/v1/checklist-items`, `/api/v1/inspections/{id}/corrections`, `/api/v1/corrections/{id}/decision` and `/api/v1/defects`. Expectation generation and missing marking run on a persistent `db-scheduler` schedule and are idempotent at the database level. A critical safety failure triggers the administrable `AUTO_DOWN` status rule; reversing that projected `DOWN` requires `STATUS_OVERRIDE` and a stored override reason.

Breakdowns are reported through `POST /api/v1/breakdowns` with an `Idempotency-Key`, listed at `/api/v1/breakdowns`, and moved through the repair lifecycle with `POST /api/v1/breakdowns/{id}/transition` and `If-Match`. Only one active breakdown is allowed per equipment. Repairs record start and end timestamps, and downtime is calculated from the union of overlapping incidents, clipped to the reporting period and cutoff. Defects can be linked to a breakdown on the same equipment, either at creation or through `PATCH /api/v1/defects/{id}`. Photos with target type `BREAKDOWN` are validated against the breakdown site and require `BREAKDOWN_MANAGE`. Android reports and repair updates are queued offline; breakdown creation is sent before dependent transitions, which are repointed to the server ID with an adjusted `If-Match` version.

Maintenance records are created at `POST /api/v1/maintenance` with an `Idempotency-Key`, listed at `GET /api/v1/maintenance`, and decided by a supervisor through `/verify`, `/reject` and `/request-correction` with `If-Match`. Mechanics and supervisors record work; managers, general managers and administrators can list records through `MAINTENANCE_VIEW`. Cost fields are nullable and returned only to callers with `COST_VIEW`. Only verified maintenance resets service intervals. `GET /api/v1/service-schedule`, `GET /api/v1/equipment/{id}/service-status` and the schedule endpoint use the last verified service, including higher services that satisfy lower interval types through `service_type.satisfies_type_ids`. Administrators configure service types and intervals through `/api/v1/service-types` and `/api/v1/service-intervals` with `CONFIG_MANAGE` and `If-Match` version checks. `GET /api/v1/working-hours` reports raw entries and grouped totals calculated from the union of meter intervals, never by summing raw readings.

Parts are managed through `GET /api/v1/parts`, `GET /api/v1/parts/{id}`, `POST /api/v1/parts` and `PUT /api/v1/parts/{id}` with `PART_MANAGE` and `If-Match` for edits. Mechanics raise requests at `POST /api/v1/part-requests` with an `Idempotency-Key`; supervisors and managers approve or reject with `PART_APPROVE`, and issue with `PART_ISSUE`. Creating or approving a request never changes stock. Stock only moves through immutable `stock_movement` rows: issuing records negative `ISSUE` movements, returning records positive `RETURN` movements, and administrators record receipts or adjustments with `PART_MANAGE`. `GET /api/v1/stock-movements` exposes the ledger and `GET /api/v1/parts/low-stock` returns parts below their minimum, with one `LOW_STOCK` outbox message per part and day. Unit costs remain hidden unless the caller has `COST_VIEW`.

Personnel administration uses `GET/POST /api/v1/technicians`, `PUT /api/v1/technicians/{id}`, the equivalent operator routes, `GET/POST /api/v1/assignments` and `GET/POST /api/v1/transfers`. Reads require `PERSONNEL_VIEW` and are site-scoped; writes require `PERSONNEL_MANAGE`. New equipment assignments close the previous open holder, and site transfers are immutable records that close open assignments when the person leaves the site.

Daily reports are previewed at `GET /api/v1/reports/preview`, created at `POST /api/v1/reports`, edited at `PUT /api/v1/reports/{id}/narrative`, listed at `GET /api/v1/reports`, read at `GET /api/v1/reports/{id}` and downloaded from `GET /api/v1/reports/{id}/pdf`. Supervisors prepare and submit reports with `REPORT_PREPARE`; managers approve or return them with `REPORT_APPROVE` and `If-Match`. Report snapshots are captured in a read-only repeatable-read transaction and never modify operational data. Draft and returned PDFs are regenerated from a fresh snapshot with a `DRAFT` watermark. Submitting freezes the snapshot, narrative and stored PDF; approval requires a different user and replaces the PDF with the approved, watermarked copy. Stored PDFs are verified against their SHA-256 fingerprint on every download, and every download is audited. Returned reports are corrected in place, while an approved report is corrected through `/new-version`, which supersedes the old version and creates a new draft. PDFs use the embedded DejaVu fonts and the filename `RUTONGO_MINES_DAILY_REPORT_{SITE}_{DATE}_{PERIOD}.pdf`.

Sprint 10 adds role dashboards at `GET /api/v1/dashboard/{mechanic|supervisor|manager|executive}` with optional `site`, `from` and `to` filters. Dashboard totals are derived from the same operational tables as their detail endpoints and cost fields remain hidden without `COST_VIEW`. Notifications are produced from the transactional outbox by a persistent `db-scheduler` job, with hourly service-due and low-stock scans feeding the same outbox. Users read them at `GET /api/v1/notifications`, mark one or all read, and register a push token at `POST /api/v1/notifications/devices`. Administrators manage routing at `/api/v1/notification-rules`. Push delivery uses the FCM HTTP v1 API and is disabled unless `FCM_CREDENTIALS` contains service-account JSON or a path to a service-account JSON file; in-app notifications and the SSE stream continue to work without FCM. Daily maintenance jobs clean old refresh tokens and rate-limit buckets, verify the audit hash chain and verify a backup marker described below. Exports are available at `GET /api/v1/exports/{dataset}.xlsx` or `.csv`, require `EXPORT_DATA`, enforce site and cost visibility, and write a `DATA_EXPORTED` audit event. Datasets are `equipment`, `inspections`, `defects`, `breakdowns`, `maintenance`, `part-requests`, `working-hours`, `reports`, `parts` and `stock-movements`.

### Build the Android client

Requirements: Android SDK platform 36 and JDK 21. The debug build uses the local SDK configured through `ANDROID_HOME` or `local.properties`.

```sh
gradle :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

The debug APK is written to `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Physical-device and emulator acceptance still requires a connected Android target; no device is required for the JVM/Robolectric suite or the APK build.

### Build and run the web client

The web client is built into `webApp/build/dist/wasmJs/productionExecutable`. Serve that directory on port 8081 so it matches the default API CORS origins, and keep the API on port 8080.

```sh
gradle :webApp:build
python3 -m http.server 8081 --directory webApp/build/dist/wasmJs/productionExecutable
```

For the containerised route, `docker compose -f ops/docker-compose.yml up --build` builds the Wasm distribution and serves it through nginx on `http://localhost:8081`. The nginx configuration in `ops/nginx.conf` adds CSP, HSTS, frame denial, nosniff, referrer policy, permissions policy and cross-origin isolation headers. If you serve the distribution with another host, reproduce those headers there.

Open `http://localhost:8081`. The client targets `http://localhost:8080` by default. Override the API URL with `?api=https://api.example.com` or by defining `globalThis.RUTONGO_API_BASE` in `config.js` before the application script loads. The default CSP permits API calls to the same origin, `http://localhost:8080`, `http://127.0.0.1:8080` and HTTPS origins; add any other HTTP API origin to `connect-src` in both `index.html` and the production host headers. Add the web origin to `WEB_ORIGINS` (comma-separated) and set `COOKIE_SECURE=true` behind HTTPS in production.

The Playwright suite in `webApp/e2e` serves the production distribution on port 8091, mocks the API, and covers manager login from an expired session, manager dashboard/site comparison, supervisor navigation and the reports route. It requires a built distribution.

```sh
cd webApp/e2e
npm ci
npx playwright install chromium
npm run test:e2e
```

Set `PLAYWRIGHT_CHROMIUM_EXECUTABLE` to use an existing Chromium or Chrome binary. CI installs the Playwright browser, runs the suite after the Gradle build and uploads traces/screenshots when it fails.

### Run the load test

Build the fat jar and run the disposable k6 harness:

```sh
gradle :server:shadowJar
ops/load/run.sh
```

It starts a temporary PostgreSQL database and API, seeds 1,000 equipment and 100 supervisors, then runs 90 read users, 10 status-writing users and a 150-equipment daily-report PDF scenario against the API. See [`ops/load/README.md`](ops/load/README.md) for thresholds, the short smoke profile and configuration. The harness raises the API login limit only inside the disposable load environment.

The current HTTP contract is documented in [`docs/openapi.yaml`](docs/openapi.yaml). It covers authentication, users, organization, personnel, equipment, status, import, inspection, defect, breakdown, maintenance, working hours, parts, stock, report, photo, dashboard, notification and export endpoints, including `If-Match`, `Idempotency-Key`, error responses and SSE resumption headers. CI runs the full Gradle build, validates the contract with Redocly and publishes the generated reference and debug APK as build artifacts. CI also enforces `ktlint`, `detekt` with per-module baselines, Konsist architecture rules and Kover coverage thresholds.
