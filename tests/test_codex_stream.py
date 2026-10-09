"""Contract tests for the real helper's Codex JSONL translator; no model simulation in the app."""
import json
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'termux/buddy-run').read_text()
FUNCTION = SOURCE.split('stream_codex() {', 1)[1].split('\n# ------------------------------------------------------------------------ run', 1)[0]
SCRIPT = 'stream_codex() {' + FUNCTION + '\npost() { cat; printf "\\n"; }\nSID=pocket_test\nS=$1\nstream_codex\n'


class CodexStreamTests(unittest.TestCase):
    def translate(self, events):
        with tempfile.TemporaryDirectory(prefix='androidagent-stream-') as state:
            result = subprocess.run(['bash', '-c', SCRIPT, 'stream-test', state],
                                    input='\n'.join(json.dumps(e, ensure_ascii=False) for e in events) + '\n',
                                    text=True, capture_output=True, check=True)
            thread = pathlib.Path(state, 'codex-thread')
            return ([json.loads(line) for line in result.stdout.splitlines() if line],
                    thread.read_text() if thread.exists() else None)

    def test_thread_resume_identifier(self):
        events, thread = self.translate([{'type': 'thread.started', 'thread_id': 'real-contract-id'}])
        self.assertEqual(thread, 'real-contract-id')
        self.assertEqual(events, [])

    def test_unicode_response_is_routed_to_correct_chat(self):
        events, _ = self.translate([{'type': 'item.completed', 'item': {'type': 'agent_message', 'text': 'Zażółć gęślą jaźń'}}])
        self.assertEqual(events[0]['buddy_session'], 'pocket_test')
        self.assertEqual(events[0]['message']['content'][0]['text'], 'Zażółć gęślą jaźń')

    def test_phone_tool_arguments_preserved(self):
        events, _ = self.translate([{'type': 'item.started', 'item': {'type': 'mcp_tool_call', 'server': 'phone', 'tool': 'device_status', 'arguments': {}}}])
        tool = events[0]['message']['content'][0]
        self.assertEqual(tool['name'], 'mcp__phone__device_status')
        self.assertEqual(tool['input'], {})

    def test_command_progress_preserves_exact_command(self):
        events, _ = self.translate([{'type': 'item.started', 'item': {'type': 'command_execution', 'command': 'git status --short'}}])
        self.assertEqual(events[0]['message']['content'][0]['input']['command'], 'git status --short')

    def test_failed_turn_is_not_success(self):
        events, _ = self.translate([{'type': 'turn.failed', 'error': {'message': 'Authentication required'}}])
        self.assertTrue(events[0]['is_error'])
        self.assertIn('Authentication required', events[0]['result'])

    def test_completed_turn(self):
        events, _ = self.translate([{'type': 'turn.completed'}])
        self.assertEqual(events[0]['subtype'], 'success')

    def test_unrecognized_event_is_ignored(self):
        self.assertEqual(self.translate([{'type': 'future.event'}])[0], [])

    def test_protocol_error_visible(self):
        events, _ = self.translate([{'type': 'error', 'message': 'Network disconnected'}])
        self.assertEqual(events[0]['type'], 'buddy_error')
        self.assertIn('Network disconnected', events[0]['text'])

if __name__ == '__main__':
    unittest.main(verbosity=2)
