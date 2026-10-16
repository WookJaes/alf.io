#!/bin/sh
# 저장소 루트에서 실행. SQL과 시간 측정은 서로 다른 JVM에서 수행한다.
set -eu
phase=${1:?baseline 또는 optimized}
mode=${2:?sql 또는 time}
suite=${3:-standard}
case "$suite" in standard) prefix=issue35;; large) prefix=issue35-large;; *) exit 2;; esac
case "$phase:$mode" in baseline:sql|baseline:time|optimized:sql|optimized:time) ;; *) exit 2;; esac
./gradlew test --tests 'alfio.controller.api.v2.user.reservation.benchmark.ReservationDetailBenchmark' \
  --console=plain --no-build-cache --rerun-tasks -I scripts/benchmark/issue35.gradle \
  -Dpgsql.version=16.15 -Dbenchmark.suite="$suite" -Dbenchmark.phase="$phase" -Dbenchmark.mode="$mode" \
  -Dbenchmark.reportDir="build/$prefix-$phase-$mode" \
  -Dlogging.level.root=ERROR
