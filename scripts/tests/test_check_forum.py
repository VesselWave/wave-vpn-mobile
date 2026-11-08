# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('check_forum', Path(__file__).resolve().parents[1] / 'check-forum.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ForumReportTest(unittest.TestCase):
    def test_missing_skipped_or_build_failure_cannot_pass(self):
        self.assertEqual(2, module.outcome([], 1))
        self.assertEqual(2, module.outcome([{'status': 'skipped'}], 0))
        self.assertEqual(2, module.outcome([{'status': 'passed'}], 1))
        self.assertEqual(0, module.outcome([{'status': 'passed'}], 0))
        self.assertEqual(1, module.outcome([{'status': 'failed'}], 1))

    def test_junit_failure_element_is_not_mistaken_for_empty_false_element(self):
        with tempfile.TemporaryDirectory() as folder:
            xml = Path(folder) / 'results.xml'
            xml.write_text('<testsuite><testcase classname="C" name="defect"><failure message="expected"/></testcase>'
                           '<testcase classname="C" name="control"/>'
                           '<testcase classname="C" name="skip"><skipped/></testcase></testsuite>')
            cases = module.collect([xml])
            self.assertEqual(['failed', 'passed', 'skipped'], [c['status'] for c in cases])
            self.assertEqual('expected', cases[0]['detail'])
