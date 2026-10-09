"""실제 임시 Git 저장소·합성 명령으로 로컬 실행 게이트를 검증한다."""
from copy import deepcopy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('harness_run', Path(__file__).resolve().parents[1] / 'harness/run.py')
harness = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(harness)


class RunTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.git('init', '-q')
        self.git('config', 'user.name', 'Synthetic')
        self.git('config', 'user.email', 'synthetic@example.invalid')
        self.put('.gitignore', 'ai/local-state/\n')
        self.put('product.txt', 'synthetic product')
        self.put('checks.conf', 'initial')
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic baseline')
        self.plan = {'version': 1, 'completion': 'all required checks pass', 'inputs': ['checks.conf'],
                     'environment': [], 'checks': [
                         {'id': 'test', 'kind': 'test', 'required': True, 'role': 'verification',
                          'condition': 'one real test', 'adapter': 'unittest',
                          'command': [sys.executable, '-c', 'import unittest; unittest.main(module=None, argv=["suite", "discover", "-s", "ai/local-state/suite"])']},
                         {'id': 'build', 'kind': 'build', 'required': True, 'role': 'implementation',
                          'condition': 'command executes', 'command': [sys.executable, '-c', 'pass']},
                         {'id': 'qa', 'kind': 'qa', 'required': True, 'role': 'verification',
                          'condition': 'synthetic flow', 'procedure': 'inspect synthetic flow', 'independent': False},
                         {'id': 'review', 'kind': 'review', 'required': True, 'role': 'review',
                          'condition': 'diff reviewed', 'procedure': 'review diff', 'independent': True}]}
        self.put('ai/local-state/suite/test_synthetic.py', 'import unittest\nclass T(unittest.TestCase):\n def test_real(self): self.assertEqual(2+2,4)\n')
        self.run = harness.Run(self.root, 'run-1')
        self.run.init(self.plan)

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], check=True, capture_output=True)

    def put(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        return path

    def save_plan(self):
        harness.write(self.run.directory / 'plan.json', self.plan)

    def manual(self, item='qa', independent=True):
        self.run.begin(item)
        self.put('ai/local-state/evidence.txt', '합성 확인 범위: CLI와 상태만 확인, 실제 사용자 데이터 없음')
        return self.run.record(item, 'ai/local-state/evidence.txt', 'synthetic flow and diff',
                               'current Codex synthetic reporter', independent, '확인 완료')

    def complete(self):
        self.assertTrue(self.run.execute('test'))
        self.assertTrue(self.run.execute('build'))
        self.manual()
        self.manual('review')
        self.assertFalse(any(r['reasons'] for r in self.run.inspect() if r['required']))

    def test_normal_record_show_gate_and_git_exclusion(self):
        self.complete()
        plan, state = self.run.load()
        result = state['results']['test']
        self.assertEqual(result['counts'], {'executed': 1, 'failures': 0, 'errors': 0, 'skipped': 0})
        self.assertTrue(result['started'] <= result['ended'])
        self.assertEqual(result['role'], 'verification')
        self.assertIn('harness', result['snapshot'])
        self.assertEqual(self.git('ls-files', 'ai/local-state').stdout, b'')
        self.assertEqual(harness.main(['--root', str(self.root), 'gate', 'run-1']), 0)
        self.assertEqual(harness.main(['--root', str(self.root), 'show', 'run-1']), 0)

    def test_invalid_plan_fields(self):
        for field, value in [('completion', ''), ('version', 2), ('checks', []), ('inputs', None), ('environment', 'bad')]:
            with self.subTest(field=field):
                plan = deepcopy(self.plan)
                plan[field] = value
                with self.assertRaises(harness.Invalid):
                    harness.validate_plan(plan)
        for field in ('role', 'condition', 'command', 'adapter'):
            plan = deepcopy(self.plan)
            del plan['checks'][0][field]
            with self.assertRaises(harness.Invalid):
                harness.validate_plan(plan)

    def test_duplicate_and_no_required_checks(self):
        plan = deepcopy(self.plan)
        plan['checks'].append(plan['checks'][0])
        with self.assertRaises(harness.Invalid): harness.validate_plan(plan)
        plan = deepcopy(self.plan)
        for c in plan['checks']: c['required'] = False
        with self.assertRaises(harness.Invalid): harness.validate_plan(plan)

    def test_missing_running_failed_and_completed_text_do_not_pass(self):
        for status in ('not_run', 'running', 'failed', 'invalid'):
            plan, state = self.run.load()
            state['completed'] = True
            state['results']['test'] = {'status': status}
            self.run.save(state)
            self.assertTrue(self.run.inspect()[0]['reasons'])
            self.assertEqual(harness.main(['--root', str(self.root), 'gate', 'run-1']), 1)
        state['results']['test'] = {'status': 'completed'}
        self.run.save(state)
        with self.assertRaises(harness.Invalid): self.run.inspect()

    def test_zero_and_skipped_tests(self):
        for code in ('import unittest; unittest.main()',
                     'import unittest\nclass T(unittest.TestCase):\n @unittest.skip("synthetic")\n def test_skip(self): pass\nunittest.main()'):
            self.plan['checks'][0]['command'] = [sys.executable, '-c', code]
            self.save_plan()
            self.assertFalse(self.run.execute('test'))
            self.assertEqual(self.run.inspect()[0]['status'], 'failed')

    def test_test_failures_errors_and_missing_summary(self):
        for code in ('import unittest\nclass T(unittest.TestCase):\n def test_fail(self): self.fail()\nunittest.main()',
                     'import unittest\nclass T(unittest.TestCase):\n def test_error(self): raise RuntimeError()\nunittest.main()', 'pass'):
            self.plan['checks'][0]['command'] = [sys.executable, '-c', code]
            self.save_plan()
            self.assertFalse(self.run.execute('test'))

    def test_failed_command_and_cache_are_not_new_pass(self):
        for code in ('raise SystemExit(7)', 'print("FROM-CACHE")', 'print("UP-TO-DATE")', 'print("NO-SOURCE")'):
            self.plan['checks'][1]['command'] = [sys.executable, '-c', code]
            self.save_plan()
            self.assertFalse(self.run.execute('build'))

    def test_missing_executable(self):
        self.plan['checks'][1]['command'] = ['synthetic-nonexistent-executable']
        self.save_plan()
        self.assertFalse(self.run.execute('build'))

    def test_bad_test_evidence_preserves_actual_exit_code(self):
        self.plan['checks'][0]['command'] = [sys.executable, '-c', 'pass']
        self.save_plan()
        self.assertFalse(self.run.execute('test'))
        self.assertEqual(self.run.load()[1]['results']['test']['exit_code'], 0)

    def test_receipt_missing_modified_and_forged_pass(self):
        self.assertTrue(self.run.execute('build'))
        _, state = self.run.load()
        path = self.run.directory / state['results']['build']['receipt']
        path.write_text('{}', encoding='utf-8')
        self.assertTrue(self.run.inspect()[1]['reasons'])
        path.unlink()
        self.assertTrue(self.run.inspect()[1]['reasons'])
        state['results']['build'] = {'status': 'passed', 'summary': 'completed'}
        self.run.save(state)
        self.assertTrue(self.run.inspect()[1]['reasons'])

    def test_same_commit_dirty_tracked_and_untracked_invalidate_all(self):
        self.complete()
        original = self.git('rev-parse', 'HEAD').stdout
        for name in ('product.txt', 'related-new.txt'):
            with self.subTest(name=name):
                self.put(name, 'changed')
                self.assertEqual(self.git('rev-parse', 'HEAD').stdout, original)
                rows = self.run.inspect()
                self.assertTrue(all(r['status'] == 'invalid' for r in rows))
                if name == 'product.txt': self.put(name, 'synthetic product')
                else: (self.root / name).unlink()
                self.assertFalse(any(r['reasons'] for r in self.run.inspect()))

    def test_deleted_product_and_harness_changes_invalidate(self):
        self.complete()
        (self.root / 'product.txt').unlink()
        self.assertTrue(all(r['reasons'] for r in self.run.inspect()))
        self.put('product.txt', 'synthetic product')
        self.put('ai/harness/new.py', '# synthetic harness version')
        self.assertTrue(all(r['reasons'] for r in self.run.inspect()))

    def test_config_environment_and_completion_changes(self):
        self.complete()
        self.put('checks.conf', 'changed')
        self.assertTrue(all(r['reasons'] for r in self.run.inspect()))
        self.put('checks.conf', 'initial')
        self.plan['completion'] = 'new mandatory condition'
        self.save_plan()
        self.assertTrue(all(r['reasons'] for r in self.run.inspect()))
        self.plan['completion'] = 'all required checks pass'
        self.plan['environment'] = ['HARNESS_SYNTHETIC_ENV']
        self.save_plan()
        with self.assertRaises(harness.Invalid): self.run.inspect()
        with patch.dict(os.environ, {'HARNESS_SYNTHETIC_ENV': 'A'}):
            self.complete()
            with patch.dict(os.environ, {'HARNESS_SYNTHETIC_ENV': 'B'}):
                self.assertTrue(all(r['reasons'] for r in self.run.inspect()))

    def test_ignored_configuration_is_explicitly_watched(self):
        self.put('.gitignore', 'ai/local-state/\nignored.conf\n')
        self.put('ignored.conf', 'A')
        self.plan['inputs'].append('ignored.conf')
        self.save_plan()
        self.complete()
        self.put('ignored.conf', 'B')
        self.assertTrue(all(r['reasons'] for r in self.run.inspect()))

    def test_local_state_only_changes_do_not_invalidate(self):
        self.complete()
        self.put('ai/local-state/arbitrary.json', '{}')
        _, state = self.run.load()
        state['note'] = 'summary only'
        self.run.save(state)
        self.assertFalse(any(r['reasons'] for r in self.run.inspect()))
        self.put('docs/harness/records/synthetic.md', '결과 요약만 갱신')
        self.assertFalse(any(r['reasons'] for r in self.run.inspect()))

    def test_optional_failure_does_not_block_required_checks(self):
        self.complete()
        plan, state = self.run.load()
        plan['checks'].append({'id': 'optional', 'kind': 'static', 'required': False, 'role': 'verification',
                               'condition': 'optional diagnostics', 'command': [sys.executable, '-c', 'raise SystemExit(9)']})
        harness.write(self.run.directory / 'plan.json', plan)
        self.complete()
        self.assertFalse(self.run.execute('optional'))
        self.assertEqual(harness.main(['--root', str(self.root), 'gate', 'run-1']), 0)

    def test_malformed_snapshot_and_timestamps_are_rejected(self):
        self.assertTrue(self.run.execute('build'))
        _, state = self.run.load()
        original = deepcopy(state['results']['build'])
        for key, value in (('snapshot', None), ('ended', 'not-a-time'), ('counts', 'wrong-type'), ('exit_code', True)):
            if key == 'counts': continue  # build에는 테스트 건수를 적용하지 않는다.
            state['results']['build'] = dict(original, **{key: value})
            self.run.save(state)
            self.assertTrue(self.run.inspect()[1]['reasons'])

    def test_unavailable_inputs_and_empty_manual_evidence(self):
        (self.root / 'checks.conf').unlink()
        with self.assertRaises(harness.Invalid): self.run.inspect()
        self.put('checks.conf', 'initial')
        self.run.begin('qa')
        self.put('ai/local-state/empty.md', '')
        with self.assertRaises(harness.Invalid):
            self.run.record('qa', 'ai/local-state/empty.md', 'scope', 'self', False, 'summary')

    def test_resume_preserves_valid_and_guides_interrupted_only(self):
        self.assertTrue(self.run.execute('test'))
        self.run.begin('qa')
        _, before = self.run.load()
        rows = self.run.inspect()
        self.assertEqual(rows[0]['reasons'], [])
        self.assertEqual(rows[2]['status'], 'running')
        self.assertIn('중단', rows[2]['reasons'][0])
        self.assertEqual(harness.main(['--root', str(self.root), 'resume', 'run-1']), 1)
        self.assertEqual(self.run.load()[1], before)

    def test_manual_requires_begin_and_stale_review_qa(self):
        with self.assertRaises(harness.Invalid):
            self.run.record('qa', 'missing', 'scope', 'reporter', False, 'summary')
        for item in ('qa', 'review'):
            self.run.begin(item)
            self.put('ai/local-state/manual.txt', 'review evidence')
            self.put('product.txt', 'changed during manual review')
            self.assertFalse(self.run.record(item, 'ai/local-state/manual.txt', 'diff', 'self', True, 'reviewed'))
            self.assertEqual(next(r for r in self.run.inspect() if r['id'] == item)['status'], 'invalid')
            self.put('product.txt', 'synthetic product')

    def test_independence_and_manual_evidence_missing(self):
        self.manual('review', independent=False)
        self.assertTrue(self.run.inspect()[3]['reasons'])
        self.manual('review', independent=True)
        self.assertFalse(self.run.inspect()[3]['reasons'])
        (self.root / 'ai/local-state/evidence.txt').unlink()
        self.assertTrue(self.run.inspect()[3]['reasons'])

    def test_missing_and_corrupted_state_and_plan(self):
        for name in ('state.json', 'plan.json'):
            path = self.run.directory / name
            original = path.read_bytes()
            for value in ('{broken', 'null', '{}'):
                path.write_text(value, encoding='utf-8')
                self.assertEqual(harness.main(['--root', str(self.root), 'resume', 'run-1']), 2)
            path.unlink()
            self.assertEqual(harness.main(['--root', str(self.root), 'gate', 'run-1']), 2)
            path.write_bytes(original)

    def test_reuse_is_marked_and_invalid_source_rejected(self):
        self.complete()
        other = harness.Run(self.root, 'run-2')
        other.init(self.plan)
        other.reuse('run-1', 'test', 'same code and environment already verified')
        result = other.load()[1]['results']['test']
        self.assertEqual(result['mode'], 'reused')
        self.assertEqual(result['reuse']['source'], 'run-1')
        self.assertEqual(other.inspect()[0]['reasons'], [])
        self.put('product.txt', 'changed')
        with self.assertRaises(harness.Invalid): other.reuse('run-1', 'build', 'old result')

    def test_junit_fresh_report_counts_and_missing_evidence(self):
        check = self.plan['checks'][0]
        check.update(adapter='junit', report='ai/local-state/report.xml',
                     command=[sys.executable, '-c', 'from pathlib import Path; Path("ai/local-state/report.xml").write_text("<testsuite><testcase name=\'real\'/></testsuite>")'])
        self.save_plan()
        self.assertTrue(self.run.execute('test'))
        self.assertEqual(self.run.inspect()[0]['reasons'], [])
        with self.assertRaises(harness.Invalid): self.run.execute('test')
        (self.root / check['report']).unlink()
        self.assertTrue(self.run.inspect()[0]['reasons'])

    def test_junit_empty_skipped_failed_and_malformed(self):
        check = self.plan['checks'][0]
        for index, xml in enumerate(('<testsuite/>', '<testsuite><testcase><skipped/></testcase></testsuite>',
                                     '<testsuite><testcase><failure/></testcase></testsuite>', 'broken')):
            check.update(adapter='junit', report=f'ai/local-state/report-{index}.xml')
            check['command'] = [sys.executable, '-c', f'from pathlib import Path; Path({check["report"]!r}).write_text({xml!r})']
            self.save_plan()
            self.assertFalse(self.run.execute('test'))

    def test_automatic_change_during_command_invalidates(self):
        self.plan['checks'][1]['command'] = [sys.executable, '-c', 'from pathlib import Path; Path("product.txt").write_text("changed during run")']
        self.save_plan()
        self.assertFalse(self.run.execute('build'))
        self.assertEqual(self.run.inspect()[1]['status'], 'invalid')

    def test_added_required_condition_needs_new_execution(self):
        self.complete()
        self.plan['checks'].append({'id': 'static', 'kind': 'static', 'required': True, 'role': 'verification',
                                    'condition': 'static analysis', 'command': [sys.executable, '-c', 'pass']})
        self.save_plan()
        self.assertEqual(self.run.inspect()[-1]['status'], 'not_run')
        self.assertTrue(all(r['reasons'] for r in self.run.inspect()))

    def test_paths_and_git_tracking_guard(self):
        for ident in ('../escape', '/absolute', ''):
            with self.assertRaises(harness.Invalid): harness.Run(self.root, ident)
        with self.assertRaises(harness.Invalid): harness.relative(self.root, '../escape')
        self.git('add', '-f', 'ai/local-state/run-1/state.json')
        with self.assertRaises(harness.Invalid): harness.Run(self.root, 'blocked').init(self.plan)

    def test_cli_full_flow_with_synthetic_resources(self):
        script = str(Path(harness.__file__).resolve())
        def cli(*args):
            return subprocess.run([sys.executable, '-B', script, '--root', str(self.root), *args],
                                  capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(cli('gate', 'run-1').returncode, 1)
        self.assertEqual(cli('run', 'run-1', 'test').returncode, 0)
        self.assertEqual(cli('run', 'run-1', 'build').returncode, 0)
        self.put('ai/local-state/cli-evidence.txt', 'synthetic CLI QA / review scope')
        for item in ('qa', 'review'):
            self.assertEqual(cli('begin', 'run-1', item).returncode, 0)
            self.assertEqual(cli('record', 'run-1', item, '--evidence', 'ai/local-state/cli-evidence.txt',
                                 '--scope', 'synthetic flow', '--reporter', 'synthetic independent reviewer',
                                 '--independent', '--summary', 'checked').returncode, 0)
        self.assertEqual(cli('show', 'run-1').returncode, 0)
        self.assertEqual(cli('gate', 'run-1').returncode, 0)
        self.put('product.txt', 'synthetic follow-up change')
        resumed = cli('resume', 'run-1')
        self.assertEqual(resumed.returncode, 1)
        self.assertIn('무효', resumed.stdout)


if __name__ == '__main__':
    unittest.main()
