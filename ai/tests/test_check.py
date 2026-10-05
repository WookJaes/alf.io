"""정리 과정에서 생길 수 있는 링크·기억·증거 회귀를 검사한다."""
from pathlib import Path
from tempfile import TemporaryDirectory
import hashlib
import unittest

from check import REQUIRED, ROLES, validate


class HarnessChecks(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in list(REQUIRED) + ['ai/roles/' + r + '.md' for r in ROLES]:
            self.write(name, '# 임시 문서\n')

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def test_valid_structure_and_links_with_spaces(self):
        self.write('docs/harness/근거 문서.md', '# 근거\n')
        self.write('ai/memory/test.md', '- 상태: confirmed\n[근거](../../docs/harness/근거%20문서.md#근거)\n')
        self.assertEqual(validate(self.root), [])

    def test_broken_link_after_move(self):
        self.write('AGENTS.md', '[이전 경로](.harness/WORKFLOW.md)\n')
        self.assertTrue(any('링크 대상 누락' in e for e in validate(self.root)))

    def test_missing_role(self):
        (self.root / 'ai/roles/review.md').unlink()
        self.assertTrue(any('필수 문서 누락' in e for e in validate(self.root)))

    def test_confirmed_memory_without_evidence(self):
        self.write('ai/memory/test.md', '- 상태: confirmed\n근거 없음\n')
        self.assertTrue(any('확정 기억의 로컬 근거 누락' in e for e in validate(self.root)))

    def test_memory_cannot_confirm_itself(self):
        self.write('ai/memory/test.md', '- 상태: confirmed\n[자기 참조](test.md)\n')
        self.assertTrue(any('확정 기억의 로컬 근거 누락' in e for e in validate(self.root)))

    def test_candidate_and_stale_do_not_require_proof(self):
        for status in ('candidate', 'stale'):
            self.write('ai/memory/test.md', '- 상태: ' + status + '\n')
            self.assertEqual(validate(self.root), [])

    def test_unknown_memory_status(self):
        self.write('ai/memory/test.md', '- 상태: success\n')
        self.assertTrue(any('기억 상태 누락·오류' in e for e in validate(self.root)))

    def test_modified_evidence(self):
        self.write('docs/harness/evidence/test/log.txt', '수정된 증거\n')
        digest = hashlib.sha256(b'original').hexdigest()
        self.write('docs/harness/evidence/test/checksums.sha256', digest + '  log.txt\n')
        self.assertTrue(any('증거 체크섬 불일치' in e for e in validate(self.root)))

    def test_valid_checksum_then_missing_evidence(self):
        path = self.write('docs/harness/evidence/test/log.txt', '실제 증거\n')
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        self.write('docs/harness/evidence/test/checksums.sha256', digest + '  log.txt\n')
        self.assertEqual(validate(self.root), [])
        path.unlink()
        self.assertTrue(any('증거 파일 누락' in e for e in validate(self.root)))

    def test_examples_and_remote_links_are_not_local(self):
        self.write('AGENTS.md', '[공식](https://developers.openai.com/)\n[앵커](#설명)\n```md\n[예제](없음.md)\n```\n')
        self.assertEqual(validate(self.root), [])

    def test_manifest_cannot_read_outside_evidence_directory(self):
        self.write('docs/harness/evidence/test/checksums.sha256', '0' * 64 + '  ../../README.md\n')
        self.assertTrue(any('체크섬 경로 범위 오류' in e for e in validate(self.root)))


if __name__ == '__main__':
    unittest.main()
