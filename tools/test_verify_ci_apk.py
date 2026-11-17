# Python · test_verify_ci_apk.py · unittest / Python 3 · CI 元数据断言回归
import tempfile
import unittest
from pathlib import Path
from verify_ci_apk import parse_badging, project_version


class VersionTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='freedomplus-ci-test-')
        self.addCleanup(self.directory.cleanup)
        self.config = Path(self.directory.name) / 'build.gradle'
        self.config.write_text('applicationId "io.github.fplus"\nversionCode 148\nversionName "1.3.12"\n', encoding='utf-8')

    def test_base_version(self):
        self.assertEqual(project_version(self.config, ''), {'package_name': 'io.github.fplus',
                         'version_name': '1.3.12', 'version_code': '148'})

    def test_twelve_digit_suffix(self):
        self.assertEqual(project_version(self.config, 'abcdef012345')['version_name'], '1.3.12.abcdef012345')

    def test_legacy_suffix(self):
        self.assertEqual(project_version(self.config, '97d7')['version_name'], '1.3.12.97d7')

    def test_bad_suffixes(self):
        for suffix in ['ABCDEF01', '../x', '123', 'abcdef0123456', ' x ']:
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                project_version(self.config, suffix)

    def test_ambiguous_version_is_rejected(self):
        self.config.write_text(self.config.read_text() + 'versionName "1.4.0"\n', encoding='utf-8')
        with self.assertRaises(ValueError):
            project_version(self.config, '')


class BadgingTests(unittest.TestCase):
    def test_compile_codename_does_not_replace_package_name(self):
        line = "package: name='io.github.fplus' versionCode='148' versionName='1.3.12' compileSdkVersion='34' compileSdkVersionCodename='14'"
        self.assertEqual(parse_badging(line)['name'], 'io.github.fplus')

    def test_missing_version_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_badging("package: name='io.github.fplus' versionCode='148'")

    def test_duplicate_package_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_badging("package: name='x' name='y' versionCode='148' versionName='1.3.12'")


if __name__ == '__main__':
    unittest.main()
