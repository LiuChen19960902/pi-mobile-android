"""CI 结果门槛的合成回归；只在临时目录生成报告。"""
from pathlib import Path
import importlib.util
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("ci_results", Path(__file__).with_name("check-ci-test-results.py"))
results = importlib.util.module_from_spec(spec)
spec.loader.exec_module(results)

class CiResultsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.report("shared", "SharedTest")
        self.report("androidApp", results.CONFORMANCE)

    def tearDown(self):
        self.temp.cleanup()

    def report(self, module, name, tests=1, skipped=0, failures=0):
        path = self.root / module / "build/test-results/jvm" / f"TEST-{name}.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'<testsuite name="{name}" tests="{tests}" skipped="{skipped}" failures="{failures}" errors="0"/>')
        return path

    def test_both_modules_and_conformance_run(self):
        self.assertEqual(results.validate(self.root), [])

    def test_missing_reports_fail(self):
        self.assertTrue(results.validate(self.root / "missing"))

    def test_skipped_conformance_fails(self):
        self.report("androidApp", results.CONFORMANCE, skipped=1)
        self.assertTrue(results.validate(self.root))

    def test_missing_conformance_fails_even_when_other_android_tests_pass(self):
        (self.root / "androidApp/build/test-results/jvm" / f"TEST-{results.CONFORMANCE}.xml").unlink()
        self.report("androidApp", "OtherTest")
        self.assertTrue(results.validate(self.root))

    def test_zero_tests_and_failure_fail(self):
        self.report("shared", "SharedTest", tests=0)
        self.report("androidApp", results.CONFORMANCE, failures=1)
        self.assertTrue(results.validate(self.root))

    def test_invalid_xml_fails(self):
        self.report("shared", "SharedTest").write_text("<broken")
        self.assertTrue(results.validate(self.root))

if __name__ == "__main__":
    unittest.main()
