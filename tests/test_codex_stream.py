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
    def translate(self, events, newline=True):
        with tempfile.TemporaryDirectory(prefix='androidagent-stream-') as state:
            result = subprocess.run(['bash', '-c', SCRIPT, 'stream-test', state],
                                    input='\n'.join(json.dumps(e, ensure_ascii=False) for e in events) + ('\n' if newline else ''),
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

    def test_final_event_without_trailing_newline_is_not_lost(self):
        events, _ = self.translate([{'type': 'item.completed', 'item': {'type': 'agent_message', 'text': 'Last output'}}], newline=False)
        self.assertEqual(events[0]['message']['content'][0]['text'], 'Last output')

    # ------------------------------------------------------------- tool ids and results

    def result_of(self, event):
        """The single tool_result block of a translated user event."""
        self.assertEqual(event['type'], 'user')
        self.assertEqual(event['buddy_session'], 'pocket_test')
        blocks = event['message']['content']
        self.assertEqual(len(blocks), 1)
        self.assertEqual(blocks[0]['type'], 'tool_result')
        return blocks[0]

    def test_tool_calls_carry_codex_item_ids(self):
        events, _ = self.translate([
            {'type': 'item.started', 'item': {'id': 'item_1', 'type': 'command_execution', 'command': 'ls',
                                              'aggregated_output': '', 'exit_code': None, 'status': 'in_progress'}},
            {'type': 'item.started', 'item': {'id': 'item_2', 'type': 'mcp_tool_call', 'server': 'phone',
                                              'tool': 'device_status', 'arguments': {}, 'status': 'in_progress'}},
        ])
        self.assertEqual([e['message']['content'][0]['id'] for e in events], ['item_1', 'item_2'])

    def test_successful_command_output_becomes_tool_result(self):
        events, _ = self.translate([{'type': 'item.completed', 'item': {
            'id': 'item_1', 'type': 'command_execution', 'command': 'ls',
            'aggregated_output': 'zażółć.txt\nb.txt\n', 'exit_code': 0, 'status': 'completed'}}])
        self.assertEqual(len(events), 1)
        result = self.result_of(events[0])
        self.assertEqual(result['tool_use_id'], 'item_1')
        self.assertEqual(result['content'], 'zażółć.txt\nb.txt\n')
        self.assertFalse(result['is_error'])

    def test_failed_command_reports_exit_code(self):
        events, _ = self.translate([
            {'type': 'item.completed', 'item': {'id': 'item_2', 'type': 'command_execution', 'command': 'make',
                                                'aggregated_output': 'boom', 'exit_code': 2, 'status': 'failed'}},
            {'type': 'item.completed', 'item': {'id': 'item_3', 'type': 'command_execution', 'command': 'true',
                                                'aggregated_output': '', 'exit_code': 1, 'status': 'failed'}},
        ])
        first, second = (self.result_of(e) for e in events)
        self.assertTrue(first['is_error'])
        self.assertEqual(first['content'], 'boom\nexit 2')
        self.assertTrue(second['is_error'])
        self.assertEqual(second['content'], 'exit 1')

    def test_command_without_exit_code_is_not_an_error(self):
        events, _ = self.translate([{'type': 'item.completed', 'item': {
            'id': 'item_4', 'type': 'command_execution', 'command': 'sleep 1',
            'aggregated_output': 'partial', 'exit_code': None, 'status': 'completed'}}])
        result = self.result_of(events[0])
        self.assertFalse(result['is_error'])
        self.assertNotIn('exit', result['content'])

    def test_long_command_output_is_clipped(self):
        events, _ = self.translate([{'type': 'item.completed', 'item': {
            'id': 'item_5', 'type': 'command_execution', 'command': 'yes',
            'aggregated_output': 'ż' * 10000, 'exit_code': 0, 'status': 'completed'}}])
        self.assertEqual(self.result_of(events[0])['content'], 'ż' * 4000)

    def test_mcp_tool_result_text_and_images(self):
        events, _ = self.translate([{'type': 'item.completed', 'item': {
            'id': 'item_6', 'type': 'mcp_tool_call', 'server': 'phone', 'tool': 'screenshot', 'arguments': {},
            'result': {'content': [{'type': 'text', 'text': 'Ekran 1080x2220'},
                                   {'type': 'image', 'data': 'AAAA', 'mimeType': 'image/png'}]},
            'error': None, 'status': 'completed'}}])
        result = self.result_of(events[0])
        self.assertEqual(result['tool_use_id'], 'item_6')
        self.assertEqual(result['content'], 'Ekran 1080x2220\n[obraz]')
        self.assertFalse(result['is_error'])

    def test_mcp_tool_failure_is_an_error_result(self):
        events, _ = self.translate([
            {'type': 'item.completed', 'item': {'id': 'item_7', 'type': 'mcp_tool_call', 'server': 'phone', 'tool': 'tap',
                                                'arguments': {'x': 1}, 'result': None,
                                                'error': {'message': 'Accessibility is off'}, 'status': 'failed'}},
            {'type': 'item.completed', 'item': {'id': 'item_8', 'type': 'mcp_tool_call', 'server': 'phone', 'tool': 'tap',
                                                'arguments': {}, 'result': {'content': []}, 'status': 'failed'}},
        ])
        first, second = (self.result_of(e) for e in events)
        self.assertTrue(first['is_error'])
        self.assertEqual(first['content'], 'Accessibility is off')
        self.assertTrue(second['is_error'])

    def test_file_change_is_an_edit_with_its_result(self):
        events, _ = self.translate([{'type': 'item.completed', 'item': {
            'id': 'item_9', 'type': 'file_change', 'status': 'completed',
            'changes': [{'path': '/home/work/app/main.py', 'kind': 'update'}, {'path': '/home/work/app/new.py', 'kind': 'add'}]}}])
        self.assertEqual(len(events), 2)
        tool = events[0]['message']['content'][0]
        self.assertEqual(events[0]['type'], 'assistant')
        self.assertEqual((tool['type'], tool['name'], tool['id']), ('tool_use', 'Edit', 'item_9'))
        self.assertEqual(tool['input'], {'file_path': '/home/work/app/main.py'})
        result = self.result_of(events[1])
        self.assertEqual(result['tool_use_id'], 'item_9')
        self.assertEqual(result['content'], '/home/work/app/main.py (update)\n/home/work/app/new.py (add)')
        self.assertFalse(result['is_error'])

    def test_failed_file_change_without_changes(self):
        events, _ = self.translate([{'type': 'item.completed', 'item': {
            'id': 'item_10', 'type': 'file_change', 'status': 'failed', 'changes': []}}])
        self.assertEqual(events[0]['message']['content'][0]['input'], {})
        self.assertTrue(self.result_of(events[1])['is_error'])

    def test_web_search_is_a_finished_tool_call(self):
        events, _ = self.translate([{'type': 'item.completed', 'item': {'id': 'item_11', 'type': 'web_search', 'query': 'pogoda Kraków'}}])
        self.assertEqual(events[0]['message']['content'][0]['input'], {'query': 'pogoda Kraków'})
        self.assertFalse(self.result_of(events[1])['is_error'])

    def test_every_event_is_correlated_with_the_run(self):
        events, _ = self.translate([
            {'type': 'item.started', 'item': {'id': 'item_1', 'type': 'command_execution', 'command': 'ls'}},
            {'type': 'item.completed', 'item': {'id': 'item_1', 'type': 'command_execution', 'command': 'ls',
                                                'aggregated_output': 'x', 'exit_code': 0, 'status': 'completed'}},
            {'type': 'item.completed', 'item': {'id': 'item_2', 'type': 'file_change', 'status': 'completed',
                                                'changes': [{'path': 'a', 'kind': 'delete'}]}},
        ])
        self.assertEqual(len(events), 4)
        self.assertTrue(all(e['buddy_session'] == 'pocket_test' and 'buddy_run' in e for e in events))


class ClaudeTagTests(unittest.TestCase):
    """The helper's tag() filter applied to every Claude Code stream-json line."""
    TAG = 'tag() {' + SOURCE.split('\ntag() {', 1)[1].split('\n\nif ', 1)[0]

    def tag(self, event):
        script = self.TAG + '\nSID=chat; RUN=run\ntag\n'
        out = subprocess.run(['bash', '-c', script], input=json.dumps(event), text=True, capture_output=True, check=True)
        return json.loads(out.stdout)

    def test_tool_results_keep_text_and_drop_image_data(self):
        event = {'type': 'user', 'parent_tool_use_id': None, 'tool_use_result': {'file': {'base64': 'AAAA'}},
                 'message': {'role': 'user', 'content': [
                     {'type': 'tool_result', 'tool_use_id': 'toolu_1', 'is_error': False,
                      'content': [{'type': 'text', 'text': 'Zrzut ekranu'},
                                  {'type': 'image', 'source': {'type': 'base64', 'data': 'AAAA'}}]},
                     {'type': 'tool_result', 'tool_use_id': 'toolu_2', 'content': 'plain text'}]}}
        out = self.tag(event)
        self.assertNotIn('tool_use_result', out)
        self.assertEqual(out['message']['content'][0]['content'], [{'type': 'text', 'text': 'Zrzut ekranu'}, {'type': 'image'}])
        self.assertEqual(out['message']['content'][1], event['message']['content'][1])
        self.assertIsNone(out['parent_tool_use_id'])
        self.assertEqual((out['buddy_session'], out['buddy_run']), ('chat', 'run'))

    def test_other_events_are_only_correlated(self):
        event = {'type': 'assistant', 'message': {'content': [{'type': 'tool_use', 'id': 'toolu_1', 'name': 'Read',
                                                               'input': {'file_path': '/a/b'}}]}}
        self.assertEqual(self.tag(event), dict(event, buddy_session='chat', buddy_run='run'))

if __name__ == '__main__':
    unittest.main(verbosity=2)
