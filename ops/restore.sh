#!/usr/bin/env sh
set -eu

usage() {
    cat <<'USAGE'
Usage: restore.sh <dump-file> [target-database]

Environment:
  PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD  PostgreSQL connection
  RESTORE_MAINTENANCE_DB                          Maintenance database (default: postgres)
USAGE
}

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
    usage
    exit 0
fi
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    usage >&2
    exit 2
fi

artifact="$1"
target="${2:-${PGDATABASE:-mmems}_restore}"
maintenance_db="${RESTORE_MAINTENANCE_DB:-postgres}"

case "$target" in
'' | *[!A-Za-z0-9_]*)
    echo "target database must contain only letters, digits and underscores" >&2
    exit 2
    ;;
esac

if [ ! -f "$artifact" ]; then
    echo "dump file not found: $artifact" >&2
    exit 1
fi

command -v psql >/dev/null 2>&1 || {
    echo "psql is required" >&2
    exit 1
}
command -v createdb >/dev/null 2>&1 || {
    echo "createdb is required" >&2
    exit 1
}
command -v pg_restore >/dev/null 2>&1 || {
    echo "pg_restore is required" >&2
    exit 1
}

marker="${artifact}.json"
if [ -f "$marker" ]; then
    expected="$(sed -n 's/.*"sha256"[[:space:]]*:[[:space:]]*"\([a-fA-F0-9]\{64\}\)".*/\1/p' "$marker")"
    if [ -n "$expected" ]; then
        actual="$(sha256sum "$artifact")"
        actual="${actual%% *}"
        if [ "$actual" != "$expected" ]; then
            echo "checksum mismatch for $artifact" >&2
            exit 1
        fi
    fi
else
    echo "warning: backup marker not found for $artifact" >&2
fi

if [ "$(psql --dbname="$maintenance_db" --tuples-only --no-align --command="select 1 from pg_database where datname = '$target'")" = "1" ]; then
    echo "target database already exists: $target" >&2
    exit 1
fi

createdb --maintenance-db="$maintenance_db" "$target"
pg_restore --dbname="$target" --no-owner --no-privileges --exit-on-error "$artifact"

printf '%s\n' "$target"
