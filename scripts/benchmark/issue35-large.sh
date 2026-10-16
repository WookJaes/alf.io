#!/bin/sh
# 저장소 루트에서 실행. 변경 전 소스는 새 임시 디렉터리에만 복원한다.
set -eu
baseline_ref=f7cb88d5daf1820661af6ee255107718c7167db8
baseline_dir=$(mktemp -d "${TMPDIR:-/tmp}/alfio-issue35-large.XXXXXX")
trap 'rm -rf "$baseline_dir"' EXIT HUP INT TERM
git archive "$baseline_ref" | tar -x -C "$baseline_dir"
for file in \
  scripts/benchmark/issue35.sh \
  scripts/benchmark/issue35.gradle \
  src/test/java/alfio/test/util/IntegrationTestUtil.java \
  src/test/java/alfio/controller/api/v2/user/reservation/benchmark/ReservationDetailBenchmark.java; do
  cp "$file" "$baseline_dir/$file"
done
(
  cd "$baseline_dir"
  scripts/benchmark/issue35.sh baseline sql large
  scripts/benchmark/issue35.sh baseline time large
)
mkdir -p src/test/resources/benchmark/issue35-large build
cp "$baseline_dir"/src/test/resources/benchmark/issue35-large/*.sha256 src/test/resources/benchmark/issue35-large/
cp "$baseline_dir"/build/issue35-large-baseline-*.json build/
cp "$baseline_dir"/build/issue35-large-environment-baseline-*.json build/
for mode in sql time; do
  mkdir -p "build/issue35-large-baseline-$mode"
  cp "$baseline_dir/build/issue35-large-baseline-$mode/"TEST-*.xml "build/issue35-large-baseline-$mode/"
done
scripts/benchmark/issue35.sh optimized sql large
scripts/benchmark/issue35.sh optimized time large
python3 scripts/benchmark/check_issue35.py large
