"""실제 임시 Git 저장소·합성 명령으로 로컬 실행 게이트를 검증한다."""
from copy import deepcopy
import importlib.util
import json
import os
from pathlib import Path, PureWindowsPath
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

    def test_zero_exit_runner_failed_summary_blocks_gate(self):
        for item in self.plan['checks']:
            item['required'] = item['id'] == 'test'
        cases = (
            (' def test_failure(self): self.fail()', 1, 0, 0),
            (' def test_error(self): raise RuntimeError()', 0, 1, 0),
            (' @unittest.expectedFailure\n def test_unexpected(self): pass', 0, 0, 1),
        )
        for body, failures, errors, unexpected in cases:
            with self.subTest(body=body):
                code = ('import unittest\nclass T(unittest.TestCase):\n' + body
                        + '\nunittest.TextTestRunner().run(unittest.defaultTestLoader.loadTestsFromTestCase(T))')
                self.plan['checks'][0]['command'] = [sys.executable, '-c', code]
                self.save_plan()
                self.assertEqual(harness.main(['--root', str(self.root), 'run', 'run-1', 'test']), 1)
                result = self.run.load()[1]['results']['test']
                self.assertEqual(result['exit_code'], 0)
                self.assertEqual(result['status'], 'failed')
                self.assertEqual(result['test_outcome'], 'FAILED')
                self.assertEqual(result['unexpected_successes'], unexpected)
                self.assertEqual(result['counts'], {'executed': 1, 'failures': failures, 'errors': errors, 'skipped': 0})
                self.assertEqual(harness.main(['--root', str(self.root), 'gate', 'run-1']), 1)

    def test_zero_exit_runner_success_summary_passes_gate(self):
        for item in self.plan['checks']:
            item['required'] = item['id'] == 'test'
        code = ('import unittest\nclass T(unittest.TestCase):\n def test_success(self): pass'
                '\nunittest.TextTestRunner().run(unittest.defaultTestLoader.loadTestsFromTestCase(T))')
        self.plan['checks'][0]['command'] = [sys.executable, '-c', code]
        self.save_plan()
        self.assertTrue(self.run.execute('test'))
        result = self.run.load()[1]['results']['test']
        self.assertEqual(result['test_outcome'], 'OK')
        self.assertEqual(result['unexpected_successes'], 0)
        self.assertEqual(harness.main(['--root', str(self.root), 'gate', 'run-1']), 0)

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

    def test_unittest_other_gradle_tasks_do_not_reject_real_tests(self):
        for marker in ('> Task :compileJava UP-TO-DATE', '> Task :compileJava FROM-CACHE',
                       '> Task :processTestResources NO-SOURCE'):
            with self.subTest(marker=marker):
                self.plan['checks'][0]['command'] = [sys.executable, '-c',
                    'import unittest\nprint(' + repr(marker) + ', flush=True)\nclass T(unittest.TestCase):\n def test_real(self): pass\nunittest.main()']
                self.save_plan()
                self.assertTrue(self.run.execute('test'))
                self.assertEqual(self.run.inspect()[0]['reasons'], [])

    def test_gradle_single_junit_target_states_and_exit_code(self):
        check = self.plan['checks'][0]
        states = ('', 'UP-TO-DATE', 'FROM-CACHE', 'NO-SOURCE', 'SKIPPED', 'FAILED', 'unknown', 'EXECUTED', None)
        for index, target in enumerate(states):
            with self.subTest(target=target):
                report = f'ai/local-state/gradle-{index}.xml'
                lines = '> Task :compileJava UP-TO-DATE\n> Task :processTestResources NO-SOURCE\n> Task :other FROM-CACHE\n'
                if target is not None:
                    lines += '> Task :module:test' + (' ' + target if target else '')
                check.update(adapter='junit', report=report, gradle_tasks=[':module:test'],
                             command=[sys.executable, '-c',
                                      f'from pathlib import Path; print({lines!r}); Path({report!r}).write_text("<testsuite><testcase/></testsuite>")'])
                self.save_plan()
                self.assertEqual(self.run.execute('test'), target == '')
                result = self.run.load()[1]['results']['test']
                self.assertEqual(result['exit_code'], 0)
                self.assertEqual(result['counts']['executed'], 1)
                if target != '': self.assertIn('대상', result['summary'])
        check['report'] = 'ai/local-state/gradle-exit.xml'
        check['command'] = [sys.executable, '-c',
            'from pathlib import Path; print("> Task :module:test"); Path("ai/local-state/gradle-exit.xml").write_text("<testsuite><testcase/></testsuite>"); raise SystemExit(7)']
        self.save_plan()
        self.assertFalse(self.run.execute('test'))
        self.assertEqual(self.run.load()[1]['results']['test']['exit_code'], 7)

    def test_gradle_missing_declaration_and_task_evidence(self):
        check = self.plan['checks'][0]
        check.update(adapter='junit', report='ai/local-state/unknown.xml', command=['./gradlew', 'test'])
        with self.assertRaises(harness.Invalid): harness.validate_plan(self.plan)
        check['command'] = [sys.executable, '-c',
            'from pathlib import Path; print("> Task :test"); Path("ai/local-state/unknown.xml").write_text("<testsuite><testcase/></testsuite>")']
        self.save_plan()
        self.assertFalse(self.run.execute('test'))
        self.assertIn('gradle_tasks', self.run.load()[1]['results']['test']['summary'])
        check['gradle_tasks'] = [':test']
        check['report'] = 'ai/local-state/evidence.xml'
        check['command'][2] = check['command'][2].replace('unknown.xml', 'evidence.xml')
        self.save_plan()
        self.assertTrue(self.run.execute('test'))
        _, state = self.run.load()
        result = state['results']['test']
        result.pop('gradle_tasks')
        self.run.attest(state, check, {k: v for k, v in result.items() if k not in {'receipt', 'receipt_hash'}})
        self.assertTrue(self.run.inspect()[0]['reasons'])

    def test_gradle_stream_split_tail_limit_and_all_targets(self):
        item = dict(self.plan['checks'][0], gradle_tasks=[':test', ':module:test'])
        output = harness.ExecutionOutput(item)
        for chunk in (b'> Task :compileJava UP-', b'TO-DATE\n> Task :te', b'st\n',
                      b'x' * 70000 + b'\n', b'> Task :module:test'):
            output.feed(chunk)
        self.assertIsNone(output.finish())
        self.assertEqual(output.tasks, {':test': 'EXECUTED', ':module:test': 'EXECUTED'})
        for lines in (b'> Task :test\n', b'> Task :test\n> Task :module:test SKIPPED\n',
                      b'> Task :test\n> Task :test UP-TO-DATE\n> Task :module:test\n'):
            output = harness.ExecutionOutput(item); output.feed(lines)
            self.assertIsNotNone(output.finish())

    def test_gradle_task_declaration_validation(self):
        for tasks in ([], ':test', [':test', ':test'], ['test'], [None]):
            self.plan['checks'][0]['gradle_tasks'] = tasks
            with self.assertRaises(harness.Invalid): harness.validate_plan(self.plan)

    def multiple_junit(self, files, selectors=None):
        for item in self.plan['checks']:
            item['required'] = item['id'] == 'test'
        check = self.plan['checks'][0]
        check.pop('report', None)
        check.pop('gradle_tasks', None)
        code = 'from pathlib import Path\n'
        for name, xml in files.items():
            code += f'p=Path({name!r}); p.parent.mkdir(parents=True, exist_ok=True); p.write_text({xml!r})\n'
        check.update(adapter='junit', reports=selectors or ['ai/local-state/xml/TEST-*.xml'],
                     command=[sys.executable, '-c', code])
        self.save_plan()
        return check

    def test_multiple_junit_aggregate_and_deduplicate_paths(self):
        self.multiple_junit({
            'ai/local-state/xml/TEST-a.xml': '<testsuite tests="999"><testcase/><testcase><skipped/></testcase></testsuite>',
            'ai/local-state/xml/TEST-b.xml': '<testsuites><testsuite><testcase/><testcase/></testsuite></testsuites>'},
            ['ai/local-state/xml/TEST-*.xml', 'ai/local-state/xml/TEST-a.xml', 'ai/local-state/xml'])
        self.assertTrue(self.run.execute('test'))
        result = self.run.load()[1]['results']['test']
        self.assertEqual(result['counts'], {'executed': 3, 'failures': 0, 'errors': 0, 'skipped': 1})
        self.assertEqual(len(result['reports']), 2)
        for entry in result['reports']:
            self.assertEqual(entry['hash'], harness.file_hash(self.root / entry['path']))
        self.assertEqual(harness.main(['--root', str(self.root), 'gate', 'run-1']), 0)
        self.assertEqual(harness.main(['--root', str(self.root), 'resume', 'run-1']), 0)

    def test_multiple_junit_missing_zero_skipped_failures_errors_and_format(self):
        bad_cases = (None, '<testsuite/>', '<testsuite><testcase><skipped/></testcase></testsuite>',
                     '<testsuite><testcase><failure/></testcase></testsuite>',
                     '<testsuite><testcase><error/></testcase></testsuite>', 'broken',
                     '<other><testcase/></other>')
        for index, xml in enumerate(bad_cases):
            with self.subTest(xml=xml):
                directory = f'ai/local-state/bad-{index}'
                files = {} if xml is None else {directory + '/a.xml': xml}
                if index >= 3:
                    files[directory + '/b.xml'] = '<testsuite><testcase/></testsuite>'
                self.multiple_junit(files, [directory])
                self.assertFalse(self.run.execute('test'))
                self.assertEqual(self.run.load()[1]['results']['test']['exit_code'], 0)
                self.assertEqual(harness.main(['--root', str(self.root), 'gate', 'run-1']), 1)

    def test_multiple_junit_reads_every_report_and_missing_selector(self):
        files = {'ai/local-state/xml/TEST-a.xml': '<testsuite><testcase/></testsuite>',
                 'ai/local-state/xml/TEST-b.xml': '<testsuite><testcase><failure/></testcase></testsuite>'}
        check = self.multiple_junit(files)
        self.assertFalse(self.run.execute('test'))
        result = self.run.load()[1]['results']['test']
        self.assertEqual(result['counts']['executed'], 2)
        self.assertEqual(result['counts']['failures'], 1)
        self.assertEqual(len(result['reports']), 2)
        check['reports'] = ['ai/local-state/xml/TEST-a.xml']
        with self.assertRaises(harness.Invalid): harness.reports_junit(self.root, check)
        check['reports'] = ['ai/local-state/xml/TEST-*.xml', 'ai/local-state/missing.xml']
        with self.assertRaises(harness.Invalid): harness.reports_junit(self.root, check)

    def test_multiple_junit_old_files_block_before_command_and_rerun(self):
        self.put('ai/local-state/xml/old.xml', '<testsuite><testcase/></testsuite>')
        self.multiple_junit({'ai/local-state/xml/TEST-new.xml': '<testsuite><testcase/></testsuite>'})
        with self.assertRaises(harness.Invalid): self.run.execute('test')
        self.assertFalse((self.root / 'ai/local-state/xml/TEST-new.xml').exists())
        (self.root / 'ai/local-state/xml/old.xml').unlink()
        self.assertTrue(self.run.execute('test'))
        with self.assertRaises(harness.Invalid): self.run.execute('test')

    def test_multiple_junit_report_set_changes_invalidate_gate_resume_and_reuse(self):
        name = 'ai/local-state/xml/TEST-a.xml'
        xml = '<testsuite><testcase/></testsuite>'
        self.multiple_junit({name: xml})
        self.assertTrue(self.run.execute('test'))
        other = harness.Run(self.root, 'multi-reuse'); other.init(self.plan)
        other.reuse('run-1', 'test', 'same report set')
        self.assertEqual(other.inspect()[0]['reasons'], [])
        for mutation in ('delete', 'content', 'add', 'unselected-add'):
            with self.subTest(mutation=mutation):
                if mutation == 'delete': (self.root / name).unlink()
                elif mutation == 'content': self.put(name, xml + '\n')
                elif mutation == 'add': self.put('ai/local-state/xml/TEST-added.xml', xml)
                else: self.put('ai/local-state/xml/unselected.xml', xml)
                self.assertEqual(self.run.inspect()[0]['status'], 'invalid')
                self.assertEqual(other.inspect()[0]['status'], 'invalid')
                self.assertEqual(harness.main(['--root', str(self.root), 'gate', 'run-1']), 1)
                self.assertEqual(harness.main(['--root', str(self.root), 'resume', 'run-1']), 1)
                with self.assertRaises(harness.Invalid): other.reuse('run-1', 'test', 'changed reports')
                self.put(name, xml)
                for added in ('TEST-added.xml', 'unselected.xml'):
                    (self.root / 'ai/local-state/xml' / added).unlink(missing_ok=True)
                self.assertEqual(self.run.inspect()[0]['reasons'], [])

    def test_invalid_junit_patterns_reject_before_command_and_state_transition(self):
        for pattern in ('**.xml', 'foo**/*.xml', '***/*.xml'):
            with self.subTest(pattern=pattern):
                check = self.multiple_junit(
                    {'ai/local-state/xml/TEST-a.xml': '<testsuite><testcase/></testsuite>'},
                    ['ai/local-state/xml/' + pattern])
                with self.assertRaises(harness.Invalid): harness.validate_plan(self.plan)
                other = harness.Run(self.root, 'invalid-pattern')
                with self.assertRaises(harness.Invalid): other.init(self.plan)
                self.assertFalse(other.directory.exists())
                self.assertEqual(harness.main(['--root', str(self.root), 'run', 'run-1', 'test']), 2)
                self.assertFalse((self.root / 'ai/local-state/xml/TEST-a.xml').exists())
                self.assertEqual(harness.read(self.run.directory / 'state.json')['results']['test']['status'], 'not_run')
                with self.assertRaises(harness.Invalid): harness.report_scope(self.root, check['reports'][0])

    def test_recursive_junit_pattern_reads_nested_reports(self):
        self.multiple_junit({
            'ai/local-state/xml/TEST-a.xml': '<testsuite><testcase/></testsuite>',
            'ai/local-state/xml/nested/TEST-b.xml': '<testsuite><testcase/><testcase/></testsuite>'},
            ['ai/local-state/xml/**/*.xml'])
        self.assertTrue(self.run.execute('test'))
        result = self.run.load()[1]['results']['test']
        self.assertEqual(result['counts']['executed'], 3)
        self.assertEqual(len(result['reports']), 2)
        self.assertEqual(self.run.inspect()[0]['reasons'], [])

    def test_junit_glob_value_error_becomes_harness_invalid(self):
        with patch.object(Path, 'glob', side_effect=ValueError('invalid pattern')):
            with self.assertRaisesRegex(harness.Invalid, '패턴 문법 오류'):
                harness.report_glob(self.root, 'ai/local-state/xml/*.xml')

    def test_multiple_junit_declaration_scope_and_symlink_guards(self):
        check = self.multiple_junit({})
        for selectors in ([], 'xml', [None], ['']):
            check['reports'] = selectors
            with self.assertRaises(harness.Invalid): harness.validate_plan(self.plan)
        check['reports'] = ['ai/local-state/xml']
        check['report'] = 'ai/local-state/a.xml'
        with self.assertRaises(harness.Invalid): harness.validate_plan(self.plan)
        check.pop('report')
        for selectors in (['/tmp/xml'], ['../xml'], ['build/test-results/*.xml'], ['ai/local-state']):
            check['reports'] = selectors; self.save_plan()
            with self.assertRaises(harness.Invalid): self.run.execute('test')
        check['reports'] = ['ai/local-state/xml']
        self.put('ai/local-state/elsewhere/a.xml', '<testsuite><testcase/></testsuite>')
        (self.root / 'ai/local-state/xml').symlink_to(self.root / 'ai/local-state/elsewhere', target_is_directory=True)
        self.save_plan()
        with self.assertRaises(harness.Invalid): self.run.execute('test')
        (self.root / 'ai/local-state/xml').unlink()
        (self.root / 'ai/local-state/xml').mkdir()
        (self.root / 'ai/local-state/xml/alias.xml').symlink_to(self.root / 'ai/local-state/elsewhere/a.xml')
        with self.assertRaises(harness.Invalid): self.run.execute('test')

    def test_junit_windows_rooted_and_drive_relative_scopes_are_invalid(self):
        root = PureWindowsPath('C:/repo')
        # 실제 Windows 파일시스템 대신 표준 라이브러리의 경로 해석을 검증한다.
        with patch.object(harness, 'Path', PureWindowsPath):
            for name in ('/tmp/xml', r'\tmp\xml', r'C:tmp\xml',
                         r'C:\tmp\xml', r'D:\tmp\xml', r'\\server\share\xml'):
                with self.subTest(name=name), self.assertRaises(harness.Invalid):
                    harness.report_scope(root, name)

    def test_junit_outside_report_paths_raise_harness_invalid(self):
        paths = ((self.root, self.root.parent / 'outside.xml'),
                 (PureWindowsPath('C:/repo'), PureWindowsPath('C:/tmp/xml')),
                 (PureWindowsPath('C:/repo'), PureWindowsPath('D:/tmp/xml')))
        for root, path in paths:
            with self.subTest(root=root, path=path), self.assertRaises(harness.Invalid):
                harness.report_path(root, path)

    def test_multiple_junit_separate_scopes_preserve_previous_execution(self):
        self.put('ai/local-state/previous/TEST-old.xml', '<testsuite><testcase><failure/></testcase></testsuite>')
        self.multiple_junit({
            'ai/local-state/new-a/TEST-a.xml': '<testsuite><testcase/></testsuite>',
            'ai/local-state/new-b/TEST-b.xml': '<testsuite><testcase/><testcase/></testsuite>'},
            ['ai/local-state/new-a/TEST-a.xml', 'ai/local-state/new-b/TEST-b.xml'])
        self.assertTrue(self.run.execute('test'))
        self.assertEqual(self.run.load()[1]['results']['test']['counts']['executed'], 3)
        self.assertTrue((self.root / 'ai/local-state/previous/TEST-old.xml').is_file())
        self.assertEqual(self.run.inspect()[0]['reasons'], [])

    def test_multiple_junit_cli_existing_arguments_unchanged(self):
        self.multiple_junit({'ai/local-state/xml/TEST-a.xml': '<testsuite><testcase/></testsuite>',
                             'ai/local-state/xml/TEST-b.xml': '<testsuite><testcase/></testsuite>'})
        self.assertEqual(harness.main(['--root', str(self.root), 'run', 'run-1', 'test']), 0)
        self.assertEqual(harness.main(['--root', str(self.root), 'gate', 'run-1']), 0)

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
        self.assert_cli_flow()

    def test_cli_full_flow_with_inherited_cp949_environment(self):
        with patch.dict(os.environ, {'PYTHONUTF8': '0', 'PYTHONIOENCODING': 'cp949'}):
            self.assert_cli_flow()

    def test_cli_full_flow_with_cp949_output(self):
        self.assert_cli_flow(output_encoding='cp949')

    def assert_cli_flow(self, output_encoding='utf-8'):
        script = str(Path(harness.__file__).resolve())
        # 부모 기본 인코딩·CI 설정에 기대지 않고 자식 출력과 읽기를 맞춘다.
        child_env = dict(os.environ, PYTHONUTF8='0', PYTHONIOENCODING=output_encoding)
        def cli(*args):
            return subprocess.run([sys.executable, '-B', script, '--root', str(self.root), *args],
                                  capture_output=True, text=True, encoding=output_encoding, env=child_env)
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
