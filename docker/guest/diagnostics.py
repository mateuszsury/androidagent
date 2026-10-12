"""Bounded, per-boot guest evidence. Never exports arbitrary files or credentials."""
import datetime
import json
import os
import re
import shutil
import stat
import threading
import time
from pathlib import Path

REVISION = 3
ROOT = Path('/var/lib/androidagent/diagnostics')
BOOT_PATTERN = re.compile(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}')
MAX_BOOTS = 5
LOG_LIMIT = 512 * 1024
EVENT_LIMIT = 1024 * 1024
MAX_RESPONSE = 256 * 1024
LOG_NAMES = ('init.log', 'engine.log', 'containerd.log', 'docker.log', 'base-images.log', 'agent.log')
LOG_LOCK = threading.RLock()
CHILD_NAMES = {'iptables', 'ip6tables', 'iptables-nft', 'ip6tables-nft', 'xtables-nft-multi', 'nft', 'modprobe', 'sleep'}


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def boot_id():
    value = os.environ.get('AA_BOOT_ID', '')
    if not value:
        value = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    if not BOOT_PATTERN.fullmatch(value):
        raise ValueError('Invalid guest boot id')
    return value


def read_regular(path, limit, tail=False):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode):
            raise ValueError('Only regular diagnostic files are supported')
        if tail and info.st_size > limit:
            source.seek(info.st_size - limit)
        return source.read(limit), info.st_size


def append_log(directory, name, data, durable=False):
    if name not in LOG_NAMES and name != 'events.jsonl':
        raise ValueError('Unknown diagnostic log')
    limit = EVENT_LIMIT if name == 'events.jsonl' else LOG_LIMIT
    data = data[-limit:]
    path = Path(directory) / name
    with LOG_LOCK:
        fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            if not stat.S_ISREG(os.fstat(fd).st_mode):
                raise ValueError('Not a regular diagnostic log')
            view = memoryview(data)
            while view:
                count = os.write(fd, view)
                view = view[count:]
            if durable:
                os.fsync(fd)
        finally:
            os.close(fd)
        if path.stat().st_size > limit:
            retained, unused = read_regular(path, limit, tail=True)
            # Keep the inode so PID1 tail -f continues to follow it.
            fd = os.open(path, os.O_WRONLY | os.O_NOFOLLOW)
            try:
                os.lseek(fd, 0, os.SEEK_SET)
                view = memoryview(retained)
                while view:
                    count = os.write(fd, view)
                    view = view[count:]
                os.ftruncate(fd, len(retained))
                if durable:
                    os.fsync(fd)
            finally:
                os.close(fd)


def atomic_json(path, value, durable=False):
    path = Path(path)
    temporary = path.with_suffix('.part')
    data = json.dumps(value).encode()
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)
        if durable:
            stream.flush()
            os.fsync(stream.fileno())
    temporary.replace(path)
    if durable:
        fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def proc_info(pid):
    try:
        text = Path(f'/proc/{int(pid)}/stat').read_text()
        end = text.rfind(')')
        fields = text[end + 2:].split()
        result = {'pid': int(pid), 'name': text[text.index('(') + 1:end], 'state': fields[0],
                  'ppid': int(fields[1]), 'cpu_ticks': int(fields[11]) + int(fields[12]),
                  'start_ticks': int(fields[19]), 'rss_pages': int(fields[21]), 'vsize_bytes': int(fields[20])}
        result['cpu_seconds'] = round(result['cpu_ticks'] / os.sysconf('SC_CLK_TCK'), 3)
        try:
            result['io'] = {key: int(value.strip()) for key, value in
                            (line.split(':', 1) for line in Path(f'/proc/{pid}/io').read_text().splitlines())
                            if key in ('read_bytes', 'write_bytes', 'rchar', 'wchar')}
        except (OSError, ValueError):
            pass
        try:
            result['wchan'] = Path(f'/proc/{pid}/wchan').read_text().strip()[:128]
        except OSError:
            pass
        return result
    except (OSError, ValueError, IndexError):
        return {'pid': int(pid), 'unavailable': True}


def safe_child_argv(pid):
    try:
        data = Path(f'/proc/{pid}/cmdline').read_bytes()[:4096]
        args = data.decode(errors='replace').strip('\0').split('\0')[:24]
        output, hidden = [], False
        for arg in args:
            secret = hidden or bool(re.search(r'(?i)(bearer |token=|password=|secret=|api.key=)', arg))
            output.append('[redacted]' if secret else arg[:128])
            hidden = arg.lower() in ('--comment', '--password', '--token', '--api-key', '--secret')
        return output
    except OSError:
        return []


def checked_startup(startup):
    """Verify the same live supervisor before accepting its current ready file."""
    identity = startup.get('supervisor', {})
    if not isinstance(identity, dict) or not isinstance(identity.get('pid'), int) or identity['pid'] <= 0 \
            or not isinstance(identity.get('start_ticks'), int):
        live = {'alive': False, 'reason': 'missing_process_identity'}
    else:
        info = proc_info(identity['pid'])
        if info.get('unavailable'):
            reason = 'process_unavailable'
        elif info.get('start_ticks') != identity['start_ticks']:
            reason = 'pid_reused'
        elif info.get('state') in ('Z', 'X', 'x'):
            reason = 'process_not_running'
        else:
            reason = 'verified'
        live = {'alive': reason == 'verified', 'reason': reason, 'process': info}
    result = {**startup, 'supervisor_liveness': live}
    if startup.get('stage') == 'ready' and not live['alive']:
        result.update({'last_persisted_stage': 'ready', 'stage': 'error', 'error_type': 'SupervisorLivenessError',
                       'detail': 'Nie można potwierdzić żywego procesu nadzoru środowiska',
                       'state_source': 'current_process_liveness_check'})
    return result


def snapshot(managed=()):
    result = {'sampled_at_utc': utc(), 'monotonic_seconds': round(time.monotonic(), 3), 'processes': [], 'children': []}
    try:
        result['guest_uptime_seconds'] = float(Path('/proc/uptime').read_text().split()[0])
        result['memory_kb'] = {key: int(value.split()[0]) for key, value in
                               (line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
                               if key in ('MemTotal', 'MemAvailable', 'Dirty', 'Writeback', 'SwapFree')}
        result['cpu_ticks'] = [int(value) for value in Path('/proc/stat').read_text().splitlines()[0].split()[1:]]
        result['clock_source'] = Path('/sys/devices/system/clocksource/clocksource0/current_clocksource').read_text().strip()
    except (OSError, ValueError, IndexError):
        pass
    for item in managed:
        info = proc_info(item['pid'])
        if item.get('start_ticks') is not None and info.get('start_ticks') is not None and item['start_ticks'] != info['start_ticks']:
            info = {'pid': item['pid'], 'unavailable': True, 'pid_reused': True}
        result['processes'].append({**item, **info})
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            name = (entry / 'comm').read_text().strip()
        except OSError:
            continue
        if name in CHILD_NAMES:
            info = proc_info(int(entry.name))
            info['argv'] = safe_child_argv(int(entry.name))
            result['children'].append(info)
            if len(result['children']) >= 16:
                break
    try:
        lock = Path('/run/xtables.lock').stat()
        result['xtables_lock'] = {'inode': lock.st_ino, 'device': lock.st_dev}
        # A slow iptables command is not proof of a lock; preserve actual holders.
        result['xtables_lock']['kernel_locks'] = [line[:512] for line in Path('/proc/locks').read_text().splitlines()
                                                if re.search(r':' + str(lock.st_ino) + r'(?: |$)', line)][:8]
    except OSError:
        pass
    result['pressure'] = {}
    for kind in ('cpu', 'memory', 'io'):
        try:
            result['pressure'][kind] = Path('/proc/pressure/' + kind).read_text()[:512]
        except OSError:
            pass
    return result


class Journal:
    def __init__(self, root=ROOT, identity=None, runtime_state='/run/androidagent-engine.json'):
        self.root = Path(root)
        self.boot_id = identity or boot_id()
        if not BOOT_PATTERN.fullmatch(self.boot_id):
            raise ValueError('Invalid boot id')
        self.directory = self.root / self.boot_id
        self.runtime_state = Path(runtime_state)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.root.is_symlink() or self.directory.is_symlink():
            raise ValueError('Diagnostic directory cannot be a symlink')
        self.directory.mkdir(exist_ok=True, mode=0o700)
        self.root.chmod(0o700)
        self.directory.chmod(0o700)
        self.prune()
        self.log_bytes = {}

    def prune(self):
        directories = sorted([path for path in self.root.iterdir() if BOOT_PATTERN.fullmatch(path.name)
                              and path.is_dir() and not path.is_symlink() and path.name != self.boot_id],
                             key=lambda path: path.stat().st_mtime, reverse=True)
        for path in directories[MAX_BOOTS - 1:]:
            shutil.rmtree(path)

    def log(self, name, data):
        with LOG_LOCK:
            self.log_bytes[name] = self.log_bytes.get(name, 0) + len(data)
            append_log(self.directory, name, data)

    def counters(self):
        with LOG_LOCK:
            return dict(self.log_bytes)

    def publish(self, value, durable=False):
        value = {**value, 'boot_id': self.boot_id, 'runtime_revision': REVISION, 'recorded_at_utc': utc()}
        atomic_json(self.runtime_state, value)
        atomic_json(self.directory / 'startup.json', value, durable=True)
        encoded = json.dumps(value).encode()
        append_log(self.directory, 'events.jsonl', encoded + b'\n', durable=durable)
        append_log(self.directory, 'engine.log', b'ANDROIDAGENT_ENGINE ' + encoded + b'\n', durable=durable)
        if durable:
            for name in LOG_NAMES:
                path = self.directory / name
                try:
                    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
                    try:
                        os.fsync(fd)
                    finally:
                        os.close(fd)
                except FileNotFoundError:
                    pass
        return value


def diagnostics(args, token='', root=ROOT):
    root = Path(root)
    identity = args.get('boot_id') or boot_id()
    if not isinstance(identity, str) or not BOOT_PATTERN.fullmatch(identity):
        raise ValueError('Invalid diagnostics boot id')
    directory = root / identity
    if root.is_symlink() or directory.is_symlink() or not directory.is_dir():
        raise ValueError('Diagnostic boot does not exist')
    maximum = int(args.get('max_bytes', MAX_RESPONSE))
    tail = int(args.get('tail_bytes', 8192))
    if not 65536 <= maximum <= MAX_RESPONSE or not 0 <= tail <= 65536:
        raise ValueError('Diagnostics limits: max_bytes 65536..262144, tail_bytes 0..65536')
    try:
        raw, unused = read_regular(directory / 'startup.json', 65536)
        startup = json.loads(raw)
    except FileNotFoundError:
        startup = {'stage': 'starting', 'boot_id': identity, 'runtime_revision': REVISION}
    response = {'schema': 1, 'runtime_revision': REVISION, 'boot_id': identity, 'startup': startup,
                'logs': {}, 'events': [], 'available_boots': [],
                'limits': {'max_bytes': maximum, 'tail_bytes': tail, 'retained_boots': MAX_BOOTS,
                           'per_log_max_bytes': LOG_LIMIT, 'events_max_bytes': EVENT_LIMIT}}
    current = identity == boot_id()
    if current:
        response['startup'] = checked_startup(startup)
    response['historical_snapshot'] = not current
    response['snapshot'] = snapshot(startup.get('progress', {}).get('processes', [])) if current else startup.get('progress', {}).get('snapshot', {})
    for name in LOG_NAMES:
        try:
            data, total = read_regular(directory / name, tail, tail=True)
            text = data.decode(errors='replace')
            if token:
                text = text.replace(token, '[vm-token]')
            response['logs'][name] = {'tail': text, 'bytes_on_disk': total,
                                      'returned_bytes': len(data), 'truncated': total > len(data)}
        except FileNotFoundError:
            pass
    try:
        raw, unused = read_regular(directory / 'events.jsonl', 65536, tail=True)
        for line in raw.splitlines()[-20:]:
            try:
                response['events'].append(json.loads(line))
            except ValueError:
                pass
    except FileNotFoundError:
        pass
    directories = sorted([p for p in root.iterdir() if BOOT_PATTERN.fullmatch(p.name) and p.is_dir() and not p.is_symlink()],
                         key=lambda p: p.stat().st_mtime, reverse=True)
    response['available_boots'] = [p.name for p in directories[:MAX_BOOTS]]
    if token:
        # A probe error or a historical event must not leak the VM bearer token
        # either. Keep structural types and keys intact while redacting values.
        def redact(value):
            if isinstance(value, str):
                return value.replace(token, '[vm-token]')
            if isinstance(value, list):
                return [redact(item) for item in value]
            if isinstance(value, dict):
                return {key: redact(item) for key, item in value.items()}
            return value
        response = redact(response)
    # Bound the bytes of the exact encoder used by agent.Handler.reply, including
    # expansion of non-ASCII log characters. Never silently send >256 KiB.
    while len(json.dumps(response).encode()) > maximum:
        if response['events']:
            response['events'].pop(0)
            response['events_truncated'] = True
            continue
        candidates = [(len(info['tail']), name) for name, info in response['logs'].items() if info['tail']]
        if candidates:
            unused, name = max(candidates)
            info = response['logs'][name]
            info['tail'] = info['tail'][len(info['tail']) // 2 + 1:]
            info['returned_bytes'] = len(info['tail'].encode())
            info['truncated'] = True
            continue
        raise ValueError('Diagnostic metadata exceeds bounded response')
    return response
