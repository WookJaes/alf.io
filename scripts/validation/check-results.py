#!/usr/bin/env python3
"""전용 실행의 결과가 실제 테스트 실행인지 확인한다. 원본 결과는 커밋하지 않는다."""
import pathlib
import sys
import xml.etree.ElementTree as ET

reports = list(pathlib.Path(sys.argv[1]).glob('TEST-*.xml'))
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for report in reports:
    suite = ET.parse(report).getroot()
    for key in counts:
        counts[key] += int(suite.get(key, '0'))
print(counts)
if counts['tests'] < 1 or counts['skipped'] or counts['failures'] or counts['errors']:
    sys.exit('실제 테스트 0건, 스킵 또는 실패: 검증 실패')
