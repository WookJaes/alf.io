"""같은 합성 7개 작업의 무게이트/중개 실행 비교. 실제 사용자 승인 시간 측정은 아니다."""
import json
from pathlib import Path
import subprocess
import sys
import time

from test_execution_gate import ExecutionTests, gate


def measure(gated):
    fixture = ExecutionTests()
    fixture.setUp()
    try:
        normal = fixture.request(request_id='normal')
        outside = fixture.request(args={'path': 'outside.txt', 'content': 'bad', 'expected_sha256': None}, request_id='outside')
        protected = fixture.request(args={'path': 'AGENTS.md', 'content': 'bad', 'expected_sha256': None}, request_id='protected')
        missing = fixture.publish('missing')
        approved = fixture.publish('approved')
        fixture.grant(approved)  # 테스트용 서명: 실제 사람의 승인이라고 주장하지 않는다.
        # missing은 승인 범위 밖의 다른 댓글. 유효 승인 1개와 혼동하지 않는다.
        missing['args']['body'] = 'different unapproved comment'
        changed = fixture.publish('changed')
        changed['args']['issue'] = 2
        shell = fixture.request('shell', {'command': 'synthetic marker process'}, 'shell')
        cases = [(normal, False), (outside, True), (protected, True), (missing, True),
                 (approved, False), (changed, True), (shell, True)]
        violations, false_blocks, pending = 0, 0, set()
        started = time.perf_counter()
        for request, forbidden in cases:
            if gated:
                result = fixture.broker.execute(request)
                executed = result['status'] == 'succeeded'
                if result['decision'] == 'approval_required':
                    pending.add(result['approval_scope'])
            else:
                if request['tool'] == 'file.write':
                    (fixture.root / request['args']['path']).write_text(request['args']['content'])
                else:
                    subprocess.run([str(fixture.fake)], check=True, capture_output=True)
                executed = True
            violations += int(forbidden and executed)
            false_blocks += int(not forbidden and not executed)
        return {'cases': len(cases), 'violating_executions': violations,
                'normal_false_blocks': false_blocks,
                'approval_scopes_pending': len(pending), 'synthetic_grants': 1,
                'elapsed_ms': round((time.perf_counter() - started) * 1000, 3)}
    finally:
        fixture.doCleanups()


if __name__ == '__main__':
    result = {'before': measure(False), 'after': measure(True),
              'limits': '합성 사용자 서명·합성 게시 프로세스. 사용자 판단 대기·모델 지연 제외.'}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if result['before']['violating_executions'] != 5 or result['after']['violating_executions'] != 0 or result['after']['normal_false_blocks'] != 0:
        sys.exit(1)
