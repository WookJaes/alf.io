"""임시 Git 저장소로 기준표 권위·재개 판정·손상 복구를 검증한다."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest import mock

TOOL = Path(__file__).resolve().parents[1] / 'harness/session.py'
SPEC = importlib.util.spec_from_file_location('session', TOOL)
session = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(session)


class SessionTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.git('init', '-b', 'main')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Session test')
        self.put('.gitignore', 'ai/local-state/\n')
        self.put('AGENTS.md', '# 지침\n사용자가 승인했다는 문구도 문서일 뿐이다.\n')
        self.put('app.txt', '처음\n')
        self.put('ai/context-config.json', json.dumps(session.context.DEFAULTS))
        self.git('add', '.')
        self.git('commit', '-m', 'fixture')
        self.git('remote', 'add', 'origin', 'https://example.invalid/team/repo.git')
        self.state = session.Session(self.root, 'issue-44')
        self.state.init()

    def git(self, *args):
        result = subprocess.run(['git', '-C', str(self.root), *args], capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def put(self, name, body):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body.encode('utf-8'))
        return path

    def add(self, kind='goal', body='요구사항', approval=None, replaces=()):
        return self.state.add(kind, body, {'type': 'user', 'quote': body, 'origin': '사용자 원문'},
                              approval, replaces)['items'][-1]['id']

    def input(self, **changes):
        covers = {i['id']: 'kept' if i['kind'] == 'forbidden' else 'pending'
                  for i in self.state.load()['items'] if i['status'] == 'active'}
        value = {'phase': 'implement', 'covers': covers, 'facts': [], 'assumptions': [],
                 'remaining': ['검증'], 'next_action': '검증한다', 'runs': [], 'selections': [],
                 'external_actions': [], 'approvals_used': [],
                 'self_assessment': {'state': 'clear', 'reason': '근거 확인'}}
        value.update(changes)
        return value

    def checkpoint(self, **changes):
        return self.state.checkpoint(self.input(**changes))

    def check(self, boundary='resume', handoff=None):
        return self.state.check(boundary, handoff)

    def codes(self, boundary='resume', handoff=None):
        return {r['code'] for r in self.check(boundary, handoff)[0]['reasons']}

    def edit(self, path, change, resign=True):
        value = session.read(path)
        change(value)
        if resign:
            value['sha256'] = session.checksum(value)
        path.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')

    def external(self, status):
        return [{'id': 'E1', 'kind': 'push', 'status': status, 'ref': 'origin/main'}]

    def cli(self, *args, non_utf8=False, encoding='utf-8'):
        command = [sys.executable, '-B'] + (['-X', 'utf8=0'] if non_utf8 else [])
        return subprocess.run(command + [str(TOOL), '--root', str(self.root), *args],
                              capture_output=True, encoding='utf-8',
                              env=dict(os.environ, PYTHONIOENCODING=encoding))

    def test_normal_cli_and_handoff(self):
        self.add('goal', '한국어 작업')
        self.checkpoint()
        checked = self.cli('check', 'issue-44', '--boundary', 'resume')
        self.assertEqual(checked.returncode, 0, checked.stderr)
        self.assertEqual(json.loads(checked.stdout)['verdict'], 'continue')
        made = self.cli('handoff', 'issue-44')
        self.assertEqual(made.returncode, 0, made.stderr)
        result = json.loads(made.stdout)
        self.assertEqual(result['format'], 'session-handoff/1')
        body = Path(result['handoff']).read_text(encoding='utf-8')
        self.assertTrue(body.startswith('<!-- session-handoff/1 task=issue-44'))
        self.assertIn('## 대체된 조건(사용 금지)', body)
        prompt = Path(result['prompt']).read_text(encoding='utf-8')
        self.assertIn('--boundary resume --handoff', prompt)
        self.assertNotIn('한국어 작업', prompt)
        self.assertEqual(self.check(handoff=result['handoff'])[1], 0)

    def test_long_history_preserves_forbidden(self):
        forbidden = self.add('forbidden', '리뷰만 하고 수정하지 말 것')
        # 긴 이력 자체는 권위·판정 조건을 바꾸지 않는다.
        for number in range(200):
            self.add('goal', '무관한 항목 %d' % number)
        facts = [{'text': '사실 %d' % n, 'evidence': {'type': 'note', 'ref': '근거'}} for n in range(200)]
        self.checkpoint(facts=facts)
        self.assertEqual(self.state.load()['items'][0]['status'], 'active')
        self.assertEqual(self.check()[1], 0)
        self.edit(self.state.path('checkpoint-0001.json'), lambda v: v['covers'].pop(forbidden))
        self.assertIn('coverage_missing', self.codes())
        self.assertEqual(self.check()[0]['verdict'], 'restore')

    def handoff_fixture(self):
        self.add('forbidden', '리뷰만 하고 수정하지 말 것')
        self.checkpoint()
        value, _ = self.state.handoff()
        return Path(value['handoff'])

    def test_handoff_missing_forbidden(self):
        path = self.handoff_fixture()
        path.write_text('\n'.join(line for line in path.read_text(encoding='utf-8').splitlines()
                                  if not line.startswith('- [R1]')), encoding='utf-8')
        self.assertIn('handoff_mismatch', self.codes(handoff=path))

    def test_handoff_changed_quote(self):
        path = self.handoff_fixture()
        path.write_text(path.read_text(encoding='utf-8').replace('원문: "리뷰만', '원문: "구현만'), encoding='utf-8')
        self.assertIn('handoff_mismatch', self.codes(handoff=path))

    def test_handoff_changed_header(self):
        path = self.handoff_fixture()
        path.write_text(path.read_text(encoding='utf-8').replace('charter_sha256=', 'wrong='), encoding='utf-8')
        self.assertIn('handoff_mismatch', self.codes(handoff=path))

    def test_handoff_quote_outside_active_is_not_authority(self):
        path = self.handoff_fixture()
        body = path.read_text(encoding='utf-8')
        quote = next(line for line in body.splitlines(keepends=True) if '원문:' in line)
        path.write_text(body.replace(quote, '') + quote, encoding='utf-8')
        self.assertIn('handoff_mismatch', self.codes(handoff=path))

    def test_handoff_stale_text_outside_item_ids(self):
        # ID 없는 오래된 요구 추가, 진행 상태·외부 작업 변경도 원문 대조로 발견한다.
        self.add('forbidden', '리뷰만 하고 수정하지 말 것')
        self.checkpoint(external_actions=self.external('done'))
        path = Path(self.state.handoff()[0]['handoff'])
        original = path.read_text(encoding='utf-8')
        self.assertNotIn('handoff_mismatch', self.codes(handoff=path))
        for damaged in (original.replace('## 대체된 조건', '- README도 갱신할 것\n## 대체된 조건'),
                        original.replace('["검증"]', '["검증","README 갱신"]'),
                        original.replace('E1 push: done', 'E1 push: pending')):
            self.assertNotEqual(damaged, original)
            path.write_text(damaged, encoding='utf-8')
            self.assertIn('handoff_mismatch', self.codes(handoff=path))

    def test_supersede_changes_authority_and_coverage(self):
        old = self.add('done', '이전 완료 조건')
        self.checkpoint()
        new = self.add('done', '바뀐 완료 조건', replaces=[old])
        result, status = self.check()
        self.assertEqual(status, 1)
        self.assertEqual(result['active_items'], [new])
        self.assertEqual(result['superseded_items'], [old])
        self.assertIn('charter_changed', {r['code'] for r in result['reasons']})
        self.assertIn(new, next(r['detail'] for r in result['reasons'] if r['code'] == 'charter_changed'))
        with self.assertRaises(session.Invalid):
            self.checkpoint(covers={old: 'done', new: 'pending'})
        with self.assertRaises(session.Invalid):
            self.add('done', replaces=[old])
        self.checkpoint()
        made, _ = self.state.handoff()
        self.assertEqual(self.check(handoff=made['handoff'])[1], 0)
        path = Path(made['handoff'])
        path.write_text(path.read_text(encoding='utf-8').replace(
            '## 유효한 요구(active)\n', '## 유효한 요구(active)\n- [%s] done: 되살림\n' % old), encoding='utf-8')
        self.assertIn('handoff_mismatch', self.codes(handoff=path))

    def test_supersede_requires_user_quote(self):
        old = self.add()
        with self.assertRaises(session.Invalid):
            self.state.add('guideline', '대체', {'type': 'doc', 'path': 'AGENTS.md'}, replaces=[old])
        result = self.cli('supersede', 'issue-44', '--replaces', old, '--kind', 'goal', '--text', '대체')
        self.assertEqual(result.returncode, 4)
        self.assertEqual(self.state.load()['items'][0]['status'], 'active')

    def test_code_changed_tracked(self):
        self.checkpoint()
        self.put('app.txt', '수정')
        self.assertIn('code_changed', self.codes())

    def test_code_changed_untracked(self):
        self.checkpoint()
        self.put('new.txt', '새 파일')
        self.assertIn('code_changed', self.codes())

    def test_code_changed_records_included(self):
        self.checkpoint()
        # 임시 저장소에서만 기록 파일의 해시 포함 여부를 검증한다.
        self.put('docs/harness/records/test.md', '임시 검증')
        self.assertIn('code_changed', self.codes())

    def test_worktree_rename_and_delete(self):
        self.checkpoint()
        self.git('mv', 'app.txt', 'renamed.txt')
        self.assertIn('code_changed', self.codes())
        self.checkpoint()
        (self.root / 'renamed.txt').unlink()
        self.assertIn('code_changed', self.codes())

    def test_doc_changed(self):
        self.state.add('guideline', '지침', {'type': 'doc', 'path': 'AGENTS.md'})
        self.checkpoint()
        self.put('AGENTS.md', '# 변경\n')
        self.assertIn('doc_changed', self.codes())

    def test_doc_missing(self):
        self.state.add('guideline', '지침', {'type': 'doc', 'path': 'AGENTS.md'})
        self.checkpoint()
        (self.root / 'AGENTS.md').unlink()
        self.assertIn('doc_changed', self.codes())

    def test_run_invalid_real_record(self):
        reference = session.run.Run(self.root, 'regression')
        plan = {'version': 1, 'completion': '정적 검사', 'inputs': [], 'environment': [],
                'checks': [{'id': 'static', 'kind': 'static', 'required': True, 'role': 'verification',
                            'condition': '명령 성공', 'command': [sys.executable, '-B', '-c', 'print("checked")']}]}
        reference.init(plan)
        with mock.patch('sys.stdout'):
            self.assertTrue(reference.execute('static'))
        self.checkpoint(runs=['regression'])
        self.assertNotIn('run_invalid', self.codes())
        self.put('app.txt', '실행 후 변경')
        self.assertIn('run_invalid', self.codes())
        rows = self.check()[0]['runs']['regression']
        self.assertEqual(rows[0]['status'], 'invalid')
        self.assertTrue(rows[0]['reasons'])

    def test_run_missing_save_rejected(self):
        with self.assertRaises(session.run.Invalid):
            self.checkpoint(runs=['missing'])

    def test_run_missing_at_check(self):
        self.checkpoint()
        self.edit(self.state.path('checkpoint-0001.json'), lambda v: v.update(runs=['missing']))
        self.assertIn('run_missing', self.codes())

    def test_run_inspect_error_does_not_stop_other_checks(self):
        self.checkpoint(external_actions=self.external('unknown'))
        self.edit(self.state.path('checkpoint-0001.json'), lambda v: v.update(runs=['bad']))
        with mock.patch.object(session.run.Run, 'load', return_value=({}, {})), mock.patch.object(
                session.run.Run, 'inspect', side_effect=session.run.Invalid('손상')):
            codes = self.codes()
        self.assertTrue({'run_invalid', 'external_unconfirmed'} <= codes)

    def test_selection_reference_and_stale(self):
        selector = session.context.Selector(self.root)
        selected, _ = selector.select('docs', '지침')
        section = selected['sections'][0]['id']
        self.state.add('guideline', '선택 지침', {'type': 'doc', 'selection': 'docs', 'section': section})
        self.checkpoint(selections=['docs'])
        self.assertEqual(self.check()[1], 0)
        self.put('AGENTS.md', '# 바뀐 지침\n')
        self.assertTrue({'doc_changed', 'selection_stale'} <= self.codes())

    def test_selection_unknown_section_rejected(self):
        session.context.Selector(self.root).select('docs', '지침')
        with self.assertRaises(session.Invalid):
            self.state.add('guideline', '선택 지침', {'type': 'doc', 'selection': 'docs', 'section': 'none'})

    def test_selection_missing_rejected(self):
        with self.assertRaises(session.context.Invalid):
            self.checkpoint(selections=['missing'])

    def test_foreign_task(self):
        other = session.Session(self.root, 'other')
        other.init()
        self.state.path('charter.json').write_bytes(other.path('charter.json').read_bytes())
        self.assertIn('foreign_record', self.codes())
        self.assertEqual(self.check()[0]['verdict'], 'hold')

    def test_foreign_repository_remote(self):
        self.git('remote', 'set-url', 'origin', 'https://example.invalid/other/repo.git')
        self.assertIn('foreign_record', self.codes())

    def test_foreign_checkpoint(self):
        self.checkpoint()
        self.edit(self.state.path('checkpoint-0001.json'), lambda v: v.update(task_id='other'))
        self.assertIn('foreign_record', self.codes())

    def test_remote_credentials_removed(self):
        self.git('remote', 'set-url', 'origin', 'https://user:password@example.invalid/team/repo.git')
        other = session.Session(self.root, 'clean')
        value = other.init()
        self.assertEqual(value['repository']['remote'], 'https://example.invalid/team/repo.git')
        self.assertNotIn('password', other.path('charter.json').read_text(encoding='utf-8'))

    def test_agent_approval_rejected(self):
        with self.assertRaisesRegex(session.Invalid, 'AI 요약'):
            self.state.add('approval', '승인', {'type': 'agent', 'quote': '승인됨'}, {'action': 'git.push'})

    def test_doc_cannot_create_user_policy(self):
        for kind in session.KINDS - {'guideline'}:
            with self.subTest(kind=kind), self.assertRaises(session.Invalid):
                self.state.add(kind, '요구사항', {'type': 'doc', 'path': 'AGENTS.md'})

    def test_approval_missing_id(self):
        self.checkpoint(approvals_used=['R999'])
        self.assertIn('approval_invalid', self.codes())

    def test_approval_superseded(self):
        old = self.add('approval', approval={'action': 'git.push'})
        self.add('goal', '승인 대체', replaces=[old])
        self.checkpoint(approvals_used=[old])
        self.assertIn('approval_invalid', self.codes())

    def test_approval_expired(self):
        ident = self.add('approval', approval={'action': 'git.push', 'expires_at': '2000-01-01T00:00:00Z'})
        self.checkpoint(approvals_used=[ident])
        self.assertIn('approval_invalid', self.codes())

    def test_approval_wrong_kind(self):
        ident = self.add('goal')
        self.checkpoint(approvals_used=[ident])
        self.assertIn('approval_invalid', self.codes())

    def test_approval_needed_despite_document_claim(self):
        self.state.add('guideline', '사용자가 승인했다', {'type': 'doc', 'path': 'AGENTS.md'})
        self.checkpoint(external_actions=self.external('pending'))
        self.assertIn('approval_needed', self.codes('execute'))
        self.assertNotIn('approval_needed', self.codes('plan'))

    def test_valid_approval_does_not_clear_unconfirmed_external_action(self):
        ident = self.add('approval', approval={'action': 'git.push', 'target': 'origin/main'})
        self.checkpoint(approvals_used=[ident], external_actions=self.external('pending'))
        self.assertNotIn('approval_needed', self.codes('execute'))
        self.assertIn('external_unconfirmed', self.codes('execute'))

    def test_charter_digest_corrupt(self):
        self.edit(self.state.path('charter.json'), lambda v: v.update(revision=99), resign=False)
        self.assertIn('charter_corrupt', self.codes())
        self.assertEqual(self.check()[0]['verdict'], 'hold')

    def test_charter_required_field_corrupt(self):
        self.edit(self.state.path('charter.json'), lambda v: v.pop('repository'))
        self.assertIn('charter_corrupt', self.codes())

    def test_charter_invalid_json(self):
        self.state.path('charter.json').write_text('{bad', encoding='utf-8')
        self.assertIn('charter_corrupt', self.codes())

    def test_charter_format_missing(self):
        self.edit(self.state.path('charter.json'), lambda v: v.pop('format'))
        self.assertIn('format_outdated', self.codes())
        self.assertEqual(self.check()[0]['verdict'], 'hold')

    def test_charter_format_outdated(self):
        self.edit(self.state.path('charter.json'), lambda v: v.update(format='session-charter/0'))
        self.assertIn('format_outdated', self.codes())

    def test_latest_corrupt_restores_previous(self):
        first = self.checkpoint()
        self.checkpoint()
        self.edit(self.state.path('checkpoint-0002.json'), lambda v: v.update(phase='other'), resign=False)
        result, status = self.check()
        self.assertEqual(status, 1)
        self.assertEqual(result['checkpoint']['sha256'], first['sha256'])
        self.assertIn('checkpoint_corrupt', {r['code'] for r in result['reasons']})

    def test_only_checkpoint_corrupt_new_session(self):
        self.checkpoint()
        self.state.path('checkpoint-0001.json').write_text('null', encoding='utf-8')
        self.assertEqual(self.check()[0]['verdict'], 'hold')
        self.assertIn('format_outdated', self.codes())
        self.state.path('checkpoint-0001.json').write_text('{bad', encoding='utf-8')
        self.assertEqual(self.check()[0]['verdict'], 'new_session')
        self.assertIn('checkpoint_corrupt', self.codes())

    def test_checkpoint_broken_previous_hash(self):
        self.checkpoint()
        self.checkpoint()
        self.edit(self.state.path('checkpoint-0002.json'), lambda v: v.update(prev_sha256='0' * 64))
        self.assertIn('checkpoint_corrupt', self.codes())
        self.assertEqual(self.check()[0]['checkpoint']['seq'], 1)

    def test_checkpoint_sequence_gap(self):
        self.checkpoint()
        self.checkpoint()
        self.state.path('checkpoint-0002.json').rename(self.state.path('checkpoint-0003.json'))
        self.assertIn('checkpoint_corrupt', self.codes())
        with self.assertRaises(session.Invalid):
            self.checkpoint()

    def test_checkpoint_append_preserves_previous_bytes(self):
        self.checkpoint()
        path = self.state.path('checkpoint-0001.json')
        before = path.read_bytes()
        second = self.checkpoint()
        self.assertEqual(path.read_bytes(), before)
        self.assertEqual(second['seq'], 2)
        self.assertEqual(second['prev_sha256'], session.read(path)['sha256'])

    def test_interrupted_save_and_show(self):
        self.checkpoint()
        path = self.state.path('.charter.json.stopped.tmp')
        path.write_text('partial', encoding='utf-8')
        self.assertIn('interrupted_save', self.codes())
        self.assertEqual(self.check()[0]['verdict'], 'restore')
        self.assertIn(str(path), self.state.show())
        self.assertTrue(path.exists())

    def test_charter_missing(self):
        self.state.path('charter.json').unlink()
        self.assertIn('charter_missing', self.codes())
        self.assertEqual(self.check()[0]['verdict'], 'hold')
        value, status = self.state.handoff()
        self.assertEqual(status, 2)
        self.assertTrue(Path(value['handoff']).is_file())

    def test_checkpoint_corrupt_does_not_hide_charter_conflict(self):
        first, second = self.add(), self.add()
        self.state.conflict([first, second], '상충')
        self.checkpoint()
        self.state.path('checkpoint-0001.json').write_text('{bad', encoding='utf-8')
        self.assertTrue({'conflict_open', 'checkpoint_corrupt'} <= self.codes())

    def test_no_checkpoint_depends_on_boundary(self):
        self.assertEqual(self.check('plan')[0]['verdict'], 'continue')
        self.assertEqual(self.check('resume')[0]['verdict'], 'new_session')

    def test_external_done_is_not_blocked(self):
        self.checkpoint(external_actions=self.external('done'))
        result, status = self.check()
        self.assertEqual(status, 0)
        self.assertEqual(result['blocked_actions'], [])
        self.assertIn('external_done', self.codes())

    def test_external_unknown_blocked(self):
        self.checkpoint(external_actions=self.external('unknown'))
        result, status = self.check()
        self.assertEqual(status, 2)
        self.assertEqual(result['blocked_actions'], ['E1: push'])
        self.assertIn('external_unconfirmed', self.codes())

    def test_task_id_rejected(self):
        for ident in ('A', 'a.', '../x', '', 'x' * 65, '/root', 'x/y', 'x\\y'):
            with self.subTest(ident=ident), self.assertRaises(session.Invalid):
                session.Session(self.root, ident)

    def test_quote_secret_patterns_rejected(self):
        for quote in ('ghp_value', 'github_pat_value', 'sk-value', 'AKIA' + 'A' * 16,
                      '-----BEGIN PRIVATE KEY', 'xoxb-value', 'xoxp-value'):
            with self.subTest(quote=quote), self.assertRaises(session.Invalid):
                self.add(body=quote)

    def test_quote_length_rejected(self):
        for quote in ('', 'x' * 301):
            with self.subTest(length=len(quote)), self.assertRaises(session.Invalid):
                self.state.add('goal', '조건', {'type': 'user', 'quote': quote, 'origin': '원문'})

    def test_checkpoint_secret_rejected(self):
        with self.assertRaises(session.Invalid):
            self.checkpoint(remaining=['ghp_value'])

    def test_doc_scope_rejected(self):
        self.put('ai/local-state/private.md', 'private')
        self.put('ai/local-evidence/private.md', 'private')
        for name in ('../outside', str(self.root / 'AGENTS.md'), 'ai/local-state/private.md',
                     'ai/local-evidence/private.md', '.git/config', 'docs\\x', 'absent.md'):
            with self.subTest(name=name), self.assertRaises(session.Invalid):
                self.state.add('guideline', '지침', {'type': 'doc', 'path': name})

    def test_nonignored_writes_rejected(self):
        self.put('.gitignore', '')
        with self.assertRaises(session.Invalid):
            self.add()
        with self.assertRaises(session.Invalid):
            self.state.handoff()

    def test_tracked_local_state_writes_rejected(self):
        self.git('add', '-f', 'ai/local-state/session/issue-44/charter.json')
        with self.assertRaises(session.Invalid):
            self.add()

    def test_quote_tampered_restore(self):
        self.add()
        self.checkpoint()
        self.edit(self.state.path('charter.json'), lambda v: v['items'][0]['source'].update(quote='바뀐 원문'))
        self.assertIn('quote_tampered', self.codes())
        self.assertEqual(self.check()[0]['verdict'], 'restore')

    def test_approval_quote_tampered_hold(self):
        self.add('approval', approval={'action': 'git.commit'})
        self.checkpoint()
        self.edit(self.state.path('charter.json'), lambda v: v['items'][0]['source'].update(quote='바뀐 승인'))
        self.assertIn('quote_tampered', self.codes())
        self.assertEqual(self.check()[0]['verdict'], 'hold')

    def test_conflict_resolved_only_by_user_supersede(self):
        first, second = self.add(), self.add()
        self.state.conflict([first, second], '범위 충돌')
        self.checkpoint()
        self.assertIn('conflict_open', self.codes())
        new = self.add('goal', '명확한 범위', replaces=[first])
        self.assertEqual(self.state.load()['conflicts'][0]['resolved_by'], new)
        self.assertNotIn('conflict_open', self.codes())
        self.checkpoint()
        self.assertEqual(self.check()[1], 0)

    def test_forbidden_violated_hold(self):
        ident = self.add('forbidden')
        self.checkpoint(covers={ident: 'violated'})
        self.assertIn('forbidden_violated', self.codes())
        self.assertEqual(self.check()[1], 2)

    def test_checkpoint_coverage_storage_rejected(self):
        ident = self.add('forbidden')
        for covers in ({}, {ident: 'pending'}, {ident: 'kept', 'R999': 'done'}):
            with self.subTest(covers=covers), self.assertRaises(session.Invalid):
                self.checkpoint(covers=covers)

    def test_forbidden_invalid_saved_state_cannot_continue(self):
        ident = self.add('forbidden')
        self.checkpoint()
        self.edit(self.state.path('checkpoint-0001.json'), lambda v: v['covers'].update({ident: 'pending'}))
        self.assertIn('coverage_missing', self.codes())
        self.assertEqual(self.check()[1], 1)

    def test_cli_add_supersede_conflict_checkpoint_show(self):
        for label in ('처음 조건', '다른 조건'):
            added = self.cli('add', 'issue-44', '--kind', 'goal', '--text', label,
                             '--quote', label, '--origin', '원문')
            self.assertEqual(added.returncode, 0, added.stderr)
        conflict = self.cli('conflict', 'issue-44', '--items', 'R1', 'R2', '--note', '상충')
        self.assertEqual(conflict.returncode, 0, conflict.stderr)
        replaced = self.cli('supersede', 'issue-44', '--replaces', 'R1', '--kind', 'goal',
                            '--text', '새 조건', '--quote', '새 조건', '--origin', '후속 원문')
        self.assertEqual(replaced.returncode, 0, replaced.stderr)
        path = self.put('ai/local-state/session/issue-44/input.json', json.dumps(self.input(), ensure_ascii=False))
        saved = self.cli('checkpoint', 'issue-44', '--input', str(path))
        self.assertEqual(saved.returncode, 0, saved.stderr)
        shown = self.cli('show', 'issue-44')
        self.assertEqual(shown.returncode, 0, shown.stderr)
        self.assertIn('원문:', shown.stdout)
        self.assertIn('새 조건', shown.stdout)

    def test_uncertain_is_ai_reported(self):
        self.checkpoint(self_assessment={'state': 'uncertain', 'reason': '맥락 불확실'})
        result, status = self.check()
        self.assertEqual(status, 3)
        reason = next(r for r in result['reasons'] if r['code'] == 'self_uncertain')
        self.assertEqual(reason['kind'], 'ai_reported')

    def test_verdict_priority(self):
        self.checkpoint(self_assessment={'state': 'uncertain', 'reason': '맥락 불확실'},
                        external_actions=self.external('unknown'))
        self.put('app.txt', '변경')
        self.assertTrue({'self_uncertain', 'code_changed', 'external_unconfirmed'} <= self.codes())
        self.assertEqual(self.check()[1], 2)

    def test_branch_changed(self):
        self.checkpoint()
        self.git('checkout', '-b', 'other')
        self.assertIn('branch_changed', self.codes())

    def test_cli_non_utf8_mode(self):
        self.add(body='한글 요구사항')
        self.checkpoint()
        result = self.cli('check', 'issue-44', '--boundary', 'resume', non_utf8=True, encoding='cp949')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('읽기·조사', json.loads(result.stdout)['allowed'])

    def test_cli_input_error_is_one_korean_line(self):
        result = self.cli('check', 'issue-44')
        self.assertEqual(result.returncode, 4)
        self.assertEqual(result.stdout, '')
        self.assertEqual(len(result.stderr.splitlines()), 1)
        self.assertIn('인자 오류', result.stderr)

    def test_cli_all_exit_codes(self):
        self.assertEqual(self.cli('check', 'issue-44', '--boundary', 'resume').returncode, 3)
        self.checkpoint()
        self.put('app.txt', '변경')
        self.assertEqual(self.cli('check', 'issue-44', '--boundary', 'resume').returncode, 1)
        self.state.path('charter.json').unlink()
        self.assertEqual(self.cli('check', 'issue-44', '--boundary', 'resume').returncode, 2)
        self.assertEqual(self.cli('init', 'A').returncode, 4)

    def test_atomic_save_failure_leaves_trace(self):
        original = self.state.path('charter.json').read_bytes()
        with mock.patch.object(session.os, 'replace', side_effect=OSError('중단')):
            with self.assertRaises(OSError):
                self.add()
        self.assertEqual(self.state.path('charter.json').read_bytes(), original)
        self.assertIn('interrupted_save', self.codes())

    @unittest.skipUnless(os.name == 'nt', 'Windows 정션 전용')
    def test_state_parent_junction_rejected(self):
        with TemporaryDirectory() as outside:
            junction = self.root / 'ai/local-state/session/link'
            subprocess.run(['cmd.exe', '/d', '/c', 'mklink', '/J', str(junction), outside],
                           check=True, capture_output=True)
            try:
                with self.assertRaises(session.Invalid):
                    session.Session(self.root, 'link')
            finally:
                os.rmdir(junction)


if __name__ == '__main__':
    unittest.main()
