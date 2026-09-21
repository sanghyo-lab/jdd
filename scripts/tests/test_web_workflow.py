"""The shared check must actually run the locked web checks on this platform."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from jdd import Repository


class WebWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        (self.root / 'web').mkdir()
        (self.root / 'web/package.json').write_text('{}')
        self.repo = Repository(self.root)

    def test_check_uses_native_npm_and_runs_tests_before_build(self):
        env = {'APP_RUNTIME': 'test', 'LLM_PROVIDER': 'mock'}
        with patch.object(self.repo, 'run'), patch('jdd.subprocess.run') as run:
            self.repo.check(env)
        npm = 'npm.cmd' if os.name == 'nt' else 'npm'
        self.assertEqual([call.args[0] for call in run.call_args_list],
                         [[npm, 'ci'], [npm, 'test'], [npm, 'run', 'build']])
        for call in run.call_args_list:
            self.assertEqual(call.kwargs, {'cwd': self.root / 'web', 'check': True, 'env': env})

    def test_failed_web_test_stops_before_build(self):
        with patch.object(self.repo, 'run'), patch('jdd.subprocess.run') as run:
            run.side_effect = [subprocess.CompletedProcess([], 0), subprocess.CalledProcessError(1, 'npm test')]
            with self.assertRaises(subprocess.CalledProcessError):
                self.repo.check()
        self.assertEqual(run.call_count, 2)


if __name__ == '__main__':
    unittest.main()
