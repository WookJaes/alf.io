#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
docker info >/dev/null
printf '검증 기준 HEAD: %s\n' "$(git rev-parse HEAD)"
git diff --stat
# Only this dedicated task's reports are removed; product-test reports are preserved.
rm -rf build/test-results/e2eValidation
./gradlew --no-daemon e2eValidation --no-build-cache -Pverbose
python3 scripts/validation/check-results.py build/test-results/e2eValidation
