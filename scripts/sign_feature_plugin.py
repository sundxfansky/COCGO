#!/usr/bin/env python3
"""Sign one feature JAR; serve the JAR and its .sig file together over HTTPS."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import zipfile


def sign(artifact, key):
    with zipfile.ZipFile(artifact) as archive:
        names = archive.namelist()
        if 'classes.dex' not in names or names.count('feature.properties') != 1:
            raise ValueError('Expected an independently built feature JAR')
    if artifact.stat().st_size > 64 * 1024 * 1024:
        raise ValueError('Feature exceeds the size limit')
    signature = Path(str(artifact) + '.sig')
    # Sign the entire archive, including its ID, core API and monotonic version.
    with tempfile.TemporaryDirectory(dir=artifact.parent) as folder:
        temporary = Path(folder) / 'signature'
        subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(key), '-out', str(temporary), str(artifact)], check=True)
        temporary.replace(signature)
    print(f'Signed: {artifact}\nSignature: {signature}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--key', required=True, type=Path)
    args = parser.parse_args()
    sign(args.input, args.key)
