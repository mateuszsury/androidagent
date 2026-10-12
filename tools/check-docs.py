#!/usr/bin/env python3
"""Check first-party Markdown links, local anchors and documentation PNG files."""
import binascii
from html.parser import HTMLParser
from pathlib import Path
import re
import struct
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]


class HtmlLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []

    def handle_starttag(self, tag, attrs):
        field = 'src' if tag == 'img' else 'href' if tag == 'a' else None
        for key, value in attrs:
            if key == field and value:
                self.links.append(value)


def without_code(text):
    return re.sub(r'^(```|~~~).*?^\1\s*$', '', text, flags=re.MULTILINE | re.DOTALL)


def anchors(path):
    found = set()
    repeated = {}
    for heading in re.findall(r'^#{1,6}\s+(.+?)\s*#*$', without_code(path.read_text()), re.MULTILINE):
        heading = re.sub(r'[^\w\- ]', '', heading.lower()).replace(' ', '-')
        count = repeated.get(heading, 0)
        repeated[heading] = count + 1
        found.add(heading + (f'-{count}' if count else ''))
    return found


def main():
    docs = sorted({*ROOT.glob('*.md'), *ROOT.joinpath('docs').rglob('*.md'),
                   ROOT/'docker/SOURCES.md', ROOT/'terminal/README.md',
                   ROOT/'terminal/SOURCES.md', ROOT/'usb/README.md',
                   *ROOT.joinpath('.github').rglob('*.md')})
    errors = []
    links_checked = 0
    for path in docs:
        body = without_code(path.read_text(encoding='utf-8'))
        html = HtmlLinks()
        html.feed(body)
        links = re.findall(r'\[[^\]\n]*\]\(([^\s)]+)(?:\s+"[^"]*")?\)', body) + html.links
        for link in links:
            url = urlsplit(link.strip('<>'))
            if url.scheme or url.netloc:
                continue
            target = (path.parent / unquote(url.path)).resolve() if url.path else path
            links_checked += 1
            if not target.is_relative_to(ROOT) or not target.exists():
                errors.append(f'{path.relative_to(ROOT)}: missing local target {link}')
            elif url.fragment and target.suffix.lower() == '.md' and unquote(url.fragment) not in anchors(target):
                errors.append(f'{path.relative_to(ROOT)}: missing anchor {link}')
    images = list(ROOT.joinpath('docs/images').glob('*.png'))
    for path in images:
        data = path.read_bytes()
        if len(data) < 33 or data[:8] != b'\x89PNG\r\n\x1a\n' or data[12:16] != b'IHDR':
            errors.append(f'{path.relative_to(ROOT)}: invalid PNG header')
            continue
        width, height = struct.unpack('>II', data[16:24])
        crc = struct.unpack('>I', data[29:33])[0]
        if not width or not height or binascii.crc32(data[12:29]) != crc:
            errors.append(f'{path.relative_to(ROOT)}: invalid dimensions or IHDR CRC')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print(f'PASS: {len(docs)} Markdown files, {links_checked} local links/anchors, {len(images)} PNG images')
    return 0


if __name__ == '__main__':
    sys.exit(main())
