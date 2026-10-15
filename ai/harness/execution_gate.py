"""실행 전 중개 도구. 검증 완료 판정(run.py)과 별개이며 POSIX에서 동작한다."""
import argparse
from contextlib import contextmanager
import hashlib
import hmac
import json
import os
from pathlib import Path, PureWindowsPath
import re
import stat
import subprocess
import sys
import time
import unicodedata

VERSION = 1
TOOLS = {'file.write', 'git.commit', 'git.push', 'github.issue_comment'}
SENSITIVE = TOOLS - {'file.write'}
PROTECTED = ['.git', '.codex', '.agents', '.github', 'AGENTS.md', '.gitignore',
             '.gitattributes', '.gitmodules', 'ai/harness', 'ai/WORKFLOW.md',
             'docs/conventions/git.md']
MAX_INPUT = 1024 * 1024
WORK_BRANCH = r'(?:feat|fix|docs|test|refactor|chore)/(?:[1-9][0-9]*-)?[a-z][a-z0-9]*(?:-[a-z0-9]+)*'
REASONS = {
    'scope': '작업·디렉터리·대상이 허용 범위와 다릅니다. 선언한 범위로 요청하십시오.',
    'protected': '보호 경로입니다. 일반 작업으로 수정할 수 없습니다.',
    'path': '경로 탈출·별칭·링크·특수 파일은 지원하지 않습니다. 일반 상대 파일을 사용하십시오.',
    'schema': '인자가 잘못됐습니다. 지원되는 구조화 요청으로 다시 작성하십시오.',
    'repository': '실제 저장소·원격 대상 확인에 실패했습니다. origin과 저장소를 확인하십시오.',
    'stale': '대상이 변경됐습니다. 현재 상태를 읽고 요청·승인 범위를 다시 확인하십시오.',
    'replay': '같은 ID의 인자가 다릅니다. 이전 결과를 확인하십시오.',
    'approval': '승인 서명·형식 오류입니다. 사용자 승인 경로에서 확인하십시오.',
    'explicit_user_approval': '유효한 사용자 승인이 없습니다. 별도 사용자 터미널에서 승인 후 같은 요청을 재개하십시오.',
    'scope_and_approval': '범위·필요한 승인을 확인해 대상 도구에 전달했습니다.',
    'unsupported': '임의 셸·복합 명령·PR 본문 수정·미지원 도구는 전달하지 않습니다.',
    'integration': '연동·정책·입력을 확인할 수 없어 실행하지 않았습니다. 설정을 확인하십시오.',
    'invalid': '판정에 실패해 실행하지 않았습니다. 선언과 기록을 확인하십시오.',
    'delivery_uncertain_do_not_retry': '전달 결과 또는 기록이 불명확합니다. 실제 대상을 확인하기 전 재시도하지 마십시오.',
}


def tool_environment():
    # 호출자가 Git 경로·index나 GitHub host를 바꿔 대상 검사를 우회하지 못한다.
    return {k: v for k, v in os.environ.items()
            if not k.startswith('GIT_') and k not in {'GH_HOST', 'GH_REPO', 'GH_DEBUG'}}


class Invalid(ValueError):
    pass


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(',', ':')).encode('utf-8')


def digest(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def file_hash(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def save(path, value):
    path = Path(path)
    temporary = path.with_suffix('.tmp')
    with temporary.open('w', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    # 파일 데이터와 교체된 디렉터리 항목의 영속화는 별개다.
    # 이 동기화가 실패하면 호출자에게 예외를 전달해 도구 실행을 막는다.
    directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def fields(value, required):
    if not isinstance(value, dict) or set(value) != set(required):
        raise Invalid('schema: 누락·미지원 인자')


def ident(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', value):
        raise Invalid('schema: 잘못된 ID')
    return value


def relative(value):
    if (not isinstance(value, str) or not value or '\\' in value or '\x00' in value
            or Path(value).is_absolute() or PureWindowsPath(value).anchor
            or any(p in {'', '.', '..'} for p in value.split('/'))):
        raise Invalid('path: 정규 상대 경로 필요')
    return value


def folded(value):
    return unicodedata.normalize('NFD', value).casefold()


def within(name, scope):
    name, scope = folded(name), folded(scope)
    return name == scope or name.startswith(scope + '/')


def canonical_repository(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', value):
        raise Invalid('repository: owner/name 필요')
    return value


def validate_policy(policy):
    fields(policy, {'version', 'task_id', 'run_id', 'root', 'repository', 'allowed_paths',
                    'protected_paths', 'approval_required', 'binaries'})
    if policy['version'] != VERSION:
        raise Invalid('policy: version 오류')
    ident(policy['task_id'])
    ident(policy['run_id'])
    root = Path(policy['root'])
    if not root.is_absolute() or str(root.resolve()) != str(root) or not (root / '.git').is_dir():
        raise Invalid('policy: 실제 Git 개발 저장소의 절대 경로 필요 (worktree 미지원)')
    canonical_repository(policy['repository'])
    for key in ('allowed_paths', 'protected_paths'):
        if not isinstance(policy[key], list) or not all(isinstance(p, str) for p in policy[key]):
            raise Invalid('policy: 경로 배열 필요')
        for name in policy[key]:
            relative(name)
    if not policy['allowed_paths']:
        raise Invalid('policy: 허용 경로 필요')
    approvals = policy['approval_required']
    if (not isinstance(approvals, list) or not all(isinstance(v, str) for v in approvals)
            or not SENSITIVE.issubset(set(approvals)) or not set(approvals) <= TOOLS):
        raise Invalid('policy: 커밋·푸시·게시의 사용자 승인 필수')
    fields(policy['binaries'], {'git', 'gh'})
    for binary in policy['binaries'].values():
        fields(binary, {'path', 'sha256'})
        if not Path(binary['path']).is_absolute() or not re.fullmatch('[0-9a-f]{64}', binary['sha256']):
            raise Invalid('policy: 실행 파일 절대 경로·해시 필요')
    return policy


@contextmanager
def parent_fd(root, name):
    """검사와 쓰기 모두 같은 디렉터리 FD 사용. 링크 교체로 외부 경로를 따르지 않는다."""
    if os.name != 'posix' or not hasattr(os, 'O_NOFOLLOW'):
        raise Invalid('platform: POSIX O_NOFOLLOW 필요')
    descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in relative(name).split('/')[:-1]:
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = next_fd
        yield descriptor, name.split('/')[-1]
    finally:
        os.close(descriptor)


def target_hash(root, name):
    with parent_fd(root, name) as (parent, leaf):
        try:
            info = os.stat(leaf, dir_fd=parent, follow_symlinks=False)
        except FileNotFoundError:
            return None
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise Invalid('path: 일반 단일 링크 파일만 지원')
        fd = os.open(leaf, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=parent)
        with os.fdopen(fd, 'rb') as stream:
            return hashlib.sha256(stream.read()).hexdigest()


class Broker:
    def __init__(self, authority):
        raw = Path(authority).absolute()
        if any(p.is_symlink() for p in (raw, *raw.parents)):
            raise Invalid('authority: 심볼릭 링크 금지')
        self.directory = raw.resolve()
        self.policy = validate_policy(load(self.directory / 'policy.json'))
        self.root = Path(self.policy['root'])
        if self.directory == self.root or self.root in self.directory.parents:
            raise Invalid('authority: 정책·승인 저장소는 개발 저장소 밖에 배치')
        for name in ('policy.json', 'anchor.json', 'key', 'approvals.json', 'journal.json'):
            item = self.directory / name
            if item.is_symlink() or not item.is_file() or item.stat().st_nlink != 1:
                raise Invalid('authority: 정책·기록의 링크·특수 파일 금지')
        if digest(self.policy) != load(self.directory / 'anchor.json')['policy_sha256']:
            raise Invalid('policy: 고정 정책과 불일치')
        self.policy_hash = digest(self.policy)
        self.key = (self.directory / 'key').read_bytes()
        if len(self.key) != 32:
            raise Invalid('authority: 서명 키 오류')

    def binary(self, name):
        item = self.policy['binaries'][name]
        resolved = Path(item['path']).resolve()
        if resolved == self.root or self.root in resolved.parents:
            raise Invalid('integration: 실행 파일은 개발 저장소 밖에 배치')
        if file_hash(item['path']) != item['sha256']:
            raise Invalid('integration: 실행 파일 변경·누락')
        return item['path']

    def git_command(self, *args):
        # 검사와 실제 전달에 같은 제한을 적용한다. 사용자/저장소 설정은 수정하지 않는다.
        options = {'core.hooksPath': '/dev/null', 'core.fsmonitor': 'false',
                   'commit.gpgsign': 'false', 'push.gpgSign': 'false',
                   'credential.helper': '', 'core.askPass': '/usr/bin/false',
                   'maintenance.auto': 'false', 'gc.auto': '0',
                   'remote.origin.mirror': 'false', 'push.followTags': 'false',
                   'http.followRedirects': 'false'}
        command = [self.binary('git')]
        for key, value in options.items():
            command.extend(['-c', key + '=' + value])
        return command + list(args)

    def git_environment(self, exec_path=None):
        environment = tool_environment()
        environment.update(GIT_ALLOW_PROTOCOL='https', GIT_TERMINAL_PROMPT='0')
        environment.pop('SSH_ASKPASS', None)
        if exec_path is not None:
            environment['GIT_EXEC_PATH'] = exec_path
        return environment

    def git(self, *args):
        result = subprocess.run(self.git_command(*args), cwd=self.root,
                                stdin=subprocess.DEVNULL, capture_output=True, timeout=30,
                                env=self.git_environment())
        if result.returncode:
            raise Invalid('repository: Git 상태 확인 실패')
        return result.stdout

    def push_transport(self):
        # remote.<name>.vcs는 HTTPS URL을 별도 remote helper로 바꿀 수 있다.
        for entry in self.git('config', '--null', '--list').decode().split('\x00'):
            key = entry.partition('\n')[0].lower()
            if ((key.startswith('remote.') and key.endswith('.vcs'))
                    or (key.startswith('url.') and key.endswith(('.insteadof', '.pushinsteadof')))
                    or (key.startswith('http.') and key != 'http.followredirects'
                        and key.endswith('.followredirects'))):
                raise Invalid('integration: 원격 helper·URL 재작성·개별 redirect 설정 미지원')
        # 고정 Git이 제공하는 HTTPS helper도 실제 승인 대상으로 식별한다.
        directory = Path(self.git('--exec-path').decode().strip())
        if not directory.is_absolute():
            raise Invalid('integration: Git exec-path는 절대 경로 필요')
        helper = directory / 'git-remote-https'
        resolved = helper.resolve()
        if self.root == resolved or self.root in resolved.parents or not os.access(helper, os.X_OK):
            raise Invalid('integration: 저장소 밖의 실행 가능한 HTTPS helper 필요')
        return {'exec_path': str(directory), 'https_helper_sha256': file_hash(helper)}

    def scoped_path(self, name):
        relative(name)
        if any(within(name, p) for p in PROTECTED + self.policy['protected_paths']):
            raise Invalid('protected: 보호 경로 수정 금지')
        if not any(within(name, p) for p in self.policy['allowed_paths']):
            raise Invalid('scope: 허용 변경 경로 밖')
        return target_hash(self.root, name)

    def context(self, request):
        fields(request, {'id', 'task_id', 'tool', 'cwd', 'args'})
        ident(request['id'])
        if request['task_id'] != self.policy['task_id'] or request['cwd'] != str(self.root):
            raise Invalid('scope: 작업 ID·작업 디렉터리 불일치')
        if request['tool'] not in TOOLS:
            raise Invalid('unsupported: 임의 셸·PR 본문 수정·미지원 도구는 실행하지 않음')
        if self.git('rev-parse', '--show-toplevel').decode().strip() != str(self.root):
            raise Invalid('repository: 저장소 경로 불일치')
        if self.git('rev-parse', '--absolute-git-dir').decode().strip() != str(self.root / '.git'):
            raise Invalid('repository: 다른 Git 디렉터리 금지')
        actual = self.git('remote', 'get-url', 'origin').decode().strip()
        expected = 'https://github.com/' + self.policy['repository'] + '.git'
        if actual != expected:
            raise Invalid('repository: origin은 선언한 GitHub HTTPS URL과 일치해야 함')
        tool, args = request['tool'], request['args']
        context = {'policy': self.policy_hash, 'root': str(self.root),
                   'repository': self.policy['repository'], 'task_id': request['task_id'],
                   'tool': tool, 'args_sha256': digest(args)}
        if tool == 'file.write':
            fields(args, {'path', 'content', 'expected_sha256'})
            if not isinstance(args['content'], str) or len(args['content'].encode()) > MAX_INPUT // 2:
                raise Invalid('schema: UTF-8 내용 크기 초과·형식 오류')
            before = self.scoped_path(args['path'])
            if before != args['expected_sha256']:
                raise Invalid('stale: 파일이 변경됨, 다시 읽고 요청')
            context['before'] = before
        elif tool == 'git.commit':
            fields(args, {'paths', 'message', 'head', 'index_sha256'})
            if (not isinstance(args['message'], str) or not args['message'].strip()
                    or len(args['message']) > 4096 or '\x00' in args['message']):
                raise Invalid('schema: 커밋 메시지 오류')
            if not isinstance(args['paths'], list) or not args['paths']:
                raise Invalid('schema: 커밋 대상 경로 필요')
            staged = self.git('diff', '--cached', '--no-ext-diff', '--no-textconv',
                              '--name-only', '-z', '--no-renames').decode().split('\x00')[:-1]
            if set(staged) != set(args['paths']) or len(set(args['paths'])) != len(args['paths']):
                raise Invalid('scope: 실제 전체 스테이징 경로와 선언 불일치')
            for name in staged:
                self.scoped_path(name)
            index = self.git('ls-files', '--stage', '-z').decode()
            entries = index.split('\x00')[:-1]
            for entry in entries:
                metadata, name = entry.split('\t', 1)
                mode, _, stage = metadata.split(' ')
                if name in staged and (mode not in {'100644', '100755'} or stage != '0'):
                    raise Invalid('path: 스테이징 링크·서브모듈·충돌 항목 금지')
            context['head'] = self.git('rev-parse', 'HEAD').decode().strip()
            context['index_sha256'] = hashlib.sha256(index.encode('utf-8')).hexdigest()
            if args['head'] != context['head'] or args['index_sha256'] != context['index_sha256']:
                raise Invalid('stale: HEAD·스테이징 목록 변경')
        elif tool == 'git.push':
            fields(args, {'branch', 'head'})
            branch = args['branch']
            if not isinstance(branch, str) or len(branch) > 128 or not re.fullmatch(WORK_BRANCH, branch):
                raise Invalid('scope: 저장소의 타입/이슈번호-요약 브랜치 규칙 필요')
            current = self.git('symbolic-ref', '--short', 'HEAD').decode().strip()
            context['head'] = self.git('rev-parse', 'HEAD').decode().strip()
            if branch != current or args['head'] != context['head']:
                raise Invalid('stale: 푸시 브랜치·HEAD 변경')
            urls = self.git('remote', 'get-url', '--push', '--all', 'origin').decode().splitlines()
            if urls != [expected]:
                raise Invalid('repository: 실제 push URL은 승인 저장소 1개만 허용')
            context['push_urls'] = urls
            context['git_transport'] = self.push_transport()
        else:
            fields(args, {'issue', 'body'})
            if (type(args['issue']) is not int or args['issue'] < 1 or not isinstance(args['body'], str)
                    or not args['body'].strip() or len(args['body'].encode()) > MAX_INPUT // 2):
                raise Invalid('schema: 이슈 번호·댓글 내용 오류')
            # PR conversation도 Issue API를 쓰지만 본문 편집 endpoint는 제공하지 않는다.
            self.binary('gh')
        return context

    def approval(self, scope):
        path = self.directory / 'approvals.json'
        records = load(path)
        if not isinstance(records, list):
            raise Invalid('approval: 승인 기록 형식 오류')
        for record in records:
            fields(record, {'payload', 'signature'})
            payload = record['payload']
            signature = hmac.new(self.key, encoded(payload), hashlib.sha256).hexdigest()
            if not isinstance(record['signature'], str) or not hmac.compare_digest(signature, record['signature']):
                raise Invalid('approval: 가짜·변조된 승인 기록')
            fields(payload, {'scope', 'reference', 'expires'})
            ident(payload['reference'])
            if type(payload['expires']) not in (int, float):
                raise Invalid('approval: 승인 만료 형식 오류')
            if payload['scope'] == digest(scope) and payload['expires'] > time.time():
                return payload['reference']
        return None

    def invoke(self, request):
        tool, args = request['tool'], request['args']
        if tool == 'file.write':
            with parent_fd(self.root, args['path']) as (parent, leaf):
                # 교체 직전 재확인, 기존 파일 권한 보존. 새 파일은 0600.
                if target_hash(self.root, args['path']) != args['expected_sha256']:
                    raise Invalid('stale: 전달 직전 파일 변경')
                mode = os.stat(leaf, dir_fd=parent, follow_symlinks=False).st_mode & 0o777 if args['expected_sha256'] is not None else 0o600
                temporary = '.execution-gate-' + os.urandom(16).hex()
                fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                             mode, dir_fd=parent)
                try:
                    with os.fdopen(fd, 'wb') as stream:
                        os.fchmod(stream.fileno(), mode)
                        stream.write(args['content'].encode('utf-8'))
                        stream.flush()
                        os.fsync(stream.fileno())
                    os.replace(temporary, leaf, src_dir_fd=parent, dst_dir_fd=parent)
                finally:
                    try:
                        os.unlink(temporary, dir_fd=parent)
                    except FileNotFoundError:
                        pass
            return 0
        if tool == 'git.commit':
            command = self.git_command('commit', '--no-verify', '-m', args['message'])
            environment = self.git_environment()
        elif tool == 'git.push':
            command = self.git_command('push', '--no-verify', '--no-follow-tags',
                                       '--recurse-submodules=no', 'origin',
                                       'HEAD:refs/heads/' + args['branch'])
            environment = self.git_environment(self.push_transport()['exec_path'])
        else:
            command = [self.binary('gh'), 'api', '--hostname', 'github.com', '--method', 'POST',
                       'repos/' + self.policy['repository'] + '/issues/' + str(args['issue']) + '/comments',
                       '--input', '-']
            environment = tool_environment()
        body = encoded({'body': args['body']}) if tool == 'github.issue_comment' else None
        result = subprocess.run(command, cwd=self.root, input=body, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, timeout=120, env=environment)
        return result.returncode

    def execute(self, request):
        """ID별 최대 한 번 전달. 승인 대기는 저장된 요청을 실행하지 않고 재판정한다."""
        import fcntl  # Windows에서는 도구를 전달하기 전에 실패한다.
        started = time.monotonic()
        with (self.directory / 'lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            # 매 요청 정책·승인·실제 대상을 다시 읽는다. 장기 객체의 낡은 권한 금지.
            current = Broker(self.directory)
            if current.policy_hash != self.policy_hash:
                raise Invalid('policy: 실행 중 정책 변경, 새 중개 세션 필요')
            return current._execute_locked(request, started)

    def _execute_locked(self, request, started):
        result = {'decision': 'deny', 'rule': 'invalid', 'status': 'not_started',
                  'approval_ref': None, 'exit_code': None}
        try:
            fields(request, {'id', 'task_id', 'tool', 'cwd', 'args'})
            ident(request['id'])
            if request['task_id'] != self.policy['task_id'] or request['cwd'] != str(self.root):
                raise Invalid('scope: 작업 ID·작업 디렉터리 불일치')
            fingerprint = digest(request)
            journal = load(self.directory / 'journal.json')
            old = journal.get(request['id'])
            if old:
                if old['fingerprint'] != fingerprint:
                    raise Invalid('replay: 같은 요청 ID에 다른 인자')
                # 완료·실패·불명확한 전달 모두 재실행하지 않는다.
                return dict(old['result'], replayed=True)
            context = self.context(request)
            reference = self.approval(context) if request['tool'] in self.policy['approval_required'] else None
            if request['tool'] in self.policy['approval_required'] and not reference:
                result.update(decision='approval_required', rule='explicit_user_approval',
                              approval_scope=digest(context))
            else:
                # 검사 중 파일·Git·정책 변경도 전달 전에 재확인한다.
                if context != self.context(request) or self.policy_hash != digest(load(self.directory / 'policy.json')):
                    raise Invalid('stale: 전달 전 조건 변경')
                result.update(rule='scope_and_approval', approval_ref=reference)
                intent = dict(result, decision='allow', status='unknown')
                journal[request['id']] = {'fingerprint': fingerprint, 'result': intent}
                save(self.directory / 'journal.json', journal)  # durable 전달 의도; 실패 시 시작 금지
                result.update(decision='allow', status='unknown')
                try:
                    code = self.invoke(request)
                    result.update(status='succeeded' if code == 0 else 'failed', exit_code=code)
                except (OSError, subprocess.SubprocessError, Invalid):
                    # timeout·I/O 오류는 부작용 여부를 추측하지 않는다.
                    result.update(status='unknown', rule='delivery_uncertain_do_not_retry')
                result['reason'] = REASONS[result['rule']]
                journal[request['id']]['result'] = dict(result)
                save(self.directory / 'journal.json', journal)
        except (Invalid, OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError) as exc:
            if result['decision'] == 'allow':
                result.update(status='unknown', rule='delivery_uncertain_do_not_retry')
            else:
                result.update(decision='deny', rule=str(exc).split(':')[0] if isinstance(exc, Invalid) else 'invalid')
        result['elapsed_ms'] = round((time.monotonic() - started) * 1000, 3)
        result['reason'] = REASONS.get(result['rule'], REASONS['invalid'])
        # 전체 요청·인자·본문·stdout/stderr를 보관하지 않는다.
        evidence = {k: v for k, v in result.items() if k != 'approval_scope'}
        evidence.update(task_id=self.policy['task_id'], run_id=self.policy['run_id'],
                        request_sha256=digest(request), policy_sha256=self.policy_hash,
                        recorded_at=int(time.time()),
                        tool=request.get('tool') if isinstance(request, dict) and request.get('tool') in TOOLS else 'unsupported')
        try:
            with (self.directory / 'events.jsonl').open('a', encoding='utf-8') as stream:
                stream.write(encoded(evidence).decode() + '\n')
                stream.flush()
                os.fsync(stream.fileno())
        except OSError:
            result['audit'] = 'unavailable'  # 전달 결과를 not_started로 잘못 덮지 않는다.
        return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--authority', required=True)
    args = parser.parse_args(argv)
    try:
        raw = sys.stdin.buffer.read(MAX_INPUT + 1)
        if len(raw) > MAX_INPUT:
            raise Invalid('schema: 요청 크기 초과')
        result = Broker(args.authority).execute(json.loads(raw))
    except (Invalid, OSError, ValueError, TypeError, KeyError, ImportError):
        result = {'decision': 'deny', 'rule': 'integration', 'status': 'not_started',
                  'reason': REASONS['integration']}
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result['status'] == 'succeeded' else 3 if result['decision'] == 'approval_required' else 2


if __name__ == '__main__':
    sys.exit(main())
