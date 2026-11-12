#!/usr/bin/env bash
# Run the CardDemo characterization suite, the Spring tests, and (when spring.sh has an instance up) the
# live drives, and keep the evidence.
# Usage: verify.sh [evidence-dir] [-- extra harness args, e.g. --job POSTTRAN]
set -u
repo="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
scripts="$repo/.cursor/skills/verify-carddemo/scripts"
evidence="${1:-/tmp/carddemo-verify/$(date -u +%Y%m%dT%H%M%SZ)}"
shift $(( $# > 0 ? 1 : 0 ))
[ "${1:-}" = "--" ] && shift
mkdir -p "$evidence"
port="${CARDDEMO_PORT:-8080}"

python3 "$repo/characterization/batch/harness.py" --out "$evidence/batch" "$@" > "$evidence/batch.log" 2>&1
batch_rc=$?
python3 "$repo/characterization/online/validate_specs.py" > "$evidence/online.log" 2>&1
online_rc=$?
(cd "$repo/carddemo-java" && ./gradlew --no-daemon test --rerun-tasks) > "$evidence/spring-test.log" 2>&1
spring_rc=$?
grep '\[spec-replay\] CO[A-Z0-9]*:' "$evidence/spring-test.log" > "$evidence/spec-replay.txt"
grep '\[batch-golden\]' "$evidence/spring-test.log" > "$evidence/batch-golden.txt"

drive="skipped (no spring.sh instance on :$port)"
drive_rc=0
if "$scripts/spring.sh" status "$port" > "$evidence/spring-status.txt" 2>&1; then
  python3 "$scripts/drive_spring.py" --base "http://localhost:$port" --out "$evidence/drive" > "$evidence/drive.log" 2>&1
  http_rc=$?
  python3 "$scripts/browser_drive.py" --base "http://localhost:$port" --out "$evidence/browser" > "$evidence/browser.log" 2>&1
  browser_rc=$?
  drive_rc=$(( http_rc || browser_rc ))
  drive="http rc=$http_rc $(tail -1 "$evidence/drive.log"); browser rc=$browser_rc $(tail -1 "$evidence/browser.log")"
fi
{
  echo "commit  $(git -C "$repo" describe --always --dirty --abbrev=12)"
  echo "batch   rc=$batch_rc  $(tail -1 "$evidence/batch.log")"
  echo "online  rc=$online_rc  $(tail -1 "$evidence/online.log")"
  echo "spring  rc=$spring_rc  $(grep -c . "$evidence/spec-replay.txt") programs replayed, see spec-replay.txt"
  echo "javabat $(grep -o '[0-9]* artifacts: .*' "$evidence/batch-golden.txt" || echo 'no [batch-golden] tally'), see batch-golden.txt"
  echo "drive   $drive"
} | tee "$evidence/summary.txt"
echo "evidence: $evidence"
[ $batch_rc -eq 0 ] && [ $online_rc -eq 0 ] && [ $spring_rc -eq 0 ] && [ $drive_rc -eq 0 ]
