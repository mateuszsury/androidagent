"""pyserial transport for an explicitly leased Android USB UART. No MCP credential."""
import base64
import json
import time
import urllib.parse
import urllib.request
from serial.serialutil import SerialBase, SerialException, PortNotOpenError


class Serial(SerialBase):
    def open(self):
        if self.is_open:
            raise SerialException('Already open')
        parsed = urllib.parse.urlsplit(self.port)
        if parsed.scheme != 'androidagent' or parsed.hostname != '10.0.2.2' or parsed.port != 18782:
            raise SerialException('Use the task-provided Android UART URL')
        self._endpoint = 'http://10.0.2.2:18782/serial'
        self._token = parsed.path.lstrip('/')
        self._buffer = bytearray()
        self._cursor = self._request('status')['cursor']
        self.is_open = True
        try:
            self._reconfigure_port()
        except Exception:
            self.is_open = False
            raise

    def _request(self, op, **args):
        req = urllib.request.Request(self._endpoint, json.dumps(dict(op=op, **args)).encode(),
            {'Authorization': 'Bearer ' + self._token, 'Content-Type': 'application/json'})
        # Do not route the private QEMU host endpoint through image HTTP proxies.
        try:
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=5) as response:
                result = json.loads(response.read(180000))
        except Exception as error:
            raise SerialException('Android USB bridge: ' + str(error)) from error
        if 'error' in result:
            raise SerialException(result['error'])
        return result

    def _reconfigure_port(self):
        parity = {'N': 0, 'O': 1, 'E': 2, 'M': 3, 'S': 4}[self.parity]
        stop = {1: 1, 2: 2, 1.5: 3}[self.stopbits]
        self._request('configure', baud=self.baudrate, bits=self.bytesize, stop=stop, parity=parity)

    def close(self):
        self.is_open = False

    def _fetch(self):
        result = self._request('read', cursor=self._cursor, limit=65536)
        self._cursor = result['cursor']
        if result['lost']:
            raise SerialException('UART receive buffer overflow')
        self._buffer.extend(base64.b64decode(result['data'], validate=True))

    @property
    def in_waiting(self):
        if not self.is_open:
            raise PortNotOpenError()
        self._fetch()
        return len(self._buffer)

    def read(self, size=1):
        if not self.is_open:
            raise PortNotOpenError()
        deadline = None if self.timeout is None else time.monotonic() + self.timeout
        while len(self._buffer) < size:
            self._fetch()
            if deadline is not None and time.monotonic() >= deadline:
                break
            if len(self._buffer) < size:
                time.sleep(.02)
        data = bytes(self._buffer[:size])
        del self._buffer[:size]
        return data

    def write(self, data):
        if not self.is_open:
            raise PortNotOpenError()
        data = bytes(data)
        sent = 0
        while sent < len(data):
            block = data[sent:sent + 65536]
            sent += self._request('write', data=base64.b64encode(block).decode())['bytes']
        return sent

    def _update_dtr_state(self):
        self._control()

    def _update_rts_state(self):
        self._control()

    def _update_break_state(self):
        self._control()

    def _control(self):
        self._request('control', dtr=self._dtr_state, rts=self._rts_state, **{'break': self._break_state})

    def reset_input_buffer(self):
        self._buffer.clear()
        self._cursor = self._request('status')['cursor']

    def reset_output_buffer(self):
        pass

    def flush(self):
        pass
