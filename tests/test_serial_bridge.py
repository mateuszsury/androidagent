"""Real pyserial URL contract against an HTTP fixture; not a physical USB test."""
import base64
import http.server
import importlib.util
import json
from pathlib import Path
import threading
import unittest

try:
    import serial
except ImportError:
    serial = None


@unittest.skipIf(serial is None, 'Install test dependency pyserial==3.5')
class PyserialBridgeTests(unittest.TestCase):
    def setUp(self):
        self.data = bytearray()
        self.configuration = {}
        test = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                if self.headers.get('Authorization') != 'Bearer fixture':
                    self.send_error(403)
                    return
                args = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                op = args['op']
                result = {}
                if op == 'status':
                    result = {'cursor': len(test.data)}
                elif op == 'read':
                    result = {'cursor': len(test.data), 'data': base64.b64encode(test.data[args['cursor']:]).decode(), 'lost': 0}
                elif op == 'write':
                    data = base64.b64decode(args['data'])
                    test.data.extend(data)
                    result = {'bytes': len(data)}
                else:
                    test.configuration[op] = args
                body = json.dumps(result).encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        path = Path(__file__).resolve().parents[1] / 'docker/guest/python/androidagent_serial/protocol_androidagent.py'
        spec = importlib.util.spec_from_file_location('bridge_under_test', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        endpoint = 'http://127.0.0.1:' + str(self.server.server_port)

        class FixtureSerial(module.Serial):
            def _request(self, *args, **kwargs):
                self._endpoint = endpoint
                return super()._request(*args, **kwargs)

        self.port = FixtureSerial('androidagent://10.0.2.2:18782/fixture', baudrate=115200, timeout=.05)

    def tearDown(self):
        self.port.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_binary_io_and_timeout(self):
        payload = bytes(range(256))
        self.assertEqual(256, self.port.write(payload))
        self.assertEqual(256, self.port.in_waiting)
        self.assertEqual(payload, self.port.read(256))
        self.assertEqual(b'', self.port.read(1))

    def test_baud_dtr_rts_break_follow_pyserial_properties(self):
        self.port.baudrate = 9600
        self.port.dtr = True
        self.port.rts = False
        self.port.break_condition = True
        self.assertEqual(9600, self.configuration['configure']['baud'])
        self.assertEqual({'op': 'control', 'dtr': True, 'rts': False, 'break': True}, self.configuration['control'])

    def test_reset_input_discards_previous_bytes_without_resetting_target(self):
        self.port.write(b'old')
        self.port.reset_input_buffer()
        self.port.write(b'new')
        self.assertEqual(b'new', self.port.read(3))
        self.assertNotIn('control', self.configuration)

    def test_close_and_non_qemu_host_rejected(self):
        self.port.close()
        with self.assertRaises(serial.PortNotOpenError):
            self.port.write(b'x')
        self.port.port = 'androidagent://example.com:18782/fixture'
        with self.assertRaises(serial.SerialException):
            self.port.open()
