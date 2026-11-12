#!/usr/bin/env bash
# Read-only readiness check for the CardDemo characterization suite. Exit 0 only if every check passes.
set -u
repo="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
fail=0
check() { if eval "$2" >/dev/null 2>&1; then echo "ok    $1"; else echo "FAIL  $1${3:+ ($3)}"; fail=1; fi; }

check "cobc on PATH" "command -v cobc" "sudo apt-get install -y gnucobol3"
check "GnuCOBOL 3.x" "cobc --version | head -1 | grep -q 'GnuCOBOL) 3\.'" "found: $(cobc --version 2>/dev/null | head -1)"
check "indexed files use BDB" "cobc --info | grep -q 'indexed file handler *: BDB'" "VSAM KSDS emulation needs BDB"
check "python3 with PyYAML" "python3 -c 'import yaml'" "pip install pyyaml"
check "batch goldens present" "test -d '$repo/characterization/batch/golden/POSTTRAN'"
check "online specs present" "ls '$repo'/characterization/online/specs/*.yaml"
check "fixtures present" "test -f '$repo/app/data/ASCII/dailytran.txt'"

stamp="$repo/characterization/batch/build/.inputs-sha256"
if [ -f "$stamp" ]; then echo "info  batch build cached (rebuilds automatically when sources change)"; else echo "info  no batch build yet; first harness run compiles (~3s)"; fi
check "JDK 21" "java -version 2>&1 | grep -q 'version \"21'" "sudo apt-get install -y openjdk-21-jdk"
check "docker reachable (Testcontainers, spring.sh)" "docker info" "start it: (sudo dockerd > /tmp/dockerd.log 2>&1 &); sudo chmod 666 /var/run/docker.sock"
check "Gradle resolves from a reachable Maven repo" "test -f ~/.gradle/init.d/mirror.gradle.kts || curl -fsS -o /dev/null https://repo.maven.apache.org/maven2/" "repo.maven.apache.org is blocked; add the repo1.maven.org init script from SKILL.md"

port="${CARDDEMO_PORT:-8080}"
run="/tmp/carddemo-spring/$port"
if [ -f "$run/pid" ] && kill -0 "$(cat "$run/pid")" 2>/dev/null; then
  pid="$(cat "$run/pid")"
  latest="$(ls "$repo"/carddemo-java/src/main/resources/db/migration/V*__*.sql | sed 's/.*\/V\([0-9]*\)__.*/\1/' | sort -n | tail -1)"
  check "port $port owned by spring.sh pid $pid" "ss -ltnp 2>/dev/null | grep ':$port ' | grep -q 'pid=$pid,'"
  check "health UP on :$port" "curl -fsS http://localhost:$port/actuator/health | grep -q '\"UP\"'"
  check "schema at V$latest" "docker exec carddemo-pg-$port psql -U carddemo -d carddemo -tAc 'SELECT max(version::int) FROM flyway_schema_history WHERE success' | grep -qx '$latest'"
  check "running build is HEAD" "curl -fsS http://localhost:$port/actuator/info | grep -q \"\$(git -C '$repo' describe --always --dirty --abbrev=12)\"" "restart with spring.sh down && spring.sh up"
else
  echo "info  no spring.sh instance on :$port (start one with scripts/spring.sh up $port)"
fi
exit $fail
