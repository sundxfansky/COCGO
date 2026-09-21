#!/usr/bin/env python3
"""Build a signed legacy-compatible package and optionally publish it to the cloud API."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import struct
import subprocess
import tempfile
import time
import urllib.request
import zipfile


def chacha20(data, key, nonce):
    state = list(struct.unpack('<4I', b'expand 32-byte k')) + list(struct.unpack('<8I', key)) + [0] + list(struct.unpack('<3I', nonce))
    output = bytearray()
    def rotate(value, bits):
        return ((value << bits) | (value >> (32 - bits))) & 0xffffffff
    for offset in range(0, len(data), 64):
        block = state.copy()
        def quarter(a, b, c, d):
            block[a] = (block[a] + block[b]) & 0xffffffff; block[d] = rotate(block[d] ^ block[a], 16)
            block[c] = (block[c] + block[d]) & 0xffffffff; block[b] = rotate(block[b] ^ block[c], 12)
            block[a] = (block[a] + block[b]) & 0xffffffff; block[d] = rotate(block[d] ^ block[a], 8)
            block[c] = (block[c] + block[d]) & 0xffffffff; block[b] = rotate(block[b] ^ block[c], 7)
        for _ in range(10):
            for indices in [(0,4,8,12),(1,5,9,13),(2,6,10,14),(3,7,11,15),(0,5,10,15),(1,6,11,12),(2,7,8,13),(3,4,9,14)]:
                quarter(*indices)
        stream = struct.pack('<16I', *((a+b) & 0xffffffff for a,b in zip(block,state)))
        output.extend(a ^ b for a,b in zip(data[offset:offset+64],stream))
        state[12] = (state[12]+1) & 0xffffffff
    return bytes(output)


def legacy_key(root):
    source = (root / 'app/src/main/cpp/security/keys.cpp').read_text()
    def part(name):
        match = re.search(name + r'\[32\]\s*=\s*\{([^}]+)}', source)
        values = bytes(int(v,16) for v in re.findall(r'0x[0-9a-fA-F]+',match.group(1)))
        if len(values) != 32: raise ValueError('Invalid legacy key component')
        return values
    return bytes(a ^ b for a,b in zip(part('KEY_MASK'),part('OBFUSCATED_KEY')))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--key', required=True, type=Path)
    parser.add_argument('--url', help='Cloud API base URL, such as https://host/api/v1')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    data = args.input.read_bytes()
    if len(data) > 64*1024*1024: raise ValueError('Plugin exceeds size limit')
    with zipfile.ZipFile(args.input) as archive:
        if 'classes.dex' not in archive.namelist(): raise ValueError('Input does not contain classes.dex')
    version = int(time.time()) - 1770000000
    name = f'encrypted_{version}.jar'
    output = args.output or args.input.parent / 'releases'
    output.mkdir(parents=True, exist_ok=True)
    artifact = output / name
    if artifact.exists(): raise FileExistsError('A release already exists for this second; retry later')
    nonce = secrets.token_bytes(12)
    encrypted = nonce + chacha20(data, legacy_key(root), nonce)
    # Bind the versioned filename as well as ciphertext to the signature.
    with tempfile.TemporaryDirectory() as folder:
        payload = Path(folder) / 'signed-payload'
        signature_file = Path(folder) / 'signature'
        payload.write_bytes(name.encode() + b'\0' + encrypted)
        subprocess.run(['openssl','dgst','-sha256','-sign',str(args.key),'-out',str(signature_file),str(payload)],check=True)
        signature = signature_file.read_bytes()
    artifact.write_bytes(encrypted)
    artifact.with_suffix('.jar.sig').write_bytes(signature)
    print(f'Signed artifact: {artifact}')
    if args.url:
        if not args.url.startswith('https://') and not args.url.startswith(('http://localhost:','http://127.0.0.1:')):
            raise ValueError('Publication requires HTTPS outside localhost')
        token = os.environ.get('SUN_UPLOAD_TOKEN')
        if not token: raise ValueError('Set SUN_UPLOAD_TOKEN to an administrator session token')
        body = json.dumps({'fileName':name,'payload':base64.b64encode(encrypted).decode(),'signature':base64.b64encode(signature).decode()}).encode()
        request = urllib.request.Request(args.url.rstrip('/')+'/plugins',data=body,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},method='POST')
        with urllib.request.urlopen(request, timeout=120) as response:
            print(f'Publication completed: HTTP {response.status}')

if __name__ == '__main__': main()
