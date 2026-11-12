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
spring="$(cd "$repo" && ls -d pom.xml build.gradle* settings.gradle* */pom.xml */build.gradle* 2>/dev/null | head -1)"
if [ -n "$spring" ]; then echo "info  JVM build found at $spring; follow the Spring section of SKILL.md"; else echo "info  Spring app not present yet"; fi
exit $fail
