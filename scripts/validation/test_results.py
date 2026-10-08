"""실행 건수 검사가 0건·전체 스킵·실패·오류를 성공으로 처리하지 않는지 확인한다."""
import pathlib
import subprocess
import sys
import tempfile
import unittest


class ResultGuardTest(unittest.TestCase):
    def check(self, attributes=None):
        with tempfile.TemporaryDirectory() as directory:
            if attributes is not None:
                pathlib.Path(directory, 'TEST-example.xml').write_text(
                    '<testsuite ' + attributes + '/>', encoding='utf-8')
            return subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name('check-results.py')), directory],
                                  capture_output=True, text=True).returncode

    def test_executed_success(self):
        self.assertEqual(0, self.check('tests="1" failures="0" errors="0" skipped="0"'))

    def test_missing_report(self):
        self.assertNotEqual(0, self.check())

    def test_zero_tests(self):
        self.assertNotEqual(0, self.check('tests="0"'))

    def test_all_skipped(self):
        self.assertNotEqual(0, self.check('tests="1" skipped="1"'))

    def test_failure(self):
        self.assertNotEqual(0, self.check('tests="1" failures="1"'))

    def test_error(self):
        self.assertNotEqual(0, self.check('tests="1" errors="1"'))
