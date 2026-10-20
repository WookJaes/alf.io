"""실제 워크플로우의 분류 셸·경로 필터를 대표 변경으로 실행한다.

YAML 전체 파서가 아니라 저장소에서 사용하는 들여쓰기 블록과 리터럴 경로만
읽는다. 지원하지 않는 형식은 실패시키며 GitHub 서버의 이벤트 평가는 대체하지 않는다.
"""
import ast
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / '.github/workflows'


def block(source, key):
    match = re.search(r'^( *)' + re.escape(key) + r':[^\n]*\n', source, re.M)
    if not match:
        raise AssertionError('워크플로우 블록 누락: ' + key)
    tail = source[match.end():]
    end = re.search(r'^ {0,' + str(len(match[1])) + r'}\S', tail, re.M)
    return tail[:end.start()] if end else tail


def workflow(name):
    return (WORKFLOWS / name).read_text(encoding='utf-8')


def paths(name, event):
    lines = block(block(block(workflow(name), 'on'), event), 'paths').splitlines()
    patterns = []
    for line in lines:
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        match = re.fullmatch(r"\s*- '([^']+)'", line)
        if not match:
            raise AssertionError('지원하지 않는 경로 형식: ' + line)
        patterns.append(match[1])
    return patterns


def matches(path, pattern):
    # Ordered GitHub path globs used here: *, **, **/ and literal characters.
    tokens = re.split(r'(\*\*/|\*\*|\*)', pattern)
    expression = ''.join({'**/': '(?:.*/)?', '**': '.*', '*': '[^/]*'}.get(t, re.escape(t)) for t in tokens)
    return re.fullmatch(expression, path) is not None


def triggered(name, event, changed):
    def included(path):
        result = False
        for pattern in paths(name, event):
            negative = pattern.startswith('!')
            if matches(path, pattern.lstrip('!')):
                result = not negative
        return result
    return any(included(path) for path in changed)


def expression(source, context):
    """실제 if/group의 제한된 GitHub 식을 안전한 Python AST로 평가한다."""
    source = source.strip()
    source = re.sub(r"'[^']*'|\b(?:github|needs)\.[a-zA-Z0-9_.]+",
                    lambda m: m[0] if m[0].startswith("'") else repr(context[m[0]]), source)
    source = source.replace('&&', ' and ').replace('||', ' or ')
    tree = ast.parse(source, mode='eval')
    allowed = (ast.Expression, ast.BoolOp, ast.And, ast.Or, ast.Compare, ast.Eq, ast.NotEq, ast.Constant)
    if any(not isinstance(node, allowed) for node in ast.walk(tree)):
        raise AssertionError('지원하지 않는 Actions 식: ' + source)
    return eval(compile(tree, '<workflow expression>', 'eval'), {'__builtins__': {}}, {})


def interpolate(source, context):
    return re.sub(r'\$\{\{(.*?)\}\}', lambda m: str(expression(m[1], context)), source)


class SelectionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.repo = Path(cls.directory.name)
        cls.git('init', '-q')
        cls.git('config', 'user.email', 'synthetic@example.invalid')
        cls.git('config', 'user.name', 'CI regression')
        cls.git('commit', '-qm', 'baseline', '--allow-empty')
        cls.base = cls.git('rev-parse', 'HEAD').strip()

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    @classmethod
    def git(cls, *args):
        return subprocess.check_output(['git', *args], cwd=cls.repo, text=True)

    def select(self, changed, event='pull_request', ref_type='branch'):
        self.git('reset', '--hard', '-q', self.base)
        self.git('clean', '-fdq')
        for name in changed:
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('synthetic\n', encoding='utf-8')
        self.git('add', '--all')
        self.git('commit', '-qm', 'representative change', '--allow-empty')
        head = self.git('rev-parse', 'HEAD').strip()
        source = block(workflow('build-on-push.yml'), 'detect_changes')
        script = textwrap.dedent(block(source, 'run'))
        values = {'github.event_name': event, 'github.event.pull_request.base.sha': self.base,
                  'github.event.before': self.base, 'github.ref_type': ref_type}
        for key, value in values.items():
            script = script.replace('${{ ' + key + ' }}', value)
        self.assertNotIn('${{', script)
        with tempfile.TemporaryDirectory() as output_dir:
            output = Path(output_dir) / 'output'
            subprocess.run(['bash', '-euo', 'pipefail', '-c', script], cwd=self.repo, check=True,
                           env={**os.environ, 'GITHUB_SHA': head, 'GITHUB_OUTPUT': str(output)},
                           capture_output=True, text=True)
            return dict(line.split('=', 1) for line in output.read_text().splitlines())

    def expect(self, changed, frontend=False, backend=False, full=False, **event):
        actual = self.select(changed, **event)
        self.assertEqual(dict(frontend=str(frontend).lower(), backend=str(backend).lower(),
                              full=str(full).lower()), actual, changed)
        context = {'needs.detect_changes.outputs.' + key: value for key, value in actual.items()}
        selected = []
        for name in ('build', 'frontend_check', 'backend_check'):
            condition = re.search(r'if: \$\{\{(.*?)\}\}', block(workflow('build-on-push.yml'), name))[1]
            if expression(condition, context):
                selected.append(name)
        expected = ['build'] if full else ['frontend_check'] if frontend else ['backend_check'] if backend else []
        self.assertEqual(expected, selected, changed)

    def test_document_only(self):
        self.expect(['README.md', 'frontend/public/README.md', 'src/main/guide.rst', 'config/guide.adoc'])

    def test_document_mixed(self):
        for extension in ('md', 'rst', 'adoc'):
            cases = [(['src/main/java/Example.java', 'frontend/public/guide.' + extension], {'backend': True}),
                     (['frontend/admin/src/example.ts', 'src/main/guide.' + extension], {'frontend': True}),
                     (['frontend/public/src/example.ts', 'config/guide.' + extension], {'frontend': True})]
            for changed, expected in cases:
                with self.subTest(extension=extension, changed=changed):
                    self.expect(changed, **expected)

    def test_single_regions(self):
        self.expect(['src/test/java/ExampleTest.java'], backend=True)
        self.expect(['frontend/public/src/example.ts'], frontend=True)
        self.expect(['frontend/admin/src/example.ts'], frontend=True)

    def test_both_regions(self):
        self.expect(['src/main/java/Example.java', 'frontend/admin/src/example.ts'], frontend=True, backend=True, full=True)

    def test_common_inputs(self):
        for name in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle/libs.versions.toml',
                     'buildSrc/src/main/kotlin/Plugin.kt', 'build-logic/build.gradle.kts', 'config/HEADER',
                     'lib_exclude', 'lombok.config', 'gradlew', 'gradlew.bat', 'src/main/dist/Dockerfile',
                     '.github/workflows/build-on-push.yml', '.codecov.yml'):
            with self.subTest(path=name):
                self.expect([name], full=True)

    def test_main_and_tag_keep_full_build(self):
        self.expect(['src/main/java/Example.java'], backend=True, full=True, event='push')
        self.expect(['README.md'], full=True, event='push', ref_type='tag')


class ValidationPathsTest(unittest.TestCase):
    def test_actual_build_inputs_trigger_both_events(self):
        for name in ('e2e-test.yml', 'migration-test.yml'):
            for event in ('pull_request', 'push'):
                for path in ('lombok.config', 'lib_exclude', 'config/HEADER', 'buildSrc/build.gradle.kts',
                             'build-logic/build.gradle.kts', 'gradle/libs.versions.toml', 'gradlew',
                             'frontend/public/package.json', 'frontend/admin/pnpm-lock.yaml',
                             'src/main/resources/alfio/template.ms', 'src/test/java/ExampleTest.java'):
                    with self.subTest(workflow=name, event=event, path=path):
                        self.assertTrue(triggered(name, event, [path]))

    def test_documents_and_unrelated_inputs_do_not_trigger(self):
        for name in ('build-on-push.yml', 'e2e-test.yml', 'migration-test.yml'):
            for event in ('pull_request', 'push'):
                with self.subTest(workflow=name, event=event):
                    self.assertFalse(triggered(name, event, ['README.md', 'src/main/guide.rst',
                                                            'frontend/admin/guide.adoc', 'docs/testing/guide.md']))
        self.assertFalse(triggered('e2e-test.yml', 'pull_request', ['scripts/validation/migration.sh']))
        self.assertFalse(triggered('migration-test.yml', 'push', ['scripts/validation/e2e.sh']))


if __name__ == '__main__':
    unittest.main()
