#!/usr/bin/env python3
"""Record actual APK package versions, licenses and exact upstream build commits."""
import json
import sys
from pathlib import Path

database, output = map(Path, sys.argv[1:])
packages = []
for block in database.read_text().split('\n\n'):
    fields = dict(line.split(':', 1) for line in block.splitlines() if ':' in line)
    if 'P' not in fields:
        continue
    packages.append({name: fields.get(key) for key, name in [
        ('P', 'name'), ('V', 'version'), ('A', 'architecture'), ('L', 'license'),
        ('o', 'origin'), ('c', 'aports_commit'), ('U', 'homepage')]})
output.write_text(json.dumps(packages, indent=2) + '\n')
