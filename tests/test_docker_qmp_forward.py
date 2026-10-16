"""Runtime preview forwards against a real QEMU slirp backend through QMP.

DockerVm adds and removes per-task forwards with human-monitor-command. slirp
reports a refused forward as returned text, not as a QMP error; DockerVm keys
its retry on that text. Skipped when no host QEMU is installed.
"""
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
VM = (ROOT / 'app/src/main/java/dev/mateuszsury/androidagent/DockerVm.java').read_text()
QEMU = shutil.which('qemu-system-x86_64')


def constant(name):
    return re.search(name + r'\s*=\s*"?([^",;]+)"?', VM).group(1)


def listening(port):
    found = []
    for line in Path('/proc/net/tcp').read_text().splitlines()[1:]:
        fields = line.split()
        address, hex_port = fields[1].split(':')
        if fields[3] == '0A' and int(hex_port, 16) == port:
            found.append('.'.join(str(int(address[i:i + 2], 16)) for i in (6, 4, 2, 0)))
    return found


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


class SourceContractTests(unittest.TestCase):
    def test_runtime_forwards_target_the_boot_netdev(self):
        netdev = constant('NETDEV')
        self.assertIn('"user,id=' + netdev + ',hostfwd=tcp:127.0.0.1:"', VM)
        self.assertIn('hmp("hostfwd_add " + NETDEV + " tcp:" + bind + ":" + host + "-:" + guestPort)', VM)
        self.assertIn('hmp("hostfwd_remove " + NETDEV + " tcp:" + f.bind + ":" + f.host)', VM)
        self.assertIn('reply.startsWith("Could not set up host forwarding rule")', VM)

    def test_port_ranges_match_the_guest_agent(self):
        agent = (ROOT / 'docker/guest/agent.py').read_text()
        self.assertIn('PREVIEW_GUEST_PORTS = range(8080, 8180)', agent)
        self.assertEqual(('8080', '8179'), (constant('GUEST_PREVIEW_FIRST'), constant('GUEST_PREVIEW_LAST')))
        self.assertEqual(('18800', '18899'), (constant('HOST_PREVIEW_FIRST'), constant('HOST_PREVIEW_LAST')))
        self.assertIn('PREVIEW_PORT + "-:8080"', VM)


@unittest.skipUnless(QEMU, 'qemu-system-x86_64 is not installed')
class QmpForwardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.socket_path = str(Path(self.temp.name) / 'qmp.sock')
        self.api = free_port()
        self.qemu = subprocess.Popen([QEMU, '-machine', 'q35', '-accel', 'tcg', '-m', '64', '-nodefaults', '-S',
                                      '-display', 'none', '-monitor', 'none',
                                      '-netdev', f'user,id={constant("NETDEV")},hostfwd=tcp:127.0.0.1:{self.api}-:7080',
                                      '-device', 'virtio-net-pci,netdev=net,romfile=',
                                      '-qmp', 'unix:' + self.socket_path + ',server=on,wait=off'],
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        until = time.monotonic() + 20
        while not os.path.exists(self.socket_path) and time.monotonic() < until:
            if self.qemu.poll() is not None:
                self.skipTest('QEMU could not start here: ' + self.qemu.stdout.read().decode()[-500:])
            time.sleep(.05)

    def tearDown(self):
        self.qemu.kill()
        self.qemu.wait()
        self.qemu.stdout.close()
        self.temp.cleanup()

    def hmp(self, line):
        # Same exchange as DockerVm.qmp: greeting, qmp_capabilities, command.
        with socket.socket(socket.AF_UNIX) as client:
            client.settimeout(10)
            client.connect(self.socket_path)
            stream = client.makefile('rw')
            self.assertIn('QMP', json.loads(stream.readline()))
            for execute, arguments in (('qmp_capabilities', None), ('human-monitor-command', {'command-line': line})):
                request = {'execute': execute, 'id': execute}
                if arguments:
                    request['arguments'] = arguments
                stream.write(json.dumps(request) + '\n')
                stream.flush()
                while True:
                    reply = json.loads(stream.readline())
                    if reply.get('id') == execute:
                        break
            self.assertNotIn('error', reply)
            return reply['return'].strip()

    def test_add_collide_remove_and_lan_bind(self):
        netdev = constant('NETDEV')
        host = free_port()
        self.assertEqual('', self.hmp(f'hostfwd_add {netdev} tcp:127.0.0.1:{host}-:8081'))
        self.assertEqual(['127.0.0.1'], listening(host))
        self.assertTrue(self.hmp(f'hostfwd_add {netdev} tcp:127.0.0.1:{host}-:8082')
                        .startswith('Could not set up host forwarding rule'))
        # The loopback boot forward blocks a wildcard bind of the same port, so
        # LAN mode never reuses 18781 and takes a port from the runtime range.
        self.assertTrue(self.hmp(f'hostfwd_add {netdev} tcp:0.0.0.0:{self.api}-:8080')
                        .startswith('Could not set up host forwarding rule'))
        self.assertIn('removed', self.hmp(f'hostfwd_remove {netdev} tcp:127.0.0.1:{host}'))
        self.assertEqual([], listening(host))
        lan = free_port()
        self.assertEqual('', self.hmp(f'hostfwd_add {netdev} tcp:0.0.0.0:{lan}-:8080'))
        self.assertEqual(['0.0.0.0'], listening(lan))
        self.assertIn('removed', self.hmp(f'hostfwd_remove {netdev} tcp:0.0.0.0:{lan}'))
        self.assertEqual([], listening(lan))


if __name__ == '__main__':
    unittest.main()
