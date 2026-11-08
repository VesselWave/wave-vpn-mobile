# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('forum_ui', Path(__file__).resolve().parents[1] / 'forum-ui-emulator.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

C, K = module.CONTROLS, module.CONTRACTS


def raw(*tests):
    out = []
    for cls, name, code, stack in tests:
        out += [f'INSTRUMENTATION_STATUS: class={cls}', f'INSTRUMENTATION_STATUS: test={name}',
                'INSTRUMENTATION_STATUS_CODE: 1', f'INSTRUMENTATION_STATUS: class={cls}',
                f'INSTRUMENTATION_STATUS: test={name}']
        if stack:
            out += [f'INSTRUMENTATION_STATUS: stack={stack[0]}', *stack[1:]]
        out.append(f'INSTRUMENTATION_STATUS_CODE: {code}')
    out += ['INSTRUMENTATION_RESULT: stream=', 'INSTRUMENTATION_CODE: -1']
    return '\n'.join(out)


class ForumUiEmulatorTest(unittest.TestCase):
    def test_parses_multiline_failure_stack(self):
        results, summary = module.parse_instrumentation(raw(
            (C, 'a', '0', None), (K, 'b', '-2', ['java.lang.AssertionError: Forum 3', '- Disconnected', '\tat x'])))
        self.assertEqual(['passed', 'failed'], [r['outcome'] for r in results])
        self.assertIn('- Disconnected', results[1]['message'])
        self.assertEqual('-1', summary['code'])

    def test_contract_failure_is_1_but_control_failure_is_2(self):
        self.assertEqual(1, module.verdict([{'class': C, 'outcome': 'passed'}, {'class': K, 'outcome': 'failed'}], {}, True))
        self.assertEqual(2, module.verdict([{'class': C, 'outcome': 'failed'}, {'class': K, 'outcome': 'failed'}], {}, True))
        self.assertEqual(0, module.verdict([{'class': C, 'outcome': 'passed'}], {}))

    def test_partial_duplicate_or_unterminated_output_cannot_pass(self):
        expected = {(C, 'a'), (C, 'b')}
        a = {'class': C, 'test': 'a', 'outcome': 'passed'}
        b = {'class': C, 'test': 'b', 'outcome': 'passed'}
        self.assertEqual(2, module.verdict([a], {'code': '-1'}, expected=expected))
        self.assertEqual(2, module.verdict([a, a, b], {'code': '-1'}, expected=expected))
        self.assertEqual(2, module.verdict([a, b], {}, expected=expected))
        self.assertEqual(0, module.verdict([a, b], {'code': '-1'}, expected=expected))

    def test_skipped_missing_or_crashed_runs_never_pass(self):
        self.assertEqual(2, module.verdict([], {}))
        self.assertEqual(2, module.verdict([{'class': K, 'outcome': 'skipped'}], {}, True))
        self.assertEqual(2, module.verdict([{'class': C, 'outcome': 'passed'}], {}, True))
        self.assertEqual(2, module.verdict([{'class': C, 'outcome': 'passed'}], {'shortMsg': 'Process crashed.'}))


if __name__ == '__main__':
    unittest.main()
