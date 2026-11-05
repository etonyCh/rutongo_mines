# Rutongo Mines Mechanical Equipment Management System

Kotlin monolith for mining equipment maintenance workflows. The implementation follows the technical specification in the project root and is being delivered sprint by sprint.

## Current scope

Sprint 0 foundation and Sprint 1 identity/security: Gradle multi-project, shared domain enums, Ktor API, PostgreSQL/Flyway, append-only hash-chained audit events, database outbox table and port, login/refresh/logout/password change, JWT RS256, Argon2id, site-scoped roles, administrator bootstrap, user administration, Docker Compose and CI.

## Run locally

Requirements: JDK 21, Gradle 8.13+, Docker Compose.

```sh
docker compose -f ops/docker-compose.yml up --build
```

The API health endpoint is available at `http://localhost:8080/health`. The initial site records are seeded by Flyway. Set `POSTGRES_PASSWORD` outside local development.

### Bootstrap the first administrator

Start PostgreSQL, then run the server distribution once with `bootstrap-admin`, setting `ADMIN_USERNAME`, `ADMIN_FULL_NAME`, `ADMIN_EMPLOYEE_ID` and `ADMIN_INITIAL_PASSWORD`. The command refuses to run after an administrator exists. The initial password must be changed at first login. Production deployments must also set `APP_ENV=production`, `JWT_PRIVATE_KEY_B64` (PKCS#8 DER, base64) and `JWT_PUBLIC_KEY_B64` (X.509 DER, base64); keys must be managed outside the repository. Development creates an ephemeral RSA key pair on process start, so development access tokens become invalid after a restart.

Authentication routes are under `/api/v1/auth`. Web refresh tokens are issued as Secure, HttpOnly, SameSite=Strict cookies; refresh requests require `X-CSRF-Token` and `X-Requested-With: rutongo-mmems`. Android clients receive an opaque refresh token in the response body.
