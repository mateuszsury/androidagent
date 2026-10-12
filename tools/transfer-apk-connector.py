#!/usr/bin/env python3
"""Stage bounded APK Git blobs for the existing GitHub connector.

The connector's JSON request cap includes base64 expansion: 8 MiB raw parts
fit its 16 MiB request cap. This helper never POSTs APK data. A caller reads the
staged part, calls github.create_blob, and calls this helper again. Pending
parts are GET-verified before acceptance; no accepted part is ever reposted.
The existing transfer implementation retains all journal/hash/fsync checks.
"""
import argparse
import importlib.util
import json
import re
import sys
from pathlib import Path


SPEC = importlib.util.spec_from_file_location('apk_git_blobs', Path(__file__).with_name('transfer-apk-git-blobs.py'))
TRANSFER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TRANSFER)


def stage(transfer):
    transfer.resume_pending()
    number = len(transfer.journal['parts'])
    with transfer.apk.open('rb') as source:
        source.seek(number * TRANSFER.PART_BYTES)
        data = source.read(TRANSFER.PART_BYTES)
    if not data:
        transfer.journal['parts_complete'] = True
        transfer.validate_completed_parts()
        transfer.save()
        return {'complete': True, 'parts': number, 'apk': transfer.apk_fields}
    entry = TRANSFER.describe(data, part=number)
    transfer.journal['pending'] = {'kind': 'part', 'entry': entry}
    transfer.save()  # Durable before the external connector POST.
    return {'complete': False, 'entry': entry, 'offset': number * TRANSFER.PART_BYTES,
            'chunk_bytes': TRANSFER.PART_BYTES, 'repository': transfer.args.repo}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--expected-bytes', type=int, required=True)
    parser.add_argument('--expected-sha256', required=True)
    parser.add_argument('--repo', default='mateuszsury/androidagent')
    parser.add_argument('--journal-dir', type=Path, required=True)
    parser.add_argument('--chunk-mib', type=int, choices=(1, 2, 4, 8), default=8)
    parser.add_argument('--attach-report', type=Path)
    args = parser.parse_args()
    if args.expected_bytes <= 0 or not re.fullmatch(r'[0-9a-f]{64}', args.expected_sha256):
        parser.error('Positive expected bytes and lowercase SHA256 are required.')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo):
        parser.error('Repository must have owner/name format.')
    TRANSFER.PART_BYTES = args.chunk_mib * 1024 * 1024
    args.report = args.attach_report
    args.attach_report = args.report is not None
    args.parts_only = not args.attach_report
    try:
        transfer = TRANSFER.Transfer(args)
        if args.attach_report:
            transfer.run()  # Only small report/manifest POSTs; APK parts exist.
        else:
            print('CONNECTOR_STAGE ' + json.dumps(stage(transfer), sort_keys=True), flush=True)
    except TRANSFER.TransferError as error:
        print('ERROR: ' + str(error), file=sys.stderr, flush=True)
        return error.exit_code
    except (OSError, ValueError, KeyError, TypeError) as error:
        print('ERROR: ' + type(error).__name__ + ' while staging metadata; transfer stopped.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
