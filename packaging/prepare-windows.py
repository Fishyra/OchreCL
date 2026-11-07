#!/usr/bin/env python3
"""Fetch locked official Windows runtime/JavaFX inputs; never execute downloads.

Use --with-packager only when a Windows JDK is not already available.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import urllib.request
import zipfile


def main():
    """Validate every download against the recorded SHA-256 before extraction."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--with-packager', action='store_true')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--proxy')
    args = parser.parse_args()
    base = Path(__file__).resolve().parents[1]
    cache = base / 'windows-cache'
    cache.mkdir(exist_ok=True)
    lock = json.loads((base / 'packaging/windows-dependencies.json').read_text())
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({'https': args.proxy}) if args.proxy else urllib.request.ProxyHandler())

    def fetch(item, directory):
        """Download atomically and reject any cache or transport corruption."""
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / item['name']
        if not path.exists():
            if args.offline:
                raise FileNotFoundError(path)
            pending = path.with_suffix('.part')
            request = urllib.request.Request(item['url'], headers={'User-Agent': 'OchreCL-build/0.1'})
            with opener.open(request, timeout=60) as response, pending.open('wb') as output:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    output.write(chunk)
            if hashlib.sha256(pending.read_bytes()).hexdigest() != item['sha256']:
                raise ValueError('Download checksum mismatch: ' + item['name'])
            os.replace(pending, path)
        if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise ValueError('Cached checksum mismatch: ' + item['name'])
        return path

    for key, folder in [('runtime', 'jre')] + ([('packager', 'jdk')] if args.with_packager else []):
        archive = fetch(lock[key], cache)
        root = cache / folder
        expected = root / lock[key]['name'].removesuffix('.zip')
        if not expected.exists():
            with zipfile.ZipFile(archive) as package:
                for member in package.infolist():
                    path = Path(member.filename)
                    if path.is_absolute() or '..' in path.parts:
                        raise ValueError('Unsafe archive member')
                package.extractall(root)
        print(key + ': ' + str(expected))
    for item in lock['javafx'] + lock['licenses']:
        fetch(item, cache / 'javafx')
    for item in lock.get('fonts', []):
        fetch(item, cache / 'fonts')
    print('All locked Windows inputs verified.')


if __name__ == '__main__':
    main()
