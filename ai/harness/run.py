"""표준 라이브러리 기반 로컬 검증 기록·게이트. 에이전트 실행기가 아니다."""
import argparse
from datetime import datetime, timezone
import glob
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

VERSION = 1
KINDS = {'test', 'build', 'static', 'qa', 'review'}
STATES = {'not_run', 'running', 'passed', 'failed', 'invalid'}
LOCAL = 'ai/local-state'


class Invalid(ValueError):
    pass


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(',', ':')).encode('utf-8')).hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


def read(path):
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError) as exc:
        raise Invalid('기록 누락·손상: 선언을 복구하거나 새 ID로 init 후 재실행') from exc


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def nonempty(value):
    return isinstance(value, str) and bool(value.strip())


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', value):
        raise Invalid('ID는 영문·숫자·밑줄·하이픈 1~80자')
    return value


def relative(root, name):
    if not nonempty(name) or Path(name).is_absolute():
        raise Invalid('저장소 기준 상대 경로 필요')
    path = (root / name).resolve()
    if root not in path.parents and path != root:
        raise Invalid('저장소 밖 경로 금지')
    return path


def validate_plan(plan):
    if not isinstance(plan, dict) or plan.get('version') != VERSION:
        raise Invalid('선언 version 오류')
    if not nonempty(plan.get('completion')) or not isinstance(plan.get('checks'), list) or not plan['checks']:
        raise Invalid('완료 조건과 checks 필요')
    for key in ('inputs', 'environment'):
        if not isinstance(plan.get(key), list) or not all(nonempty(x) for x in plan[key]):
            raise Invalid(key + ' 문자열 배열 필요')
    ids = set()
    for item in plan['checks']:
        if not isinstance(item, dict):
            raise Invalid('검증 항목 객체 필요')
        ident = identifier(item.get('id'))
        if ident in ids:
            raise Invalid('중복 항목 ID')
        ids.add(ident)
        if item.get('kind') not in KINDS or type(item.get('required')) is not bool:
            raise Invalid('kind 또는 required 오류')
        if not all(nonempty(item.get(k)) for k in ('role', 'condition')):
            raise Invalid('역할·항목 완료 조건 필요')
        if item['kind'] in {'qa', 'review'}:
            if not nonempty(item.get('procedure')) or type(item.get('independent')) is not bool:
                raise Invalid('수동 절차·독립 수행 요구 여부 필요')
        else:
            command = item.get('command')
            if not isinstance(command, list) or not command or not all(nonempty(x) for x in command):
                raise Invalid('command 인자 배열 필요')
            if item['kind'] == 'test':
                if item.get('adapter') not in {'unittest', 'junit'}:
                    raise Invalid('test adapter는 unittest 또는 junit')
                if item['adapter'] == 'junit':
                    if 'reports' in item:
                        reports = item['reports']
                        if ('report' in item or not isinstance(reports, list) or not reports
                                or not all(nonempty(r) for r in reports)):
                            raise Invalid('JUnit reports 상대 경로 배열 필요, report와 동시 사용 금지')
                    elif not nonempty(item.get('report')):
                        raise Invalid('JUnit 보고서 상대 경로 필요')
                if 'gradle_tasks' in item:
                    tasks = item['gradle_tasks']
                    if (not isinstance(tasks, list) or not tasks
                            or not all(isinstance(t, str) and re.fullmatch(r'(?::[\w.-]+)+', t) for t in tasks)
                            or len(set(tasks)) != len(tasks)):
                        raise Invalid('gradle_tasks는 중복 없는 전체 작업 경로 배열 필요 (:test 등)')
                if is_gradle(command) and not item.get('gradle_tasks'):
                    raise Invalid('Gradle 테스트는 gradle_tasks에 검증 대상 작업 선언 필요')
    if not any(c['required'] for c in plan['checks']):
        raise Invalid('필수 검증 항목이 최소 한 개 필요')
    return plan


def git(root, *args):
    result = subprocess.run(['git', '-C', str(root), *args], capture_output=True, check=False)
    if result.returncode:
        raise Invalid('Git 상태 확인 불가: 저장소·권한 확인')
    return result.stdout


def harness_path(name):
    return (name == 'AGENTS.md' or name == '.gitignore' or name.startswith('ai/')
            or name.startswith('docs/harness/') or name == 'docs/conventions/git.md'
            or name == '.github/workflows/harness-check.yml')


def file_hash(path):
    if path.is_symlink():
        return digest(['symlink', os.readlink(path)])
    if not path.is_file():
        return 'missing'
    return hashlib.sha256(path.read_bytes()).hexdigest()


def snapshot(root, plan):
    names = set(os.fsdecode(x) for x in git(root, 'ls-files', '-z', '--cached', '--others', '--exclude-standard').split(b'\0') if x)
    product, harness = {}, {}
    for name in sorted(names):
        if name.startswith(LOCAL + '/') or name.startswith('ai/local-evidence/') or '__pycache__/' in name:
            continue
        if name.startswith('docs/harness/records/'):
            continue  # 결과 요약 갱신은 코드·하네스 버전 변경이 아니다.
        path = root / name
        # Git 파일 모드와 작업 파일 실행 가능 여부도 식별한다.
        value = [file_hash(path), bool(path.exists() and path.stat().st_mode & 0o111)]
        (harness if harness_path(name) else product)[name] = value
    inputs = {name: file_hash(relative(root, name)) for name in plan['inputs']}
    if 'missing' in inputs.values():
        raise Invalid('선언한 검증 설정·환경 파일 누락: inputs 복구')
    if any(name not in os.environ for name in plan['environment']):
        raise Invalid('선언한 환경 변수 확인 불가: 환경 설정 후 재검증')
    env = {name: digest(os.environ[name]) for name in plan['environment']}
    return {'commit': git(root, 'rev-parse', 'HEAD').decode().strip(),
            'product': digest(product), 'harness': digest(harness), 'conditions': digest(plan),
            'inputs': digest(inputs), 'environment': digest([env, platform.platform(), sys.version])}


def result_unittest(output):
    matches = re.findall(r'^Ran (\d+) tests? in .+$', output, re.M)
    endings = re.findall(r'^(OK(?: \([^\n]*\))?|FAILED \([^\n]*\))\s*$', output, re.M)
    if len(matches) != 1 or len(endings) != 1:
        raise Invalid('unittest 최종 요약 확인 불가: 한 명령에서 한 suite를 실행')
    details = dict((k, int(v)) for k, v in re.findall(
        r'(failures|errors|skipped|unexpected successes)=(\d+)', endings[0]))
    counts = {'executed': int(matches[0]) - details.get('skipped', 0),
              'failures': details.get('failures', 0), 'errors': details.get('errors', 0),
              'skipped': details.get('skipped', 0)}
    return counts, ('FAILED' if endings[0].startswith('FAILED') else 'OK'), details.get('unexpected successes', 0)


def counts_junit(path):
    try:
        tree = ET.parse(path)
        root = tree.getroot()
        if root.tag not in {'testsuite', 'testsuites'}:
            raise Invalid('JUnit testsuite/testsuites 형식 필요')
        cases = list(root.iter('testcase'))
        # 집계 속성 대신 실제 testcase를 센다. 비정상 XML은 실패한다.
        return {'executed': sum(c.find('skipped') is None for c in cases),
                'failures': sum(c.find('failure') is not None for c in cases),
                'errors': sum(c.find('error') is not None for c in cases),
                'skipped': sum(c.find('skipped') is not None for c in cases)}
    except (OSError, ET.ParseError) as exc:
        raise Invalid('JUnit 보고서 누락·형식 오류') from exc


def report_scope(root, name):
    """선택자의 고정 디렉터리를 실행별 보고서 범위로 사용한다."""
    if not nonempty(name) or Path(name).anchor:
        raise Invalid('JUnit reports 저장소 기준 상대 경로 필요')
    parts = Path(name).parts
    wildcard = next((i for i, part in enumerate(parts) if glob.has_magic(part)), None)
    if wildcard is not None:
        scope = root / Path(*parts[:wildcard])
    else:
        path = root / name
        scope = path.parent if name.endswith('.xml') else path
    scope = report_path(root, scope)
    if (root / LOCAL).resolve() not in scope.parents:
        raise Invalid('JUnit 보고서 범위는 ai/local-state/ 하위 실행별 디렉터리 필요')
    return scope


def report_path(root, path):
    # 경로 별칭과 외부/이전 실행 링크를 보고서 근거로 인정하지 않는다.
    try:
        name = str(path.relative_to(root))
    except ValueError as exc:
        raise Invalid('JUnit 보고서는 저장소 안의 상대 경로 필요') from exc
    resolved = relative(root, name)
    if (root / LOCAL).resolve() not in resolved.parents:
        raise Invalid('JUnit 보고서는 ai/local-state/ 안에 생성')
    if any(p.is_symlink() for p in (path, *path.parents) if p != root and root in p.parents):
        raise Invalid('JUnit 보고서 범위·파일의 심볼릭 링크 금지')
    return resolved


def scope_xml(root, scope):
    paths = list(scope.rglob('*'))
    if any(p.is_symlink() for p in paths):
        raise Invalid('JUnit 보고서 범위·파일의 심볼릭 링크 금지')
    return {report_path(root, p) for p in paths if p.suffix == '.xml'}


def report_inventory(root, item):
    selected, inventory = set(), set()
    for name in item['reports']:
        scope = report_scope(root, name)
        inventory.update(scope_xml(root, scope))
        if glob.has_magic(name):
            paths = list(root.glob(name))
        elif name.endswith('.xml'):
            paths = [root / name] if (root / name).exists() else []
        else:
            paths = list(scope.rglob('*.xml'))
        files = {report_path(root, p) for p in paths}
        if not files or any(not p.is_file() or p.suffix != '.xml' for p in files):
            raise Invalid('JUnit 보고서 없음·파일 범위 오류: 각 reports 선택자에 XML 필요')
        selected.update(files)
    if selected != inventory:
        raise Invalid('JUnit 범위 내 XML 누락: 정상 파일만 선택하지 말고 전체 디렉터리 또는 *.xml 선언')
    return sorted(selected)


def reports_junit(root, item):
    counts = dict.fromkeys(('executed', 'failures', 'errors', 'skipped'), 0)
    evidence = []
    for path in report_inventory(root, item):
        checksum = file_hash(path)
        actual = counts_junit(path)
        if checksum != file_hash(path):
            raise Invalid('JUnit 보고서 읽기 중 변경: 재실행')
        for key in counts:
            counts[key] += actual[key]
        evidence.append({'path': path.relative_to(root).as_posix(), 'hash': checksum})
    return counts, evidence


def is_gradle(command):
    return Path(command[0]).name.lower() in {'gradle', 'gradlew', 'gradle.bat', 'gradlew.bat'}


class ExecutionOutput:
    """출력 전체를 보관하지 않고 줄별 작업 상태와 제한된 최종 요약을 수집한다."""
    def __init__(self, item):
        self.item = item
        self.tail = b''
        self.pending = b''
        self.cached = False
        self.tasks = {}
        self.saw_gradle = False

    def feed(self, chunk):
        self.tail = (self.tail + chunk)[-65536:]
        self.pending += chunk
        while b'\n' in self.pending:
            line, self.pending = self.pending.split(b'\n', 1)
            self.line(line)
        # 비정상적으로 긴 줄도 전체 로그로 보관하지 않는다. 작업 줄로 해석하지 않는다.
        if len(self.pending) > 65536:
            self.line(self.pending)
            self.pending = b''

    def line(self, raw):
        line = raw.decode('utf-8', errors='replace').strip()
        match = re.fullmatch(r'> Task ((?::[\w.-]+)+)(?:\s+(.+))?', line)
        if match:
            self.saw_gradle = True
            task = match.group(1)
            state = match.group(2) or 'EXECUTED'
            if match.group(2) == 'EXECUTED':
                state = 'UNKNOWN'  # plain Gradle 실행 줄에는 접미사가 없다.
            if task in self.item.get('gradle_tasks', []):
                self.tasks[task] = 'AMBIGUOUS' if task in self.tasks else state
            if self.item['kind'] == 'test':
                return  # 다른 Gradle 작업 상태는 테스트 전체의 캐시 판정이 아니다.
        if re.search(r'UP-TO-DATE|FROM-CACHE|NO-SOURCE', line):
            self.cached = True

    def finish(self):
        if self.pending:
            self.line(self.pending)
            self.pending = b''
        tasks = self.item.get('gradle_tasks')
        if tasks:
            if any(self.tasks.get(t) != 'EXECUTED' for t in tasks):
                return '대상 Gradle 작업 캐시·미실행·스킵 또는 실행 상태 확인 불가: --console=plain으로 재검증'
        elif self.item.get('adapter') == 'junit' and (self.saw_gradle or is_gradle(self.item['command'])):
            return 'Gradle 테스트 대상 확인 불가: gradle_tasks 선언 필요'
        if self.cached:
            return '캐시·미실행 표시 감지: 실제 실행 옵션으로 재검증'
        return None


def valid_counts(counts):
    return (isinstance(counts, dict) and set(counts) == {'executed', 'failures', 'errors', 'skipped'}
            and all(type(v) is int and v >= 0 for v in counts.values())
            and counts['executed'] > 0 and counts['failures'] == counts['errors'] == 0)


class Run:
    def __init__(self, root, run_id):
        self.root = Path(root).resolve()
        self.directory = self.root / LOCAL / identifier(run_id)
        if self.directory.is_symlink() or self.root / LOCAL not in self.directory.resolve().parents:
            raise Invalid('로컬 상태 경로 오류')
        self.run_id = run_id

    def init(self, plan):
        validate_plan(plan)
        if self.directory.exists():
            raise Invalid('실행 ID가 이미 존재: 기존 기록을 보존하고 새 ID 사용')
        if git(self.root, 'check-ignore', '--no-index', LOCAL + '/probe').strip() == b'':
            raise Invalid('로컬 상태 Git 제외 규칙 필요')
        if git(self.root, 'ls-files', LOCAL).strip():
            raise Invalid('로컬 상태가 Git에 추적됨: 제외 상태 복구 필요')
        snap = snapshot(self.root, plan)
        state = {'version': VERSION, 'run_id': self.run_id, 'product_start': snap['commit'],
                 'created': now(), 'results': {c['id']: {'status': 'not_run'} for c in plan['checks']}}
        write(self.directory / 'plan.json', plan)
        write(self.directory / 'state.json', state)

    def load(self):
        git(self.root, 'check-ignore', '--no-index', LOCAL + '/probe')
        if git(self.root, 'ls-files', LOCAL).strip():
            raise Invalid('로컬 실행 상태가 Git에 추적됨: 제외 상태 복구 필요')
        plan = validate_plan(read(self.directory / 'plan.json'))
        state = read(self.directory / 'state.json')
        if (not isinstance(state, dict) or state.get('version') != VERSION
                or state.get('run_id') != self.run_id or not nonempty(state.get('product_start'))
                or not nonempty(state.get('created')) or not isinstance(state.get('results'), dict)):
            raise Invalid('실행 상태 형식 오류: 복구 또는 새 ID로 재실행')
        for result in state['results'].values():
            if not isinstance(result, dict) or result.get('status') not in STATES:
                raise Invalid('결과 상태 형식 오류: 복구 또는 재실행')
        return plan, state

    def item(self, plan, item_id):
        for item in plan['checks']:
            if item['id'] == item_id:
                return item
        raise Invalid('선언되지 않은 검증 항목')

    def save(self, state):
        write(self.directory / 'state.json', state)

    def attest(self, state, item, result):
        path = self.directory / (item['id'] + '-' + uuid.uuid4().hex + '.json')
        write(path, result)
        state['results'][item['id']] = dict(result, receipt=path.name, receipt_hash=file_hash(path))
        self.save(state)

    def execute(self, item_id):
        plan, state = self.load()
        item = self.item(plan, item_id)
        if item['kind'] in {'qa', 'review'}:
            raise Invalid('수동 항목은 record 명령으로 범위·보고 주체·근거 기록')
        before = snapshot(self.root, plan)
        report = None
        reports = None
        if item.get('adapter') == 'junit' and 'reports' in item:
            for name in item['reports']:
                scope = report_scope(self.root, name)
                if scope_xml(self.root, scope):
                    raise Invalid('기존 JUnit 보고서 혼입 금지: 새 실행별 reports 범위 필요')
                scope.mkdir(parents=True, exist_ok=True)
        elif item.get('adapter') == 'junit':
            report = relative(self.root, item['report'])
            if (self.root / LOCAL).resolve() not in report.parents:
                raise Invalid('JUnit 보고서는 ai/local-state/ 안에 생성')
            if report.exists():
                raise Invalid('기존 JUnit 보고서 재사용 금지: 새 report 경로로 실행')
            report.parent.mkdir(parents=True, exist_ok=True)
        result = {'status': 'running', 'kind': item['kind'], 'role': item['role'],
                  'mode': 'executed', 'snapshot': before, 'started': now(), 'command': item['command']}
        state['results'][item_id] = result
        self.save(state)
        counts, error = None, None
        output = ExecutionOutput(item)
        test_outcome, unexpected_successes = None, 0
        # 원본 로그는 디스크에 기록하지 않는다. unittest 요약만 메모리에서 추출한다.
        try:
            process = subprocess.Popen(item['command'], cwd=self.root, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT)
            while True:
                chunk = process.stdout.read(4096)
                if not chunk:
                    break
                output.feed(chunk)
            exit_code = process.wait()
            process.stdout.close()
            if item['kind'] == 'test':
                if item.get('adapter') == 'junit' and 'reports' in item:
                    counts, reports = reports_junit(self.root, item)
                elif report:
                    counts = counts_junit(report)
                else:
                    counts, test_outcome, unexpected_successes = result_unittest(output.tail.decode('utf-8', errors='replace'))
        except OSError:
            exit_code = -1
            error = '명령 실행 불가: 실행 파일·권한 확인'
        except Invalid as exc:
            error = str(exc)  # 요약 오류여도 실제 프로세스 종료 코드는 보존한다.
        execution_error = output.finish()
        if item.get('gradle_tasks'):
            result['gradle_tasks'] = output.tasks
        result.update(ended=now(), exit_code=exit_code, counts=counts,
                      summary=error or ('명령 성공' if exit_code == 0 else '명령 실패'))
        if item.get('adapter') == 'unittest':
            result.update(test_outcome=test_outcome, unexpected_successes=unexpected_successes)
        summary_passed = test_outcome != 'FAILED' and unexpected_successes == 0
        passed = exit_code == 0 and not error and not execution_error and summary_passed and (item['kind'] != 'test' or valid_counts(counts))
        if execution_error:
            result['summary'] = execution_error
        elif not summary_passed:
            result['summary'] = 'unittest 최종 요약 FAILED: 실패 또는 예상 밖 성공을 확인하고 재검증'
        elif item['kind'] == 'test' and not valid_counts(counts) and not error:
            result['summary'] = '실제 테스트 0건·스킵만·실패·오류: 테스트 보완'
        result['status'] = 'passed' if passed else 'failed'
        if before != snapshot(self.root, plan):
            result.update(status='invalid', summary='실행 중 코드·조건 변경: 재실행 필요')
        if reports is not None:
            result['reports'] = reports
        if report and report.is_file():
            result['report'] = {'path': item['report'], 'hash': file_hash(report)}
        self.attest(state, item, result)
        return result['status'] == 'passed'

    def begin(self, item_id):
        plan, state = self.load()
        item = self.item(plan, item_id)
        if item['kind'] not in {'qa', 'review'}:
            raise Invalid('자동 항목은 run 사용')
        state['results'][item_id] = {'status': 'running', 'started': now(),
                                     'snapshot': snapshot(self.root, plan)}
        self.save(state)

    def record(self, item_id, evidence, scope, reporter, independent, summary):
        plan, state = self.load()
        item = self.item(plan, item_id)
        if item['kind'] not in {'qa', 'review'}:
            raise Invalid('자동 검증은 run으로 실행')
        if not all(nonempty(v) for v in (evidence, scope, reporter, summary)):
            raise Invalid('수동 확인 범위·보고 주체·요약·근거 필요')
        path = relative(self.root, evidence)
        if not path.is_file() or path.stat().st_size == 0:
            raise Invalid('수동 근거 파일 누락·빈 파일')
        previous = state['results'].get(item_id, {})
        if previous.get('status') != 'running' or 'snapshot' not in previous or 'started' not in previous:
            raise Invalid('수동 검토 시작 전에 begin 실행 필요')
        timestamp = now()
        current = snapshot(self.root, plan)
        result = {'status': 'passed', 'kind': item['kind'], 'role': item['role'], 'mode': 'manual',
                  'snapshot': previous['snapshot'], 'started': previous['started'], 'ended': timestamp,
                  'exit_code': None, 'counts': None, 'summary': summary,
                  'scope': scope, 'reporter': reporter, 'independent': independent,
                  'procedure': item['procedure'], 'evidence': {'path': evidence, 'hash': file_hash(path)}}
        if previous['snapshot'] != current:
            result.update(status='invalid', summary='검토 중 코드·조건 변경: begin부터 재확인')
        self.attest(state, item, result)
        return result['status'] == 'passed'

    def reasons(self, item, result, current):
        status = result.get('status', 'not_run')
        if status != 'passed':
            return [status + ': ' + ('중단된 실행 확인 후 재실행' if status == 'running' else '필요한 검증 수행')]
        issues = []
        old = result.get('snapshot', {})
        if not isinstance(old, dict):
            old = {}
        for key in current:
            if old.get(key) != current[key]:
                issues.append(key + ' 변경·누락: 무효, 재검증')
        try:
            receipt = self.directory / result.get('receipt', '')
            if (receipt.resolve().parent != self.directory.resolve() or not receipt.is_file()
                    or file_hash(receipt) != result.get('receipt_hash')
                    or read(receipt) != {k: v for k, v in result.items() if k not in {'receipt', 'receipt_hash'}}):
                issues.append('실행 근거 영수증 누락·변조: 재실행')
            start, end = (datetime.fromisoformat(result[k]) for k in ('started', 'ended'))
            if not start.tzinfo or not end.tzinfo or start > end or end > datetime.now(timezone.utc):
                issues.append('실제 시작·종료 시각 오류: 재실행')
        except (KeyError, TypeError, ValueError, OSError):
            issues.append('실행 근거·시각 확인 불가: 재실행')
        if result.get('kind') != item['kind'] or result.get('role') != item['role'] or not nonempty(result.get('summary')):
            issues.append('종류·역할·요약 누락: 재실행')
        manual = item['kind'] in {'qa', 'review'}
        if manual:
            if (result.get('mode') != 'manual' or not nonempty(result.get('scope'))
                    or not nonempty(result.get('reporter')) or result.get('procedure') != item['procedure']
                    or item['independent'] and result.get('independent') is not True):
                issues.append('수동 범위·보고 주체·독립 수행 근거 부족: 재확인')
        elif (result.get('mode') not in {'executed', 'reused'} or result.get('exit_code') != 0
              or type(result.get('exit_code')) is not int or result.get('command') != item['command']):
            issues.append('실제 명령 성공 근거 부족: 재실행')
        if item.get('gradle_tasks') and result.get('gradle_tasks') != {t: 'EXECUTED' for t in item['gradle_tasks']}:
            issues.append('대상 Gradle 작업 실행 근거 부족: 재실행')
        if item['kind'] == 'test' and not valid_counts(result.get('counts')):
            issues.append('테스트 0건·실패·오류·건수 누락: 실제 테스트 실행')
        if item.get('adapter') == 'unittest' and (result.get('test_outcome') != 'OK'
                or type(result.get('unexpected_successes')) is not int or result['unexpected_successes'] != 0):
            issues.append('unittest 성공 요약 근거 부족·예상 밖 성공: 재실행')
        if result.get('mode') == 'reused' or 'reuse' in result:
            reuse = result.get('reuse')
            if not isinstance(reuse, dict) or not all(nonempty(reuse.get(k)) for k in ('source', 'reason', 'at')):
                issues.append('재사용 출처·이유·시각 누락: 재실행 또는 재사용 기록 보완')
        for key in ('evidence', 'report'):
            if key in result or key == 'evidence' and manual:
                try:
                    evidence = result[key]
                    path = relative(self.root, evidence['path'])
                    if not path.is_file() or path.stat().st_size == 0 or file_hash(path) != evidence['hash']:
                        issues.append(key + ' 근거 누락·변경: 재확인')
                    elif key == 'report' and counts_junit(path) != result.get('counts'):
                        issues.append('JUnit 건수 불일치: 재실행')
                except (KeyError, TypeError, OSError, Invalid):
                    issues.append(key + ' 근거 확인 불가: 재확인')
        if item.get('adapter') == 'junit':
            if 'reports' in item:
                try:
                    actual, evidence = reports_junit(self.root, item)
                    if evidence != result.get('reports') or actual != result.get('counts'):
                        issues.append('JUnit 보고서 집합·체크섬·집계 변경: 재실행')
                except (Invalid, OSError, ValueError):
                    issues.append('JUnit 보고서 집합 누락·변경·형식 오류: 재실행')
            elif 'report' not in result:
                issues.append('JUnit 보고서 근거 누락: 재실행')
        return issues

    def inspect(self):
        plan, state = self.load()
        current = snapshot(self.root, plan)
        rows = []
        for item in plan['checks']:
            result = state['results'].get(item['id'], {'status': 'not_run'})
            reasons = self.reasons(item, result, current)
            rows.append({'id': item['id'], 'required': item['required'],
                         'status': 'invalid' if result.get('status') == 'passed' and reasons else result.get('status', 'not_run'),
                         'mode': result.get('mode'), 'reasons': reasons})
        return rows

    def reuse(self, source_id, item_id, reason):
        if not nonempty(reason) or source_id == self.run_id:
            raise Invalid('다른 실행 ID와 재사용 근거 필요')
        plan, state = self.load()
        item = self.item(plan, item_id)
        source = Run(self.root, source_id)
        old_plan, old_state = source.load()
        if source.item(old_plan, item_id) != item:
            raise Invalid('재사용 항목 선언 불일치')
        result = old_state['results'].get(item_id, {})
        if source.reasons(item, result, snapshot(self.root, plan)):
            raise Invalid('재사용 결과가 무효: 현재 코드·조건에서 재검증')
        copied = {k: v for k, v in result.items() if k not in {'receipt', 'receipt_hash'}}
        copied.update(mode='manual' if item['kind'] in {'qa', 'review'} else 'reused',
                      reuse={'source': source_id, 'reason': reason, 'at': now()})
        self.attest(state, item, copied)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', default=str(Path(__file__).resolve().parents[2]))
    sub = parser.add_subparsers(dest='action', required=True)
    for action in ('init', 'begin', 'run', 'record', 'show', 'gate', 'resume', 'reuse'):
        p = sub.add_parser(action)
        p.add_argument('run_id')
        if action == 'init':
            p.add_argument('plan')
        if action in {'begin', 'run', 'record', 'reuse'}:
            p.add_argument('item_id')
        if action == 'record':
            for name in ('evidence', 'scope', 'reporter', 'summary'):
                p.add_argument('--' + name, required=True)
            p.add_argument('--independent', action='store_true')
        if action == 'reuse':
            p.add_argument('--source', required=True)
            p.add_argument('--reason', required=True)
    args = parser.parse_args(argv)
    try:
        run = Run(args.root, args.run_id)
        if args.action == 'init':
            run.init(read(Path(args.plan)))
        elif args.action == 'run':
            return 0 if run.execute(args.item_id) else 1
        elif args.action == 'begin':
            run.begin(args.item_id)
        elif args.action == 'record':
            return 0 if run.record(args.item_id, args.evidence, args.scope,
                                   args.reporter, args.independent, args.summary) else 1
        elif args.action == 'reuse':
            run.reuse(args.source, args.item_id, args.reason)
        else:
            rows = run.inspect()
            if args.action == 'show':
                plan, state = run.load()
                print('실행 ID: ' + run.run_id + ' / 제품 출발: ' + state['product_start'])
                print('현재 대상: ' + str(snapshot(run.root, plan)))
                for item in plan['checks']:
                    result = state['results'].get(item['id'], {})
                    print(item['id'] + ': 역할=' + item['role'] + ' / 종류=' + item['kind'])
                    for key in ('condition', 'command', 'procedure'):
                        if key in item:
                            print('  ' + key + ': ' + str(item[key]))
                    for key in ('started', 'ended', 'exit_code', 'counts', 'test_outcome', 'unexpected_successes', 'summary', 'scope', 'reporter', 'reuse', 'snapshot'):
                        if key in result:
                            print('  ' + key + ': ' + str(result[key]))
            for row in rows:
                print(row['id'] + ': ' + row['status'] + (' (' + str(row['mode']) + ')' if row['mode'] else ''))
                for reason in row['reasons']:
                    print('  ' + reason)
            blocked = any(row['required'] and row['reasons'] for row in rows)
            print('완료 판정: ' + ('거부' if blocked else '통과'))
            return 1 if blocked else 0
        print('로컬 기록 갱신: ' + args.action)
        return 0
    except (Invalid, OSError, TypeError, KeyError) as exc:
        print(str(exc) if isinstance(exc, Invalid) else '기록 처리 불가: 경로·형식·권한 확인', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
