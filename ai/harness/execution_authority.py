"""사람이 별도 터미널에서 정책을 고정하고 정확한 요청에 승인 서명을 발급한다.

에이전트 도구로 등록하지 않는다. 같은 OS 사용자의 직접 셸을 격리하는 장치가 아니다.
"""
import argparse
import hashlib
import hmac
import os
from pathlib import Path
import sys
import time

from execution_gate import Broker, Invalid, digest, encoded, ident, load, save, validate_policy


def initialize(directory, policy):
    directory = Path(directory).absolute()
    policy = validate_policy(policy)
    root = Path(policy['root'])
    if directory == root or root in directory.resolve().parents:
        raise Invalid('정책·승인 경로는 개발 저장소 밖에 필요')
    directory.mkdir(mode=0o700, parents=False, exist_ok=False)
    (directory / 'key').write_bytes(os.urandom(32))
    (directory / 'key').chmod(0o600)
    save(directory / 'policy.json', policy)
    save(directory / 'anchor.json', {'policy_sha256': digest(policy)})
    save(directory / 'approvals.json', [])
    save(directory / 'journal.json', {})


def approve(broker, request, reference, confirm):
    """confirm은 사람용 터미널 UI. broker 요청에서는 호출할 수 없다."""
    import fcntl
    ident(reference)
    with (broker.directory / 'lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        broker = Broker(broker.directory)
        context = broker.context(request)
        if request['tool'] not in broker.policy['approval_required']:
            raise Invalid('이 동작은 별도 승인 불필요')
        if broker.approval(context):
            return False  # 유효한 동일 범위 재질문 없음
        if not confirm(context, request):
            raise Invalid('사용자 승인 취소')
        if context != broker.context(request):
            raise Invalid('승인 대기 중 대상 변경: 다시 검토 필요')
        payload = {'scope': digest(context), 'reference': reference, 'expires': time.time() + 3600}
        signature = hmac.new(broker.key, encoded(payload), hashlib.sha256).hexdigest()
        records = load(broker.directory / 'approvals.json')
        records.append({'payload': payload, 'signature': signature})
        save(broker.directory / 'approvals.json', records)
        return True


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--authority', required=True)
    sub = parser.add_subparsers(dest='action', required=True)
    sub.add_parser('init').add_argument('policy')
    p = sub.add_parser('approve')
    p.add_argument('request')
    p.add_argument('--reference', required=True)
    args = parser.parse_args(argv)
    try:
        # stdin의 "승인됨"·--yes·환경 변수는 승인 입력으로 받지 않는다.
        with open('/dev/tty', 'r+', encoding='utf-8') as terminal:
            if args.action == 'init':
                policy = validate_policy(load(args.policy))
                terminal.write('정책과 도구 실행 파일을 검토하십시오.\n' + encoded(policy).decode() + '\n고정하려면 TRUST 입력: ')
                terminal.flush()
                if terminal.readline().strip() != 'TRUST':
                    raise Invalid('정책 고정 취소')
                initialize(args.authority, policy)
            else:
                def confirm(context, request):
                    # 인자에 제어 문자·ANSI가 있어도 터미널에 실행하지 않고 이스케이프해서 표시.
                    import json
                    terminal.write(json.dumps({'scope': context, 'request': request}, ensure_ascii=True,
                                              indent=2) + '\n이 범위에 동의하면 APPROVE 입력: ')
                    terminal.flush()
                    return terminal.readline().strip() == 'APPROVE'
                approve(Broker(args.authority), load(args.request), args.reference, confirm)
        print('정책·승인 처리 완료')
        return 0
    except (Invalid, OSError, ValueError, TypeError, KeyError):
        print('정책·승인 처리 거부: 별도 사용자 터미널과 선언·현재 대상 확인', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
