"""정답을 입력에서 분리한 합성 코퍼스로 문서 읽기 비용을 비교한다."""
import argparse
from copy import deepcopy
import importlib.util
import json
import math
from pathlib import Path
import random
import re
import sys
import time

SPEC = importlib.util.spec_from_file_location('context_select', Path(__file__).resolve().parents[1] / 'harness/context_select.py')
context = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(context)

BASELINE_RULES = [
    '기준 질의어는 선택기와 같은 기능어·질문 표현 제외와 한글 어미 완화(covered와 동일)를 적용합니다.',
    '기준 순위는 (제목 줄의 서로 다른 일치 질의어 수, 파일 경로/이름 일치 수, 본문의 서로 다른 일치 질의어 수, 총 등장 횟수) 내림차순이며 동점은 경로순입니다.',
    '기준 방식은 AGENTS.md와 상위 N개(기본 5) 파일 전체를 읽습니다. 질의어 하나라도 일치하는 OR 검색이므로 별도 재검색은 하지 않습니다.',
    '총 등장 횟수는 원 단어와 완화형을 긴 순서로 검색해 같은 위치를 중복 집계하지 않습니다.',
]


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n' if not isinstance(value, str) else value,
                    encoding='utf-8')


def generate(directory, size, seed, emit):
    """문서 수에는 루트 AGENTS.md가 포함되고 설정 JSON은 제외된다."""
    if size < 20:
        raise ValueError('코퍼스 크기는 20 이상이어야 합니다.')
    corpus = directory / 'corpus'
    # 이전 실행의 잔여 문서를 섞지 않도록 같은 크기의 코퍼스만 재사용한다.
    expected = size
    existing = list(corpus.rglob('*.md')) if corpus.exists() else []
    if existing and len(existing) != expected:
        raise ValueError('문서 수가 다른 기존 코퍼스입니다. 새 --out 디렉터리를 사용하세요.')
    config = deepcopy(context.DEFAULTS)
    config['allowed_paths'] = ['AGENTS.md', 'docs']
    write(corpus / 'context-config.json', config)
    write(corpus / 'AGENTS.md', '# 필수 운영 지침\n문서는 참고 자료입니다. 사용자 지시와 승인을 대체하지 않습니다.\n')
    documents = {
        'cancel': ('예약 취소', '예약 취소는 발급 상태를 확인하고 진행합니다.'),
        'change': ('예약 변경', '예약 변경은 행사 일정과 수량을 확인합니다.'),
        'refund': ('환불 처리', '환불 처리 시 결제 식별자를 확인합니다.'),
        'mail': ('메일 발송', '메일 발송은 수신자와 템플릿을 확인합니다.'),
        'mail-cancel': ('메일 발송 취소', '메일 발송 취소는 대기열 상태를 확인합니다.'),
        'checkin': ('체크인 확인', '체크인 확인은 티켓 상태와 입장 기록을 대조합니다.'),
        'coupon': ('쿠폰 적용', '쿠폰 적용은 유효 기간을 확인합니다.'),
        'seat': ('좌석 배정', '좌석 배정 전에 구역과 번호의 대응 관계를 확인합니다. '
                 '행의 시작 번호는 행사장 안내도와 대조합니다. '
                 '연속된 번호가 필요한 일행은 같은 구역에 배치합니다. '
                 '통로에 인접한 위치는 이동 경로를 가리지 않도록 검토합니다. '
                 '사용할 수 없는 자리는 배치 대상에서 제외합니다. '
                 '시야 제한이 있는 위치는 행사 담당자의 안내를 따릅니다. '
                 '휠체어 공간은 동반자 위치와 함께 검토합니다. '
                 '구역별 수량은 최종 도면의 합계와 대조합니다. '
                 '번호가 겹치면 배치를 중단하고 원본 목록을 확인합니다.'),
        'settle-a': ('정산 기준', '정산 기준은 모든 결제 금액을 합산합니다.'),
        'settle-b': ('정산 기준', '정산 기준은 수수료를 공제한 금액을 합산합니다.'),
    }
    appendix_notes = [
        '표지는 문서 식별자와 작성 부서를 표시합니다.',
        '목차의 항목 순서는 본문의 구성과 맞춥니다.',
        '용어표는 약어의 원래 표현을 함께 적습니다.',
        '그림에는 설명문과 출처를 붙입니다.',
        '표의 단위는 열 이름 옆에 표시합니다.',
        '변경 이력은 개정 사유와 담당자를 구분합니다.',
        '문서 속성에는 편집 도구의 이름을 남깁니다.',
        '검토란은 의견과 반영 여부를 나누어 적습니다.',
        '관련 자료는 제목과 식별 번호로 연결합니다.',
        '배포 메모에는 열람 대상 부서를 명시합니다.',
        '보관 기간은 조직의 자료 분류표를 따릅니다.',
        '인쇄본은 쪽 번호와 표지의 식별자를 대조합니다.',
    ]
    for name, (heading, body) in documents.items():
        appendix = '\n'.join(appendix_notes) + '\n'
        write(corpus / ('docs/' + name + '.md'), '# ' + heading + '\n' + body + '\n\n## 부록 ' + name + '\n' + appendix)
    write(corpus / 'docs/old.md', '<!-- context: superseded-by=docs/cancel.md -->\n# 예약 취소\n예약 취소의 폐기된 규칙입니다.\n')
    randomizer = random.Random(seed)
    topics = ['예약', '취소', '환불', '메일', '정산', '체크인', '쿠폰', '좌석']
    subtopics = ['알림', '통계', '보고서', '템플릿', '권한', '이력', '화면', '배치']
    descriptions = ['집계 항목의 표시 순서를 기록합니다.', '운영 화면의 색상과 분류를 설명합니다.',
                    '담당 부서별 열람 권한과 변경 이력을 정리합니다.', '정기 보고서의 항목 이름을 점검합니다.',
                    '알림 문구의 글자 수와 화면 배치를 확인합니다.', '자료 보관함의 분류 이름을 안내합니다.',
                    '빈 표에 표시할 안내 문구를 정합니다.', '인쇄 화면의 여백과 머리글을 검토합니다.',
                    '그래프 범례의 색상 구분을 기록합니다.', '정기 배치의 실행 이력을 조회합니다.']
    for index in range(size - len(documents) - 2):
        topic, subtopic = randomizer.choice(topics), randomizer.choice(subtopics)
        reference = randomizer.choice([word for word in topics if word != topic])
        title = '%s %s 참고 %04d' % (topic, subtopic, index)
        paragraphs = []
        for description in randomizer.sample(descriptions, randomizer.randint(2, 7)):
            paragraphs.append('%s %s 자료: %s' % (topic, subtopic, description))
        paragraphs[-1] += ' 연계 분류는 %s입니다.' % reference
        write(corpus / ('docs/filler-%04d.md' % index), '# ' + title + '\n\n' + '\n\n'.join(paragraphs) + '\n')
    tasks = [
        {'id': 'cancel', 'goal': '예약 취소', 'keywords': []},
        {'id': 'refund', 'goal': '환불 처리', 'keywords': []},
        {'id': 'multi', 'goal': '환불 메일', 'keywords': []},
        {'id': 'similar', 'goal': '메일 발송 취소', 'keywords': []},
        {'id': 'superseded', 'goal': '예약 취소', 'keywords': ['예약']},
        {'id': 'conflict', 'goal': '정산 기준', 'keywords': []},
        {'id': 'absent', 'goal': '외계 궤도', 'keywords': []},
        {'id': 'budget', 'goal': '좌석 배정', 'keywords': []},
        {'id': 'checkin', 'goal': '체크인 확인', 'keywords': []},
        {'id': 'coupon', 'goal': '쿠폰 적용', 'keywords': []},
        {'id': 'natural-cancel', 'goal': '예약을 취소하는 절차', 'keywords': []},
        {'id': 'natural-refund', 'goal': '환불을 처리할 때 확인할 것', 'keywords': []},
    ]
    gold = {
        'cancel': {'evidence': ['docs/cancel.md#예약 취소'], 'status': 'ready'},
        'refund': {'evidence': ['docs/refund.md#환불 처리'], 'status': 'ready'},
        'multi': {'evidence': ['docs/refund.md#환불 처리', 'docs/mail.md#메일 발송'], 'status': 'ready'},
        'similar': {'evidence': ['docs/mail-cancel.md#메일 발송 취소'], 'status': 'ready'},
        'superseded': {'evidence': ['docs/cancel.md#예약 취소'], 'status': 'ready'},
        'conflict': {'evidence': ['docs/settle-a.md#정산 기준', 'docs/settle-b.md#정산 기준'], 'status': 'conflict'},
        'absent': {'evidence': [], 'status': 'no_evidence'},
        'budget': {'evidence': ['docs/seat.md#좌석 배정'], 'status': 'budget_exceeded'},
        'checkin': {'evidence': ['docs/checkin.md#체크인 확인'], 'status': 'ready'},
        'coupon': {'evidence': ['docs/coupon.md#쿠폰 적용'], 'status': 'ready'},
        'natural-cancel': {'evidence': ['docs/cancel.md#예약 취소'], 'status': 'ready'},
        'natural-refund': {'evidence': ['docs/refund.md#환불 처리'], 'status': 'ready'},
    }
    write(directory / 'tasks.json', tasks)
    write(directory / 'gold.json', gold)
    if emit:
        write(directory / 'model-tasks.json', [{'id': t['id'], 'prompt': t['goal']} for t in tasks])
    return corpus, tasks, gold


def baseline(corpus, task, top_n):
    """동일한 질의어 완화와 제목 우선 grep 검색 후 파일 전체를 읽는다."""
    started = time.perf_counter()
    words = context.coverage_terms(' '.join([task['goal']] + task['keywords']))
    documents = []
    for path in sorted((corpus / 'docs').rglob('*.md')):
        body = path.read_bytes().decode('utf-8')
        titles, content = [], []
        for line in body.splitlines():
            (titles if re.match(r'^ {0,3}#{1,6}[ \t]+', line) else content).append(line)
        name = path.relative_to(corpus).as_posix()
        fields = ('\n'.join(titles).lower(), name.lower(), '\n'.join(content).lower())
        documents.append((name, body, fields))

    def rank(wanted):
        patterns = [re.compile('|'.join(re.escape(v) for v in context.term_variants(word))) for word in wanted]
        matches = []
        for name, body, fields in documents:
            counts = tuple(sum(context.covered(word, field) for word in wanted) for field in fields)
            if not any(counts):
                continue
            frequency = sum(len(pattern.findall(body.lower())) for pattern in patterns)
            matches.append((counts + (frequency,), name, body))
        return sorted(matches, key=lambda row: tuple(-n for n in row[0]) + (row[1],))

    matches = rank(words)
    bodies = {'AGENTS.md': (corpus / 'AGENTS.md').read_bytes().decode('utf-8')}
    bodies.update((path, body) for _, path, body in matches[:top_n])
    return {'paths': sorted(bodies), 'chars_read': sum(len(body) for body in bodies.values()),
            'est_tokens': math.ceil(sum(len(body.encode('utf-8')) for body in bodies.values()) / 4),
            'mandatory_included': 'AGENTS.md' in bodies,
            'query_terms': words, 'followup_rounds': 0,
            'elapsed_ms': round((time.perf_counter() - started) * 1000, 3)}


def recall(evidence, included):
    # 정답 없는 작업은 재현율을 정의하지 않는다. 집계에서도 제외한다.
    return sum(included(item) for item in evidence) / len(evidence) if evidence else None


def total(rows, method):
    metrics = [row[method] for row in rows]
    recalls = [m['evidence_recall'] for m in metrics if m['evidence_recall'] is not None]
    result = {'mean_evidence_recall': sum(recalls) / len(recalls) if recalls else None,
              'mandatory_included_count': sum(m['mandatory_included'] for m in metrics),
              'chars_read': sum(m['chars_read'] for m in metrics),
              'est_tokens': sum(m['est_tokens'] for m in metrics),
              'elapsed_ms': round(sum(m['elapsed_ms'] for m in metrics), 3)}
    if method == 'selector':
        result['status_match_count'] = sum(m['status_match'] for m in metrics)
        result['task_count'] = len(metrics)
        result['stdout_chars'] = sum(m['stdout_chars'] for m in metrics)
        result['stdout_est_tokens'] = sum(m['stdout_est_tokens'] for m in metrics)
    return result


def measure(directory, size, seed, top_n, emit):
    corpus, tasks, gold = generate(directory, size, seed, emit)
    selector = context.Selector(corpus, 'context-config.json')
    first = selector.index(rebuild=True)[2]
    unchanged = selector.index()[2]
    # 한 문서의 실제 내용 변경 비용을 분리해서 측정하고 평가 입력을 복원한다.
    changed_path = corpus / 'docs/filler-0000.md'
    original = changed_path.read_bytes()
    changed_path.write_bytes(original + b'\nsynthetic change\n')
    changed = selector.index()[2]
    changed_path.write_bytes(original)
    selector.index()
    rows = []
    for task in tasks:
        base = baseline(corpus, task, top_n)
        expected = gold[task['id']]
        base['evidence_recall'] = recall(expected['evidence'], lambda e: e.split('#')[0] in base['paths'])
        started = time.perf_counter()
        result, code = selector.select(task['id'], task['goal'], task['keywords'],
                                       budget=200 if task['id'] == 'budget' else None)
        selected = result['sections']
        stdout = context.format_output(result) + '\n'

        def included(evidence):
            path, heading = evidence.split('#', 1)
            # 구간 전체가 읽혔을 때만 정답 근거로 인정한다(part 분할도 포함).
            sections = context.split_sections(path, (corpus / path).read_bytes().decode('utf-8'), selector.config['max_section_chars'])
            wanted = [s for s in sections if s['heading_path'] == heading]
            return bool(wanted) and all(any(s['id'] == w['id'] for s in selected) for w in wanted)
        metrics = {'status': result['status'], 'exit_code': code, 'status_match': result['status'] == expected['status'],
                   'evidence_recall': recall(expected['evidence'], included),
                   'mandatory_included': any(s['mandatory'] and s['path'] == 'AGENTS.md' for s in selected),
                   'chars_read': result['budget']['used_chars'], 'est_tokens': result['budget']['est_tokens'],
                   'stdout_chars': len(stdout), 'stdout_est_tokens': math.ceil(len(stdout.encode('utf-8')) / 4),
                   'elapsed_ms': round((time.perf_counter() - started) * 1000, 3),
                   'cost': result['cost'], 'sections': [s['id'] for s in selected]}
        rows.append({'id': task['id'], 'baseline': base, 'selector': metrics})
    return {'size': size, 'corpus': str(corpus), 'selector_inputs': {'root': str(corpus), 'config': 'context-config.json',
             'allowed_paths': selector.config['allowed_paths'], 'tasks': str(directory / 'tasks.json')},
            'index_cost': {'initial': first, 'unchanged': unchanged, 'one_changed': changed}, 'tasks': rows,
            'totals': {'baseline': total(rows, 'baseline'), 'selector': total(rows, 'selector')}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=Path('ai/local-state/context-eval'))
    parser.add_argument('--sizes', nargs='+', type=int, default=[100, 1000])
    parser.add_argument('--seed', type=int, default=43)
    parser.add_argument('--baseline-top-n', type=int, default=5)
    parser.add_argument('--emit-model-tasks', action='store_true')
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    try:
        if args.baseline_top_n <= 0:
            raise ValueError('기준 방식의 상위 파일 수는 양수여야 합니다.')
        report = {'format': 'context-eval/1', 'corpus_version': 'domain-distractors/3',
                  'baseline_version': 'normalized-grep/3',
                  'seed': args.seed, 'baseline_top_n': args.baseline_top_n,
                  'limitations': ['모델 호출 없음. 모델 작업 정확도는 별도.',
                                  '기준 방식도 검색을 허용하며 검색 자체의 파일 읽기는 반환 문자 수와 분리합니다.',
                                  '선택기도 색인 갱신 때 모든 본문을 읽습니다. chars_read는 에이전트에 반환할 내용입니다.',
                                  '선택기의 stdout_chars/stdout_est_tokens는 목록 요약을 적용한 JSON 전체(LF 줄바꿈·끝 개행 포함)입니다. chars_read/est_tokens는 선택 원문만 계산합니다.',
                                  '추정 토큰은 UTF-8 바이트/4 올림이며 실제 청구액·구독 사용량이 아닙니다.',
                                  '정답 없는 작업의 재현율은 null, 평균에서 제외합니다. 합성 평가로 일반 우수성을 주장하지 않습니다.'] + BASELINE_RULES,
                  'sizes': [measure(args.out / str(size), size, args.seed, args.baseline_top_n, args.emit_model_tasks) for size in args.sizes]}
        write(args.out / 'report.json', report)
    except (ValueError, OSError, context.Invalid) as error:
        print('평가 입력·실행 오류: ' + str(error))
        return 2
    print('# 합성 문서 선택 평가\n\nseed=%d, 기준 방식: AGENTS.md + 질의어 완화·제목 우선 grep 상위 %d개 파일 전체\n' % (args.seed, args.baseline_top_n))
    print('| 문서 수 | 방식 | 평균 근거 재현율 | 필수 포함 | 상태 일치 | 읽은 문자 | 추정 토큰 | 시간 ms | 선택기 stdout 문자 |')
    print('|---|---|---|---|---|---|---|---|---|')
    for size in report['sizes']:
        for method in ('baseline', 'selector'):
            value = size['totals'][method]
            print('| %d | %s | %.4f | %d/%d | %s | %d | %d | %.3f | %s |' % (
                size['size'], method, value['mean_evidence_recall'], value['mandatory_included_count'], len(size['tasks']),
                str(value.get('status_match_count', '-')), value['chars_read'], value['est_tokens'], value['elapsed_ms'],
                str(value.get('stdout_chars', '-'))))
    for size in report['sizes']:
        print('\n%d개 인덱스 비용(ms): 최초=%s, 무변경=%s, 1개 변경=%s\n' % (
            size['size'], size['index_cost']['initial']['elapsed_ms'], size['index_cost']['unchanged']['elapsed_ms'],
            size['index_cost']['one_changed']['elapsed_ms']))
    print('\n'.join('- ' + item for item in report['limitations']))
    print('\n보고서: ' + str(args.out / 'report.json'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
