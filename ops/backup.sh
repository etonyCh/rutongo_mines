#!/usr/bin/env sh
set -eu

usage() {
    cat <<'USAGE'
Usage: backup.sh [--help]

Environment:
  PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD  PostgreSQL connection
  BACKUP_DIR                                      Output directory (default: /backups)
  BACKUP_PREFIX                                   Artifact prefix (default: mmems)
USAGE
}

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
    usage
    exit 0
fi
if [ "$#" -gt 0 ]; then
    usage >&2
    exit 2
fi

: "${PGDATABASE:?PGDATABASE is required}"
: "${PGUSER:?PGUSER is required}"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
BACKUP_PREFIX="${BACKUP_PREFIX:-mmems}"

command -v pg_dump >/dev/null 2>&1 || {
    echo "pg_dump is required" >&2
    exit 1
}

umask 077
mkdir -p "$BACKUP_DIR"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
artifact="${BACKUP_DIR}/${BACKUP_PREFIX}-${PGDATABASE}-${timestamp}.dump"
marker="${artifact}.json"
partial="${artifact}.partial"

cleanup() {
    rm -f "$partial" "${marker}.partial"
}
trap cleanup EXIT INT TERM

pg_dump --format=custom --no-owner --no-privileges --file="$partial"
mv "$partial" "$artifact"

checksum="$(sha256sum "$artifact")"
checksum="${checksum%% *}"
completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '{"completedAt":"%s","path":"%s","sha256":"%s"}\n' \
    "$completed_at" "$(basename "$artifact")" "$checksum" > "${marker}.partial"
mv "${marker}.partial" "$marker"

printf '%s\n' "$artifact"
printf '%s\n' "$marker"
