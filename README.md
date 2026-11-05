# Rutongo Mines Mechanical Equipment Management System

Kotlin monolith for mining equipment maintenance workflows. The implementation follows the technical specification in the project root and is being delivered sprint by sprint.

## Current scope

Sprint 0 foundation, Sprint 1 identity/security and the Sprint 2 organization/equipment/import foundation: Gradle multi-project, Ktor API, PostgreSQL/Flyway, hash-chained audit, login and session rotation, site-scoped roles, organization references, equipment directory and movements, technician/operator references, configurable Excel import profiles, preview/commit/rejection CSV, and private file/blob storage.

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
