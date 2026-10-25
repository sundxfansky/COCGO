#!/usr/bin/env python3
"""Reject class dependencies that would bind a feature to another feature's implementation."""
import argparse
import json
from pathlib import Path
import re
import struct


def utf8_constants(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Not a JVM class')
    count = struct.unpack_from('>H', data, 8)[0]
    offset, index = 10, 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, offset)[0]
            offset += 2
            yield data[offset:offset + length].decode('utf-8', errors='replace')
            offset += length
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            offset += 2
        elif tag == 15:
            offset += 3
        else:
            raise ValueError(f'Unknown constant pool tag {tag}')
        index += 1


def owner(specs, name):
    matches = [spec['id'] for spec in specs if any(
        name.startswith(prefix) if prefix.endswith('.') else name == prefix or name.startswith(prefix + '$')
        for prefix in spec['prefixes'])]
    if len(matches) > 1:
        raise ValueError(f'Overlapping feature ownership: {name}: {matches}')
    return matches[0] if matches else 'core'


def verify(catalog, roots):
    # Explicit UTF-8 so the Chinese names in the catalog also load under non-UTF-8 locales (e.g. GBK Windows).
    specs = json.loads(catalog.read_text(encoding='utf-8'))
    classes = {}
    for root in roots:
        for path in root.rglob('*.class'):
            # Join path parts so the class name is correct with both '/' and '\\' separators.
            name = '.'.join(path.relative_to(root).with_suffix('').parts)
            if name.startswith('com.coc.suncode.jar.'):
                classes[name] = path
    if not classes:
        raise ValueError('No compiled plugin classes found')
    failures = set()
    for name, path in classes.items():
        source = owner(specs, name)
        for constant in utf8_constants(path.read_bytes()):
            for ref in re.findall(r'com/coc/suncode/jar/[\w$/]+', constant):
                target = ref.replace('/', '.')
                if target not in classes:
                    continue
                destination = owner(specs, target)
                if destination not in ('core', source):
                    failures.add(f'{source}: {name} -> {destination}: {target}')
    if failures:
        raise ValueError('Cross-feature dependencies must use FeatureRuntime:\n' + '\n'.join(sorted(failures)))
    print(f'Feature boundaries verified: {len(specs)} features, {len(classes)} classes')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--catalog', type=Path, required=True)
    parser.add_argument('roots', nargs='+', type=Path)
    args = parser.parse_args()
    verify(args.catalog, args.roots)
