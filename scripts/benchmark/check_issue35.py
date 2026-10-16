#!/usr/bin/env python3
"""측정 집계에서 이슈 35의 SQL 상한과 비교 조건을 확인한다. 시간 목표는 두지 않는다."""
import json
from pathlib import Path


def read(name):
    return json.loads((Path('build') / name).read_text())


baseline = read('issue35-baseline-sql.json')
optimized = read('issue35-optimized-sql.json')
assert [(r['tickets'], r['categories']) for r in baseline] == [(1, 1), (5, 1), (20, 1), (20, 2), (20, 4)]
assert [(r['tickets'], r['categories']) for r in optimized] == [(r['tickets'], r['categories']) for r in baseline]
# 설정 분류에는 기존 공통 경로 2회가 포함된다. 로더의 카테고리별 설정은 정확히 1회다.
assert len({(r['configurationSql'], r['categorySql'], r['eventCommonSql']) for r in optimized[:3]}) == 1
for r in optimized:
    assert r['configurationSql'] == r['categories'] + 2, r
    assert r['categorySql'] == r['categories'], r
    assert 0 <= r['eventCommonSql'] <= 1, r
for a, b in zip(baseline, optimized):
    assert b['totalSql'] <= a['totalSql']
for phase in ('baseline', 'optimized'):
    times = read(f'issue35-{phase}-time.json')
    assert [(r['tickets'], r['categories']) for r in times] == [(r['tickets'], r['categories']) for r in baseline]
    assert all(r['successes'] == 100 and r['failures'] == 0 for r in times)
    assert all(0 < r['medianMs'] <= r['p95Ms'] for r in times)
for mode in ('sql', 'time'):
    assert read(f'issue35-environment-baseline-{mode}.json') == read(f'issue35-environment-optimized-{mode}.json')
print('5조건 SQL 상한·동일 환경·양 단계 1000회 성공/0실패 확인')
