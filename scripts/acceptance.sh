#!/usr/bin/env sh
set -eu
[ "${YUELIN_DISPOSABLE_ACCEPTANCE:-}" = "1" ] || { echo 'Explicit disposable acceptance permission required'; exit 1; }
case "${COMPOSE_PROJECT_NAME:-}" in yuelin-ci-*) ;; *) echo 'CI-prefixed disposable Compose project required'; exit 1;; esac
mkdir -p ci-artifacts
sh scripts/docker-smoke-test.sh
status=0
node scripts/acceptance-browser.mjs || status=1
python3 scripts/acceptance-infra.py || status=1
exit "$status"
