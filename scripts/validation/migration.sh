#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
docker info >/dev/null
printf '검증 기준 HEAD: %s\n' "$(git rev-parse HEAD)"
git diff --stat
validation_temp="$(mktemp -d "${TMPDIR:-/tmp}/alfio-previous.XXXXXX")"
trap 'rm -rf "$validation_temp"' EXIT
previous_commit=2b4759f9136cf5c6f5cb7784c30c9a09da217151
validation_java17="${JAVA17_HOME:-}"
if [[ -z "$validation_java17" && "$(uname -s)" == Darwin ]]; then
    validation_java17="$(/usr/libexec/java_home -v 17)"
fi
if [[ -z "$validation_java17" || ! -x "$validation_java17/bin/java" ]]; then
    printf '이전 릴리스 빌드용 Java 17 경로를 JAVA17_HOME으로 지정하세요.\n' >&2
    exit 1
fi
if ! git cat-file -e "$previous_commit^{commit}" 2>/dev/null; then
    git fetch --no-tags https://github.com/alfio-event/alf.io.git "$previous_commit"
fi
[[ "$(git rev-parse "$previous_commit^{commit}")" == "$previous_commit" ]]
mkdir "$validation_temp/source"
git archive "$previous_commit" | tar -x -C "$validation_temp/source"
# Build the unmodified, pinned previous source using its own Gradle wrapper and JDK.
(
    cd "$validation_temp/source"
    JAVA_HOME="$validation_java17" PATH="$validation_java17/bin:$PATH" ./gradlew --no-daemon bootJar --no-build-cache
)
export VALIDATION_PREVIOUS_JAR="$validation_temp/source/build/libs/alfio-2.0-M5-2606-boot.jar"
export VALIDATION_PREVIOUS_COMMIT="$previous_commit"
export VALIDATION_PREVIOUS_SHA256="$(python3 - "$VALIDATION_PREVIOUS_JAR" <<'PYHASH'
import hashlib
import sys
with open(sys.argv[1], 'rb') as jar:
    digest = hashlib.sha256()
    for chunk in iter(lambda: jar.read(1024 * 1024), b''):
        digest.update(chunk)
    print(digest.hexdigest())
PYHASH
)"
printf 'Previous source commit: %s\nPrevious app SHA-256: %s\n' "$previous_commit" "$VALIDATION_PREVIOUS_SHA256"
rm -rf build/test-results/migrationValidation
./gradlew --no-daemon migrationValidation --no-build-cache -Pverbose
python3 scripts/validation/check-results.py build/test-results/migrationValidation
