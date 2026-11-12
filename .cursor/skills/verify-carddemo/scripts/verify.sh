#!/usr/bin/env bash
# Run the CardDemo characterization suite and keep the evidence.
# Usage: verify.sh [evidence-dir] [-- extra harness args, e.g. --job POSTTRAN]
set -u
repo="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
evidence="${1:-/tmp/carddemo-verify/$(date -u +%Y%m%dT%H%M%SZ)}"
shift $(( $# > 0 ? 1 : 0 ))
[ "${1:-}" = "--" ] && shift
mkdir -p "$evidence"

python3 "$repo/characterization/batch/harness.py" --out "$evidence/batch" "$@" > "$evidence/batch.log" 2>&1
batch_rc=$?
python3 "$repo/characterization/online/validate_specs.py" > "$evidence/online.log" 2>&1
online_rc=$?
{
  echo "commit  $(git -C "$repo" describe --always --dirty --abbrev=12)"
  echo "batch   rc=$batch_rc  $(tail -1 "$evidence/batch.log")"
  echo "online  rc=$online_rc  $(tail -1 "$evidence/online.log")"
} | tee "$evidence/summary.txt"
echo "evidence: $evidence"
[ $batch_rc -eq 0 ] && [ $online_rc -eq 0 ]
