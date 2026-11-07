# Rutongo Mines Mechanical Equipment Management System

Kotlin monolith for mining equipment maintenance workflows. The implementation follows the technical specification in the project root and is being delivered sprint by sprint.

## Current scope

Sprint 0 foundation, Sprint 1 identity/security, Sprint 2 organization/equipment/import, Sprint 3 status/history, Sprint 4 inspection backend, Sprint 5 offline Android client and Sprint 6 breakdown management: Gradle multi-project, Ktor API, PostgreSQL/Flyway, hash-chained audit, login and session rotation, site-scoped roles, organization references, equipment directory and movements, configurable Excel imports, status event history and optimistic status updates, Active Problems, a site-scoped SSE event stream, daily inspection expectations, shift-aware deadlines, immutable inspection submissions, defects, corrections, checklist administration, automatic critical-safety status rules, breakdown reporting and repair transitions, defect-to-breakdown links, breakdown photo evidence and overlap-aware downtime calculation.

The Android client is offline-first: encrypted tokens and database keys, SQLCipher persistence, a WorkManager outbox with idempotency keys and conflict recovery, cached equipment references and signed-in profile, status updates, a resumable inspection wizard, engine-hour readings, CameraX photo evidence with client compression, and sync-issue review. The test suites cover inspection validation, submission idempotency, late submissions, critical failures, engine-hour properties, expectation generation, missing marking, shift resolution, photo references, supervisor/manager overrides, offline status failures, stale-status conflicts, photo retry, inspection draft completion, breakdown idempotency and active uniqueness, repair transitions, overlap-aware downtime, defect links and breakdown photo scoping.

## Run locally

Requirements: JDK 21, Gradle 8.13+, Docker Compose.

```sh
docker compose -f ops/docker-compose.yml up --build
```

The API health endpoint is available at `http://localhost:8080/health`. The initial site records are seeded by Flyway. Set `POSTGRES_PASSWORD` outside local development.

### Bootstrap the first administrator

Start PostgreSQL, then run the server distribution once with `bootstrap-admin`, setting `ADMIN_USERNAME`, `ADMIN_FULL_NAME`, `ADMIN_EMPLOYEE_ID` and `ADMIN_INITIAL_PASSWORD`. The command refuses to run after an administrator exists. The initial password must be changed at first login. Production deployments must also set `APP_ENV=production`, `JWT_PRIVATE_KEY_B64` (PKCS#8 DER, base64) and `JWT_PUBLIC_KEY_B64` (X.509 DER, base64); keys must be managed outside the repository. Development creates an ephemeral RSA key pair on process start, so development access tokens become invalid after a restart.

Authentication routes are under `/api/v1/auth`. Web refresh tokens are issued as Secure, HttpOnly, SameSite=Strict cookies; refresh requests require `X-CSRF-Token` and `X-Requested-With: rutongo-mmems`. Android clients receive an opaque refresh token in the response body.

Administrators create import profiles at `/api/v1/import-profiles`, mapping domain fields to the actual spreadsheet headers. Upload `.xlsx` files to `/api/v1/imports` as multipart fields `profileId` and `file`; inspect `/api/v1/imports/{id}`, then commit with `applyUpdates` as needed. Preview is dry-run against business tables, unknown columns are preserved, rejected rows can be downloaded as CSV, and repeat imports classify already-applied rows as unchanged. Files are limited to 20 MiB.

Status updates use `POST /api/v1/equipment/{id}/status` with `If-Match` set to the equipment version and an `Idempotency-Key` header. The stored statuses are `OPERATIONAL`, `UNDER_REPAIR` and `DOWN`; `OPERATIONAL_WITH_DEFECTS` is derived for display and cannot be written. Events are append-only and site-scoped, and a late event is retained in history without regressing the current equipment projection. Repeating an `Idempotency-Key` returns the original event without appending another.

Status history is available at `/api/v1/equipment/{id}/status-events`; active `DOWN` and `UNDER_REPAIR` equipment is listed at `/api/v1/active-problems`, optionally filtered with `siteId`. Authenticated clients can subscribe to `/api/v1/events` using Server-Sent Events; send `Last-Event-ID` as `<recordedAt>|<eventId>` to resume after the last delivered event. Streams only include events for sites the caller may access.

Inspections are listed at `/api/v1/inspections/expected` and `/api/v1/inspections/control`, submitted through `POST /api/v1/inspections`, and loaded with frozen answers and defects at `/api/v1/inspections/{id}`. Drafts use `PUT /api/v1/inspection-drafts/{equipmentId}`. Checklist items, corrections and defects are exposed under `/api/v1/checklist-items`, `/api/v1/inspections/{id}/corrections`, `/api/v1/corrections/{id}/decision` and `/api/v1/defects`. Expectation generation and missing marking run on a persistent `db-scheduler` schedule and are idempotent at the database level. A critical safety failure triggers the administrable `AUTO_DOWN` status rule; reversing that projected `DOWN` requires `STATUS_OVERRIDE` and a stored override reason.

Breakdowns are reported through `POST /api/v1/breakdowns` with an `Idempotency-Key`, listed at `/api/v1/breakdowns`, and moved through the repair lifecycle with `POST /api/v1/breakdowns/{id}/transition` and `If-Match`. Only one active breakdown is allowed per equipment. Repairs record start and end timestamps, and downtime is calculated from the union of overlapping incidents, clipped to the reporting period and cutoff. Defects can be linked to a breakdown on the same equipment, either at creation or through `PATCH /api/v1/defects/{id}`. Photos with target type `BREAKDOWN` are validated against the breakdown site and require `BREAKDOWN_MANAGE`.

### Build the Android client

Requirements: Android SDK platform 36 and JDK 21. The debug build uses the local SDK configured through `ANDROID_HOME` or `local.properties`.

```sh
gradle :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

The debug APK is written to `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Physical-device and emulator acceptance still requires a connected Android target; no device is required for the JVM/Robolectric suite or the APK build.

The current HTTP contract is documented in [`docs/openapi.yaml`](docs/openapi.yaml). It covers authentication, users, organization, equipment, status, import, inspection, defect, breakdown and photo endpoints, including `If-Match`, `Idempotency-Key`, error responses and SSE resumption headers. CI runs the full Gradle build, validates the contract with Redocly and publishes the generated reference and debug APK as build artifacts. CI also enforces `ktlint`, `detekt` with per-module baselines, Konsist architecture rules and Kover coverage thresholds.
