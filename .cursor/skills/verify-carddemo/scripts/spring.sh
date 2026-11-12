#!/usr/bin/env bash
# Start, inspect, or stop one local CardDemo Spring instance with its own Postgres container.
# Usage: spring.sh up|status|down [port]   (port defaults to $CARDDEMO_PORT or 8080)
set -euo pipefail
repo="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cmd="${1:-status}"
port="${2:-${CARDDEMO_PORT:-8080}}"
run="/tmp/carddemo-spring/$port"
db="carddemo-pg-$port"
jar="$repo/carddemo-java/build/libs/carddemo-java-0.1.0-SNAPSHOT.jar"

alive() { [ -f "$run/pid" ] && kill -0 "$(cat "$run/pid")" 2>/dev/null; }
health() { curl -fsS "http://localhost:$port/actuator/health" 2>/dev/null; }

case "$cmd" in
  up)
    if alive; then echo "already running: pid $(cat "$run/pid") on :$port"; exit 0; fi
    if curl -fsS -o /dev/null "http://localhost:$port/" 2>/dev/null; then echo "port $port is taken by another process" >&2; exit 1; fi
    mkdir -p "$run"
    docker rm -f "$db" >/dev/null 2>&1 || true
    docker run -d --name "$db" -e POSTGRES_DB=carddemo -e POSTGRES_USER=carddemo -e POSTGRES_PASSWORD=carddemo \
      -p 127.0.0.1::5432 postgres:16-alpine >/dev/null
    db_port="$(docker port "$db" 5432/tcp | head -1 | sed 's/.*://')"
    echo "$db_port" > "$run/db-port"
    until docker exec "$db" pg_isready -U carddemo -d carddemo >/dev/null 2>&1; do sleep 0.5; done
    (cd "$repo/carddemo-java" && ./gradlew --no-daemon -q bootJar)
    git -C "$repo" describe --always --dirty --abbrev=12 > "$run/revision"
    nohup java -jar "$jar" \
      --server.port="$port" \
      --carddemo.base-url="http://localhost:$port" \
      --spring.datasource.url="jdbc:postgresql://localhost:$db_port/carddemo" \
      --carddemo.seed.dir="$repo/app/data/EBCDIC" \
      --management.info.env.enabled=true \
      --info.app.revision="$(cat "$run/revision")" \
      > "$run/app.log" 2>&1 &
    echo $! > "$run/pid"
    for _ in $(seq 1 120); do
      if health | grep -q '"UP"'; then echo "up: http://localhost:$port pid $(cat "$run/pid") db $db:$db_port log $run/app.log"; exit 0; fi
      alive || { echo "app exited; tail of $run/app.log:" >&2; tail -20 "$run/app.log" >&2; exit 1; }
      sleep 1
    done
    echo "not healthy after 120s; see $run/app.log" >&2; exit 1
    ;;
  status)
    alive || { echo "not running on :$port"; exit 1; }
    echo "pid      $(cat "$run/pid")"
    echo "health   $(health || echo unreachable)"
    echo "info     $(curl -fsS "http://localhost:$port/actuator/info" 2>/dev/null || echo unreachable)"
    echo "schema   $(docker exec "$db" psql -U carddemo -d carddemo -tAc 'SELECT max(version::int) FROM flyway_schema_history WHERE success' 2>/dev/null || echo unknown)"
    echo "log      $run/app.log"
    ;;
  down)
    if alive; then kill "$(cat "$run/pid")"; while alive; do sleep 0.2; done; fi
    rm -f "$run/pid"
    docker rm -f "$db" >/dev/null 2>&1 || true
    echo "stopped :$port (log kept at $run/app.log)"
    ;;
  *) echo "usage: spring.sh up|status|down [port]" >&2; exit 2 ;;
esac
