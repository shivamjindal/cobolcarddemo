#!/usr/bin/env bash
# Remove per-case run directories (and with --all, the compiled programs).
# Evidence under characterization/.evidence/ is never touched.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
rm -rf "$root/.build/runs"
if [[ "${1:-}" == "--all" ]]; then
  rm -rf "$root/.build"
fi
echo "cleaned $root/.build${1:+ (all)}; evidence kept in $root/.evidence"
