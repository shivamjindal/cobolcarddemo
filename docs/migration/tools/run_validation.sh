#!/usr/bin/env bash
# Throwaway Postgres: apply db/migration in version order, load samples, validate, tear down.
# Needs Postgres server binaries (PG_BIN or /usr/lib/postgresql/*/bin) and psycopg2.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
PG_BIN="${PG_BIN:-$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1)}"
port="${PGPORT:-55439}"
data="$(mktemp -d)"
trap '"$PG_BIN/pg_ctl" -D "$data" stop -m immediate >/dev/null 2>&1 || true; rm -rf "$data"' EXIT
"$PG_BIN/initdb" -D "$data" -A trust >/dev/null
"$PG_BIN/pg_ctl" -D "$data" -o "-p $port -k $data" -l "$data/log" -w start >/dev/null
psql="$PG_BIN/psql -h $data -p $port -q -v ON_ERROR_STOP=1"
$psql -d postgres -c "create database carddemo"
for f in $(ls "$root"/db/migration/V*.sql | sort -V); do
  echo "migrate $(basename "$f")"
  $psql -d carddemo -f "$f"
done
dsn="host=$data port=$port dbname=carddemo"
cd "$here"
python3 load_sample_data.py --dsn "$dsn"
python3 validate_schema.py --dsn "$dsn"
