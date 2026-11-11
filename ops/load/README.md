# Load test

The k6 harness exercises the API against a disposable PostgreSQL database with the
Sprint 12 performance target: 1,000 equipment, 100 simultaneous users and a daily
report PDF for a site with at least 150 equipment.

## Quick start

Build the fat jar first, then run the harness:

```sh
gradle :server:shadowJar
ops/load/run.sh
```

The script:

1. starts a temporary `postgres:16-alpine` container on `127.0.0.1:55432`;
2. starts the API on `127.0.0.1:18080`, which runs Flyway migrations;
3. loads `seed.sql` into the disposable `mmems_load` database;
4. runs the k6 container against the API;
5. removes the API process and database container.

Requirements: Docker, Java 21, `curl`, and the built
`server/build/libs/server-all.jar`.

## Load profile

| Scenario | Default | Purpose |
| --- | --- | --- |
| `read` | 90 VUs for 3 minutes | equipment search/list/detail, active problems, supervisor dashboard, expected inspections, report preview |
| `write` | 10 VUs for 3 minutes | status updates with `If-Match` and `Idempotency-Key` |
| `pdf` | 1 iteration | preview, create and submit a daily report for the largest fixture site, then download its PDF |

The fixture creates 100 load supervisors, 1,000 equipment across the six seeded
sites, status history, inspection expectations, breakdowns, maintenance records and
parts. The largest site has at least 150 equipment so the PDF scenario covers the
required volume.

All fixture users have the password `LoadTestPassword1`. The fixture is only for an
isolated, disposable database.

## Thresholds

The run fails when any threshold is breached:

- read p95 latency < 1,000 ms and p99 < 2,500 ms;
- write p95 latency < 1,500 ms;
- PDF scenario p95 < 15,000 ms;
- HTTP failure rate < 1% per scenario and checks > 99%.

## Short smoke run

Use a small profile in CI or for a quick regression check:

```sh
LOAD_READ_VUS=3 LOAD_WRITE_VUS=2 LOAD_USERS=5 LOAD_DURATION=15s ops/load/run.sh
```

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `LOAD_JAR` | `server/build/libs/server-all.jar` | fat jar to run |
| `LOAD_DB_PORT` | `55432` | host port for the disposable database |
| `LOAD_API_PORT` | `18080` | host port for the API |
| `LOAD_READ_VUS` | `90` | read scenario virtual users |
| `LOAD_WRITE_VUS` | `10` | write scenario virtual users |
| `LOAD_USERS` | `100` | fixture accounts used by the scenarios |
| `LOAD_DURATION` | `3m` | read/write scenario duration |
| `LOAD_PDF` | `true` | run the daily-report PDF scenario |
| `LOAD_REPORT_DATE` | `2001-01-01` | report date used by the PDF scenario |
| `LOAD_LOGIN_RATE_LIMIT` | `1000` | API `LOGIN_RATE_LIMIT_PER_MINUTE` override |
| `LOAD_K6_IMAGE` | `grafana/k6:0.54.0` | k6 container image |

The API keeps a production-safe login limit of 10 attempts per minute by default.
The harness raises it only for the disposable load environment so setup can
authenticate all fixture users. Do not raise it on a shared or production
deployment.

The k6 script can also be pointed at an already running API:

```sh
docker run --rm --network host -v "$PWD/ops/load:/scripts:ro" \
  -e BASE_URL=http://127.0.0.1:8080 \
  grafana/k6:0.54.0 run /scripts/k6.js
```

In that case the target database must already contain the fixture rows and its API
must allow the setup logins. The PDF scenario needs a fresh database because a
report can only be created once per site, date and period.
