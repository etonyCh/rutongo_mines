#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
JAR=${LOAD_JAR:-$ROOT_DIR/server/build/libs/server-all.jar}
DB_PORT=${LOAD_DB_PORT:-55432}
API_PORT=${LOAD_API_PORT:-18080}
READ_VUS=${LOAD_READ_VUS:-90}
WRITE_VUS=${LOAD_WRITE_VUS:-10}
LOAD_USERS=${LOAD_USERS:-100}
DURATION=${LOAD_DURATION:-3m}
LOAD_PDF=${LOAD_PDF:-true}
REPORT_DATE=${LOAD_REPORT_DATE:-2001-01-01}
LOGIN_LIMIT=${LOAD_LOGIN_RATE_LIMIT:-1000}
K6_IMAGE=${LOAD_K6_IMAGE:-grafana/k6:0.54.0}

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required to run the load harness" >&2
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "java is required to run the load harness" >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required to run the load harness" >&2
  exit 1
fi
if [ ! -f "$JAR" ]; then
  echo "fat jar not found at $JAR; build it with: gradle :server:shadowJar" >&2
  exit 1
fi

WORK_DIR=$(mktemp -d)
DB_CONTAINER="mmems-load-db-$$"
APP_PID=
APP_LOG="$WORK_DIR/server.log"
BLOB_DIR="$WORK_DIR/blobs"

cleanup() {
  if [ -n "${APP_PID:-}" ]; then
    kill "$APP_PID" >/dev/null 2>&1 || true
    wait "$APP_PID" >/dev/null 2>&1 || true
  fi
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT INT TERM

echo "Starting disposable PostgreSQL on 127.0.0.1:$DB_PORT..."
docker run -d --rm --name "$DB_CONTAINER" \
  -e POSTGRES_DB=mmems_load \
  -e POSTGRES_USER=mmems \
  -e POSTGRES_PASSWORD=mmems \
  -p "127.0.0.1:$DB_PORT:5432" \
  postgres:16-alpine >/dev/null

for _ in $(seq 1 60); do
  if docker exec "$DB_CONTAINER" pg_isready -U mmems -d mmems_load >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

echo "Starting API on 127.0.0.1:$API_PORT..."
APP_ENV=test \
  HSTS_ENABLED=false \
  DATABASE_URL="jdbc:postgresql://127.0.0.1:$DB_PORT/mmems_load" \
  DATABASE_USER=mmems \
  DATABASE_PASSWORD=mmems \
  BLOB_STORAGE_DIR="$BLOB_DIR" \
  LOGIN_RATE_LIMIT_PER_MINUTE="$LOGIN_LIMIT" \
  PORT="$API_PORT" \
  java -jar "$JAR" >"$APP_LOG" 2>&1 &
APP_PID=$!

ready=false
for _ in $(seq 1 120); do
  if curl --fail --silent "http://127.0.0.1:$API_PORT/readyz" >/dev/null 2>&1; then
    ready=true
    break
  fi
  if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
    echo "API exited during startup" >&2
    tail -n 120 "$APP_LOG" >&2
    exit 1
  fi
  sleep 1
done
if [ "$ready" != true ]; then
  echo "API did not become ready" >&2
  tail -n 120 "$APP_LOG" >&2
  exit 1
fi

echo "Seeding load fixtures..."
docker exec -i "$DB_CONTAINER" psql -U mmems -d mmems_load -v ON_ERROR_STOP=1 \
  <"$SCRIPT_DIR/seed.sql" >/dev/null

echo "Running k6: read=$READ_VUS write=$WRITE_VUS users=$LOAD_USERS duration=$DURATION pdf=$LOAD_PDF"
if ! docker run --rm --network host \
  -v "$SCRIPT_DIR:/scripts:ro" \
  -e BASE_URL="http://127.0.0.1:$API_PORT" \
  -e LOAD_USERS="$LOAD_USERS" \
  -e LOAD_READ_VUS="$READ_VUS" \
  -e LOAD_WRITE_VUS="$WRITE_VUS" \
  -e LOAD_DURATION="$DURATION" \
  -e LOAD_PDF="$LOAD_PDF" \
  -e LOAD_REPORT_DATE="$REPORT_DATE" \
  "$K6_IMAGE" run /scripts/k6.js; then
  echo "Load test failed; API log follows:" >&2
  tail -n 120 "$APP_LOG" >&2
  exit 1
fi
