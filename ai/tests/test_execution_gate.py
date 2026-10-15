"""실제 임시 Git·파일·합성 게시 프로세스로 중개 경로의 전달 여부를 검사한다."""
from copy import deepcopy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
import time
import unittest
from unittest.mock import patch

HARNESS = Path(__file__).resolve().parents[1] / 'harness'
SPEC = importlib.util.spec_from_file_location('execution_gate', HARNESS / 'execution_gate.py')
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)
with patch.dict(sys.modules, execution_gate=gate):
    SPEC_AUTH = importlib.util.spec_from_file_location('execution_authority', HARNESS / 'execution_authority.py')
    authority = importlib.util.module_from_spec(SPEC_AUTH)
    SPEC_AUTH.loader.exec_module(authority)


@unittest.skipUnless(os.name == 'posix', '현재 실행 중개 어댑터는 POSIX 전용; 기존 run.py Windows 지원 유지')
class ExecutionTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / 'repo'
        self.root.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.name', 'Synthetic')
        self.git('config', 'user.email', 'synthetic@example.invalid')
        self.git('remote', 'add', 'origin', 'https://github.com/synthetic/execution-test.git')
        (self.root / 'allowed').mkdir()
        (self.root / 'allowed/file.txt').write_text('before')
        (self.root / 'outside.txt').write_text('outside')
        (self.root / 'AGENTS.md').write_text('protected')
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic baseline')
        self.git('switch', '-qc', 'codex/synthetic')
        self.marker = self.base / 'publisher-starts.txt'
        self.fake = self.base / 'fake-gh'
        # 합성 게시 도구: stdout의 비밀값·본문을 게이트가 저장하지 않는지도 검사한다.
        self.fake.write_text('#!' + sys.executable + '\nfrom pathlib import Path\nimport sys\n'
                             + 'p=Path(' + repr(str(self.marker)) + ')\n'
                             + 'with p.open("a") as f: f.write("started\\n")\n'
                             + 'print("SYNTHETIC_SECRET_DO_NOT_STORE")\n'
                             + 'sys.exit(0)\n')
        self.fake.chmod(0o700)
        self.policy = {'version': 1, 'task_id': 'synthetic', 'run_id': 'synthetic-run',
                       'root': str(self.root), 'repository': 'synthetic/execution-test',
                       'allowed_paths': ['allowed'], 'protected_paths': ['allowed/private'],
                       'approval_required': sorted(gate.SENSITIVE), 'binaries': {
                           'git': {'path': shutil.which('git'), 'sha256': gate.file_hash(shutil.which('git'))},
                           'gh': {'path': str(self.fake), 'sha256': gate.file_hash(self.fake)}}}
        self.directory = self.base / 'authority'
        authority.initialize(self.directory, self.policy)
        self.broker = gate.Broker(self.directory)

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], check=True,
                              capture_output=True, env=gate.tool_environment()).stdout.decode().strip()

    def request(self, tool='file.write', args=None, request_id='request-1'):
        if args is None:
            args = {'path': 'allowed/file.txt', 'content': 'after',
                    'expected_sha256': gate.file_hash(self.root / 'allowed/file.txt')}
        return {'id': request_id, 'task_id': 'synthetic', 'cwd': str(self.root), 'tool': tool, 'args': args}

    def publish(self, request_id='publish-1'):
        return self.request('github.issue_comment', {'issue': 1, 'body': 'synthetic comment'}, request_id)

    def grant(self, request):
        # 테스트 전용 합성 사용자 confirm. 실제 사용자 승인·OS 격리 증거가 아니다.
        return authority.approve(self.broker, request, 'synthetic-user-ref', lambda *_: True)

    def assert_blocked(self, request, decision='deny'):
        before = (self.root / 'allowed/file.txt').read_bytes()
        with patch.object(gate.Broker, 'invoke', side_effect=AssertionError('대상 도구 전달 금지')):
            result = self.broker.execute(request)
        self.assertEqual(result['decision'], decision, result)
        self.assertEqual(result['status'], 'not_started')
        self.assertEqual((self.root / 'allowed/file.txt').read_bytes(), before)
        self.assertFalse(self.marker.exists())
        return result

    def test_allowed_write_real_file_once_and_preserve_mode(self):
        path = self.root / 'allowed/file.txt'
        path.chmod(0o640)
        request = self.request()
        result = self.broker.execute(request)
        self.assertEqual(result['status'], 'succeeded')
        self.assertEqual(path.read_text(), 'after')
        self.assertEqual(path.stat().st_mode & 0o777, 0o640)
        with patch.object(gate.Broker, 'invoke', side_effect=AssertionError('중복 전달')):
            replay = self.broker.execute(request)
        self.assertTrue(replay['replayed'])
        self.assertEqual(replay['status'], 'succeeded')

    def test_new_file_and_deleted_target_precondition(self):
        request = self.request(args={'path': 'allowed/new.txt', 'content': 'new', 'expected_sha256': None})
        self.assertEqual(self.broker.execute(request)['status'], 'succeeded')
        self.assertEqual((self.root / 'allowed/new.txt').read_text(), 'new')
        (self.root / 'allowed/file.txt').unlink()
        request = self.request(args={'path': 'allowed/file.txt', 'content': 'x', 'expected_sha256': 'bad'})
        self.assertEqual(self.broker.execute(request)['rule'], 'replay')

    def test_outside_and_protected_paths_no_write(self):
        for name in ['outside.txt', 'AGENTS.md', 'allowed/private', '.git/config',
                     '.codex/hooks.json', 'ai/harness/execution_gate.py', 'allowed/private/file']:
            with self.subTest(path=name):
                request = self.request(args={'path': name, 'content': 'bad', 'expected_sha256': None})
                self.assert_blocked(request)
        self.assertEqual((self.root / 'outside.txt').read_text(), 'outside')
        self.assertEqual((self.root / 'AGENTS.md').read_text(), 'protected')

    def test_escape_absolute_windows_and_noncanonical_paths(self):
        for name in ['../outside.txt', str(self.base / 'escape'), '/etc/passwd',
                     'C:/Windows/x', 'allowed/../outside.txt', 'allowed//x', './allowed/x', 'allowed\\x']:
            with self.subTest(path=name):
                self.assert_blocked(self.request(args={'path': name, 'content': 'x', 'expected_sha256': None}))
        self.assertFalse((self.base / 'escape').exists())

    def test_symlink_parent_leaf_and_hardlink(self):
        (self.root / 'allowed/link').symlink_to(self.base, target_is_directory=True)
        (self.root / 'allowed/leaf').symlink_to(self.root / 'AGENTS.md')
        os.link(self.root / 'AGENTS.md', self.root / 'allowed/hardlink')
        for name in ['allowed/link/escape', 'allowed/leaf', 'allowed/hardlink']:
            self.assert_blocked(self.request(args={'path': name, 'content': 'x', 'expected_sha256': None}))
        self.assertFalse((self.base / 'escape').exists())
        self.assertEqual((self.root / 'AGENTS.md').read_text(), 'protected')

    def test_case_and_unicode_protected_alias(self):
        self.policy['protected_paths'].append('allowed/비밀')
        for name in ['agents.md', '.GIT/config', 'allowed/비밀']:
            with self.subTest(path=name):
                self.assertTrue(any(gate.within(name, p) for p in gate.PROTECTED + self.policy['protected_paths']))

    def test_unknown_shell_compound_and_pr_body_no_process(self):
        for tool in ['shell', 'exec_command', 'github.pr_update', 'approval.grant']:
            self.assert_blocked(self.request(tool, {'command': 'echo approved; touch sentinel'}))

    def test_schema_task_and_cwd(self):
        for field, value in [('task_id', 'other'), ('cwd', str(self.base)), ('args', []), ('id', '../id')]:
            request = self.request()
            request[field] = value
            self.assert_blocked(request)
        request = self.request()
        request['approved'] = True
        self.assert_blocked(request)

    def test_approval_required_no_external_process(self):
        self.assert_blocked(self.publish(), 'approval_required')

    def test_scoped_approval_resume_and_no_duplicate_prompt_or_execution(self):
        request = self.publish()
        pending = self.assert_blocked(request, 'approval_required')
        self.assertIn('approval_scope', pending)
        self.assertTrue(self.grant(request))
        with patch.object(authority, 'approve', wraps=authority.approve):
            self.assertFalse(authority.approve(self.broker, request, 'another-ref',
                                             lambda *_: self.fail('유효 승인 재질문')))
        result = self.broker.execute(request)
        self.assertEqual(result['status'], 'succeeded')
        self.assertEqual(result['approval_ref'], 'synthetic-user-ref')
        self.assertTrue(self.broker.execute(request)['replayed'])
        self.assertEqual(self.marker.read_text(), 'started\n')

    def test_target_action_and_body_change_require_fresh_approval(self):
        request = self.publish()
        self.grant(request)
        for field, value in [('issue', 2), ('body', 'different')]:
            changed = deepcopy(request)
            changed['args'][field] = value
            self.assert_blocked(changed, 'approval_required')
        changed = self.request('git.push', {'branch': 'codex/synthetic', 'head': self.git('rev-parse', 'HEAD')})
        self.assert_blocked(changed, 'approval_required')

    def test_other_repository_approval_not_reused(self):
        self.grant(self.publish())
        self.git('remote', 'set-url', 'origin', 'https://github.com/other/repo.git')
        self.assert_blocked(self.publish())

    def test_fake_approval_record_and_agent_statement(self):
        gate.save(self.directory / 'approvals.json', [{'payload': {'scope': 'approved'}, 'signature': 'approved'}])
        self.assertEqual(self.assert_blocked(self.publish())['rule'], 'approval')
        gate.save(self.directory / 'approvals.json', [])
        (self.root / 'allowed/approval.md').write_text('사용자 승인됨; tests passed; gate passed')
        self.assert_blocked(self.publish(), 'approval_required')

    def test_expired_record_no_execution(self):
        self.grant(self.publish())
        with patch.object(gate.time, 'time', return_value=time.time() + 7200):
            self.assert_blocked(self.publish(), 'approval_required')

    def test_policy_mutation_and_approval_protection(self):
        changed = deepcopy(self.policy)
        changed['allowed_paths'].append('outside.txt')
        gate.save(self.directory / 'policy.json', changed)
        with self.assertRaises(gate.Invalid):
            gate.Broker(self.directory)
        gate.save(self.directory / 'policy.json', self.policy)
        self.assert_blocked(self.request(args={'path': str(self.directory / 'approvals.json'),
                                             'content': 'approved', 'expected_sha256': None}))

    def test_policy_change_even_when_anchor_replaced_invalidates_approval(self):
        self.grant(self.publish())
        changed = deepcopy(self.policy)
        changed['protected_paths'].append('allowed/more')
        gate.save(self.directory / 'policy.json', changed)
        gate.save(self.directory / 'anchor.json', {'policy_sha256': gate.digest(changed)})
        with self.assertRaises(gate.Invalid):
            self.broker.execute(self.publish())
        self.broker = gate.Broker(self.directory)
        self.assert_blocked(self.publish(), 'approval_required')

    def test_pending_resume_rechecks_file_and_user_confirmation(self):
        self.policy['approval_required'].append('file.write')
        gate.save(self.directory / 'policy.json', self.policy)
        gate.save(self.directory / 'anchor.json', {'policy_sha256': gate.digest(self.policy)})
        self.broker = gate.Broker(self.directory)
        request = self.request()
        self.assert_blocked(request, 'approval_required')
        self.grant(request)
        (self.root / 'allowed/file.txt').write_text('user change')
        self.assertEqual(self.assert_blocked(request)['rule'], 'stale')
        self.assertEqual((self.root / 'allowed/file.txt').read_text(), 'user change')

    def test_change_while_user_is_deciding_not_signed(self):
        self.policy['approval_required'].append('file.write')
        gate.save(self.directory / 'policy.json', self.policy)
        gate.save(self.directory / 'anchor.json', {'policy_sha256': gate.digest(self.policy)})
        self.broker = gate.Broker(self.directory)
        request = self.request()
        def confirm(*_):
            (self.root / 'allowed/file.txt').write_text('concurrent')
            return True
        with self.assertRaises(gate.Invalid):
            authority.approve(self.broker, request, 'synthetic-user', confirm)
        self.assertEqual(gate.load(self.directory / 'approvals.json'), [])

    def test_integration_binary_missing_or_changed_no_process(self):
        self.fake.write_text('changed')
        self.assertEqual(self.assert_blocked(self.publish())['rule'], 'integration')
        self.fake.unlink()
        self.assert_blocked(self.publish())

    def test_actual_failed_publisher_once_not_success(self):
        self.fake.write_text(self.fake.read_text().replace('sys.exit(0)', 'sys.exit(7)'))
        self.policy['binaries']['gh']['sha256'] = gate.file_hash(self.fake)
        gate.save(self.directory / 'policy.json', self.policy)
        gate.save(self.directory / 'anchor.json', {'policy_sha256': gate.digest(self.policy)})
        self.broker = gate.Broker(self.directory)
        request = self.publish()
        self.grant(request)
        result = self.broker.execute(request)
        self.assertEqual((result['status'], result['exit_code']), ('failed', 7))
        self.assertTrue(self.broker.execute(request)['replayed'])
        self.assertEqual(self.marker.read_text(), 'started\n')

    def test_timeout_unknown_no_automatic_retry(self):
        request = self.publish()
        self.grant(request)
        with patch.object(gate.Broker, 'invoke', side_effect=subprocess.TimeoutExpired('synthetic', 1)):
            result = self.broker.execute(request)
        self.assertEqual(result['status'], 'unknown')
        self.assertTrue(self.broker.execute(request)['replayed'])
        self.assertFalse(self.marker.exists())

    def test_journal_failure_prevents_delivery_and_uncertain_after_delivery(self):
        request = self.publish()
        self.grant(request)
        with patch.object(gate, 'save', side_effect=OSError('disk full')):
            self.assert_blocked(request)
        real_save = gate.save
        calls = []
        def fail_after(path, value):
            calls.append(path)
            if len(calls) == 2:
                raise OSError('disk full after delivery')
            real_save(path, value)
        with patch.object(gate, 'save', side_effect=fail_after):
            result = self.broker.execute(request)
        self.assertEqual(result['status'], 'unknown')
        self.assertTrue(self.broker.execute(request)['replayed'])
        self.assertEqual(self.marker.read_text(), 'started\n')

    def commit_request(self):
        (self.root / 'allowed/file.txt').write_text('staged')
        self.git('add', 'allowed/file.txt')
        return self.request('git.commit', {'paths': ['allowed/file.txt'], 'message': 'test: synthetic',
                                          'head': self.git('rev-parse', 'HEAD'),
                                          'index_sha256': hashlib.sha256(self.broker.git('ls-files', '--stage', '-z')).hexdigest()})

    def test_real_git_commit_approval_and_hooks_disabled(self):
        request = self.commit_request()
        original = self.git('rev-parse', 'HEAD')
        self.assert_blocked(request, 'approval_required')
        self.assertEqual(self.git('rev-parse', 'HEAD'), original)
        hook = self.root / '.git/hooks/pre-commit'
        hook.write_text('#!/bin/sh\nexit 19\n')
        hook.chmod(0o700)
        self.grant(request)
        self.assertEqual(self.broker.execute(request)['status'], 'succeeded')
        self.assertNotEqual(self.git('rev-parse', 'HEAD'), original)
        self.assertTrue(self.broker.execute(request)['replayed'])

    def test_denied_commit_does_not_write_git_objects_index_or_refs(self):
        request = self.commit_request()
        def inventory():
            return {p.relative_to(self.root / '.git').as_posix(): p.read_bytes()
                    for p in (self.root / '.git').rglob('*') if p.is_file()}
        before = inventory()
        self.assert_blocked(request, 'approval_required')
        self.assertEqual(inventory(), before)

    def test_staged_outside_file_and_tree_change(self):
        request = self.commit_request()
        self.grant(request)
        (self.root / 'outside.txt').write_text('other')
        self.git('add', 'outside.txt')
        self.assertEqual(self.assert_blocked(request)['rule'], 'scope')
        request['args']['paths'].append('outside.txt')
        self.assertEqual(self.assert_blocked(request)['rule'], 'scope')

    def test_push_url_override_denied_before_push(self):
        self.git('config', 'remote.origin.pushurl', 'https://github.com/other/repo.git')
        request = self.request('git.push', {'branch': 'codex/synthetic', 'head': self.git('rev-parse', 'HEAD')})
        self.assertEqual(self.assert_blocked(request)['rule'], 'repository')

    def test_approved_push_actual_synthetic_process_once_no_network(self):
        real_git = self.policy['binaries']['git']['path']
        fake_git = self.base / 'fake-git'
        fake_git.write_text('#!' + sys.executable + '\nimport os,sys\nfrom pathlib import Path\n'
                            + 'if "push" in sys.argv[1:]:\n'
                            + ' assert sys.argv[-2:]==["origin","HEAD:refs/heads/codex/synthetic"]\n'
                            + ' assert "--no-follow-tags" in sys.argv and "--recurse-submodules=no" in sys.argv\n'
                            + ' with Path(' + repr(str(self.marker)) + ').open("a") as f: f.write("push\\n")\n'
                            + ' sys.exit(0)\n'
                            + 'os.execv(' + repr(real_git) + ',[' + repr(real_git) + ']+sys.argv[1:])\n')
        fake_git.chmod(0o700)
        self.policy['binaries']['git'] = {'path': str(fake_git), 'sha256': gate.file_hash(fake_git)}
        gate.save(self.directory / 'policy.json', self.policy)
        gate.save(self.directory / 'anchor.json', {'policy_sha256': gate.digest(self.policy)})
        self.broker = gate.Broker(self.directory)
        request = self.request('git.push', {'branch': 'codex/synthetic', 'head': self.git('rev-parse', 'HEAD')})
        self.assert_blocked(request, 'approval_required')
        self.grant(request)
        self.assertEqual(self.broker.execute(request)['status'], 'succeeded')
        self.assertTrue(self.broker.execute(request)['replayed'])
        self.assertEqual(self.marker.read_text(), 'push\n')

    def test_staged_symlink_disguised_as_normal_worktree_file_denied(self):
        request = self.commit_request()
        blob = subprocess.run(['git', '-C', str(self.root), 'hash-object', '-w', '--stdin'],
                              input=b'../../AGENTS.md', check=True, capture_output=True).stdout.decode().strip()
        self.git('update-index', '--cacheinfo', '120000,' + blob + ',allowed/file.txt')
        request['args']['index_sha256'] = hashlib.sha256(self.broker.git('ls-files', '--stage', '-z')).hexdigest()
        self.assertEqual(self.assert_blocked(request)['rule'], 'path')

    def test_publisher_receives_exact_host_endpoint_body_and_cwd(self):
        self.fake.write_text('#!' + sys.executable + '\nimport json,os,sys\n'
                             + 'assert os.getcwd()==' + repr(str(self.root)) + '\n'
                             + 'assert sys.argv[1:]==["api","--hostname","github.com","--method","POST",'
                             + '"repos/synthetic/execution-test/issues/1/comments","--input","-"]\n'
                             + 'assert json.load(sys.stdin)=={"body":"synthetic comment"}\n'
                             + 'assert "GH_HOST" not in os.environ\n')
        self.policy['binaries']['gh']['sha256'] = gate.file_hash(self.fake)
        gate.save(self.directory / 'policy.json', self.policy)
        gate.save(self.directory / 'anchor.json', {'policy_sha256': gate.digest(self.policy)})
        self.broker = gate.Broker(self.directory)
        request = self.publish()
        self.grant(request)
        with patch.dict(os.environ, {'GH_HOST': 'wrong.invalid'}):
            self.assertEqual(self.broker.execute(request)['status'], 'succeeded')

    def test_same_id_concurrent_cli_calls_only_one_real_publisher(self):
        from concurrent.futures import ThreadPoolExecutor
        request = self.publish()
        self.grant(request)
        command = [sys.executable, '-B', str(HARNESS / 'execution_gate.py'), '--authority', str(self.directory)]
        children = [subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE) for _ in range(2)]
        with ThreadPoolExecutor(max_workers=2) as pool:
            outputs = list(pool.map(lambda child: child.communicate(gate.encoded(request), timeout=15)[0], children))
        self.assertTrue(all(child.returncode == 0 for child in children), outputs)
        self.assertEqual(self.marker.read_text(), 'started\n')

    def test_git_environment_override_is_removed(self):
        request = self.request()
        with patch.dict(os.environ, {'GIT_DIR': '/missing', 'GIT_INDEX_FILE': '/missing', 'GH_HOST': 'evil.invalid'}):
            self.assertEqual(self.broker.execute(request)['status'], 'succeeded')

    def test_request_id_argument_change_rejected(self):
        request = self.request()
        self.assertEqual(self.broker.execute(request)['status'], 'succeeded')
        request['args']['content'] = 'another'
        self.assertEqual(self.assert_blocked(request)['rule'], 'replay')

    def test_minimal_audit_no_content_output_or_command(self):
        request = self.publish()
        request['args']['body'] = 'PRIVATE_BODY_SENTINEL'
        self.grant(request)
        self.broker.execute(request)
        for file in self.directory.iterdir():
            if file.name == 'key':
                continue
            content = file.read_text()
            self.assertNotIn('PRIVATE_BODY_SENTINEL', content)
            self.assertNotIn('SYNTHETIC_SECRET_DO_NOT_STORE', content)
        event = json.loads((self.directory / 'events.jsonl').read_text())
        self.assertEqual(event['run_id'], 'synthetic-run')
        self.assertEqual(event['approval_ref'], 'synthetic-user-ref')

    def test_cli_adapter_real_process_and_malformed_request_fail_closed(self):
        for request, expected in [(self.request(), 0), (self.publish(), 3),
                                  (self.request('shell', {'command': 'touch sentinel'}), 2)]:
            result = subprocess.run([sys.executable, '-B', str(HARNESS / 'execution_gate.py'),
                                     '--authority', str(self.directory)], input=gate.encoded(request),
                                    capture_output=True)
            self.assertEqual(result.returncode, expected, result.stdout)
        result = subprocess.run([sys.executable, '-B', str(HARNESS / 'execution_gate.py'),
                                 '--authority', str(self.directory)], input=b'{broken', capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(json.loads(result.stdout)['status'], 'not_started')
        self.assertFalse(self.marker.exists())

    def test_policy_validation_and_authority_boundaries(self):
        for field, value in [('approval_required', []), ('allowed_paths', ['../x']),
                             ('root', 'relative'), ('repository', 'x; shell')]:
            policy = deepcopy(self.policy)
            policy[field] = value
            with self.assertRaises((gate.Invalid, OSError)):
                gate.validate_policy(policy)
        with self.assertRaises(gate.Invalid):
            authority.initialize(self.root / 'authority', self.policy)
        (self.base / 'authority-link').symlink_to(self.directory, target_is_directory=True)
        with self.assertRaises(gate.Invalid):
            gate.Broker(self.base / 'authority-link')


if __name__ == '__main__':
    unittest.main()
