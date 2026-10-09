"""하네스 문서·근거를 읽기 전용으로 검사한다. 모델 실행기는 아니다."""
from pathlib import Path
import hashlib
import re
import sys
from urllib.parse import unquote, urlsplit

ROLES = ('diagnosis', 'review', 'verification', 'memory')
REQUIRED = ('AGENTS.md', 'ai/WORKFLOW.md', 'ai/routing.md',
            'ai/templates/work.md', 'ai/templates/failure.md',
            'ai/templates/memory.md', 'ai/memory/index.md',
            'docs/harness/README.md', 'docs/harness/local-validation.md',
            'ai/harness/run.py', 'ai/tests/test_run.py')


def local_targets(document):
    """코드 블록·외부 URL·같은 문서 앵커는 검사 대상에서 제외한다."""
    content = re.sub(r'```.*?```', '', document.read_text(encoding='utf-8'), flags=re.S)
    for target in re.findall(r'\]\(([^)]+)\)', content):
        target = target.strip().strip('<>')
        if target.startswith('#') or urlsplit(target).scheme:
            continue
        yield (document.parent / unquote(target.split('#')[0])).resolve()


def validate(root):
    root = Path(root).resolve()
    problems = []
    required = list(REQUIRED) + ['ai/roles/' + role + '.md' for role in ROLES]
    for name in required:
        if not (root / name).is_file():
            problems.append('필수 문서 누락: ' + name)
    documents = ([root / 'AGENTS.md'] + list((root / 'ai').rglob('*.md'))
                 + list((root / 'docs/harness').rglob('*.md')))
    documents = [d for d in documents if not any(part in ('local-state', 'local-evidence')
                                               for part in d.relative_to(root).parts)]
    for document in documents:
        if not document.is_file():
            continue
        for target in local_targets(document):
            if not target.exists():
                problems.append(f'링크 대상 누락: {document.relative_to(root)} → {target}')
    for document in (root / 'ai/memory').glob('*.md'):
        if document.name == 'index.md':
            continue
        match = re.search(r'^- 상태:\s*(\w+)', document.read_text(encoding='utf-8'), re.M)
        if not match or match[1] not in ('candidate', 'confirmed', 'stale'):
            problems.append('기억 상태 누락·오류: ' + document.name)
        elif match[1] == 'confirmed':
            evidence = [p for p in local_targets(document)
                        if p.is_file() and (root / 'ai/memory') not in p.parents
                        and root in p.parents]
            if not evidence:
                problems.append('확정 기억의 로컬 근거 누락: ' + document.name)
    for manifest in (root / 'docs/harness/evidence').rglob('checksums.sha256'):
        for line in manifest.read_text(encoding='utf-8').splitlines():
            if not line.strip():
                continue
            parts = line.split(maxsplit=1)
            if len(parts) != 2 or not re.fullmatch(r'[0-9a-f]{64}', parts[0]):
                problems.append('체크섬 형식 오류: ' + str(manifest.relative_to(root)))
                continue
            target = (manifest.parent / parts[1].lstrip('*')).resolve()
            if manifest.parent.resolve() not in target.parents:
                problems.append('체크섬 경로 범위 오류: ' + parts[1])
            elif not target.is_file():
                problems.append('증거 파일 누락: ' + parts[1])
            elif hashlib.sha256(target.read_bytes()).hexdigest() != parts[0]:
                problems.append('증거 체크섬 불일치: ' + parts[1])
    return problems


if __name__ == '__main__':
    errors = validate(Path(__file__).resolve().parents[2])
    for error in errors:
        print(error)
    print('하네스 구조 검사: ' + ('실패' if errors else '통과'))
    sys.exit(1 if errors else 0)
