#!/usr/bin/env python3
"""Ship guest control code in initramfs without replacing the 0.4.2 base disk.

Only /opt/androidagent's owned code is refreshed before switch_root. Docker's
images, containerd content, task files and Android exports are untouched.
"""
import gzip
import hashlib
import json
import re
import stat
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BEGIN = '# ANDROIDAGENT_RUNTIME_BEGIN'
END = '# ANDROIDAGENT_RUNTIME_END'
ANCHOR = '\t$MOCK sync\n\texec switch_root $switch_root_opts $sysroot $chart_init "$KOPT_init" $KOPT_init_args'


def unpack(raw):
    entries, offset = [], 0
    while raw[offset:offset + 6] == b'070701':
        fields = [int(raw[offset + 6 + index * 8:offset + 14 + index * 8], 16) for index in range(13)]
        name = raw[offset + 110:offset + 110 + fields[11] - 1].decode()
        data_start = (offset + 110 + fields[11] + 3) & ~3
        data = raw[data_start:data_start + fields[6]]
        if len(data) != fields[6]:
            raise ValueError('Truncated initramfs')
        offset = (data_start + fields[6] + 3) & ~3
        if name == 'TRAILER!!!':
            return entries
        entries.append((name, fields, data))
    raise ValueError('Expected newc initramfs with trailer')


def pack(entries):
    output = bytearray()
    for name, fields, data in entries + [('TRAILER!!!', [0] * 13, b'')]:
        fields = list(fields)
        encoded = name.encode() + b'\0'
        fields[6], fields[11] = len(data), len(encoded)
        output.extend(b'070701' + ''.join(f'{field:08x}' for field in fields).encode())
        output.extend(encoded)
        output.extend(b'\0' * (-len(output) % 4))
        output.extend(data)
        output.extend(b'\0' * (-len(output) % 4))
    output.extend(b'\0' * (-len(output) % 512))
    return bytes(output)


def payload():
    source = ROOT / 'docker/guest'
    files = {name: (source / name).read_bytes() for name in ('init.sh', 'agent.py', 'engine.py', 'diagnostics.py')}
    files.update({str(path.relative_to(source)): path.read_bytes()
                  for path in sorted((source / 'python').rglob('*.py'))})
    runtime = {'revision': 3, 'files': {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
    files['runtime.json'] = (json.dumps(runtime, indent=2) + '\n').encode()
    lines = ['#!/bin/sh', 'set -eu', 'root=$1', 'source=/androidagent-runtime',
             'test -d "$root/opt/androidagent"', '# Only owned code; never copy a base disk or task directory.']
    for name in files:
        lines.extend([f'mkdir -p "$root/opt/androidagent/{str(Path(name).parent)}"',
                      f'cp "$source/{name}" "$root/opt/androidagent/{name}.aa-new"',
                      f'chmod {"755" if name == "init.sh" else "644"} "$root/opt/androidagent/{name}.aa-new"',
                      f'mv -f "$root/opt/androidagent/{name}.aa-new" "$root/opt/androidagent/{name}"'])
    lines.extend(['sync', 'echo ANDROIDAGENT_RUNTIME_REVISION_3_INSTALLED'])
    files['install.sh'] = ('\n'.join(lines) + '\n').encode()
    return files, runtime


def prepare(abi):
    directory = ROOT / 'app/src/main/assets/docker' / abi
    path = directory / 'initramfs-virt'
    entries = unpack(gzip.decompress(path.read_bytes()))
    files, runtime = payload()
    updated, found = [], False
    for name, fields, data in entries:
        if name.startswith('androidagent-runtime'):
            continue
        if name == 'init':
            text = data.decode()
            text = re.sub(r'\n\t' + BEGIN + r'.*?' + END + r'\n', '\n', text, flags=re.DOTALL)
            if text.count(ANCHOR) != 1:
                raise ValueError(f'{abi}: Alpine switch_root anchor changed')
            injection = '\t' + BEGIN + '\n\tsh /androidagent-runtime/install.sh "$sysroot" || { echo ANDROIDAGENT_RUNTIME_INSTALL_FAILED; recovery_shell; }\n\t' + END + '\n'
            text = text.replace(ANCHOR, injection + ANCHOR)
            data, found = text.encode(), True
        updated.append((name, fields, data))
    if not found:
        raise ValueError('Missing Alpine init')
    directories = {'androidagent-runtime'}
    for name in files:
        directories.update('androidagent-runtime/' + str(parent) for parent in Path(name).parents if str(parent) != '.')
    for inode, name in enumerate(sorted(directories), 100000):
        updated.append((name, [inode, stat.S_IFDIR | 0o755, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0], b''))
    for inode, (name, data) in enumerate(files.items(), 110000):
        mode = 0o755 if name.endswith('.sh') else 0o644
        updated.append(('androidagent-runtime/' + name, [inode, stat.S_IFREG | mode, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0], data))
    temporary = path.with_suffix('.part')
    temporary.write_bytes(gzip.compress(pack(updated), compresslevel=6, mtime=0))
    temporary.replace(path)
    manifest = json.loads((directory / 'manifest.json').read_text())
    manifest['version'] = 3
    manifest['runtime_revision'] = runtime['revision']
    manifest['migration'] = 'same-base-and-kernel-owned-runtime-in-initramfs'
    manifest['runtime'] = runtime
    manifest['files']['initramfs-virt'] = hashlib.sha256(path.read_bytes()).hexdigest()
    (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'{abi}: runtime revision 3 embedded; base disk and kernel unchanged')


if __name__ == '__main__':
    import sys
    for abi in sys.argv[1:] or ('arm64-v8a', 'x86_64'):
        prepare(abi)
