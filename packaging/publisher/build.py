#!/usr/bin/env python3
"""Build a local macOS ARM64 release assistant without bundling credentials."""
import json
import datetime
import tempfile
import plistlib
import shutil
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASE = HERE.parents[1]
destination = BASE / 'builds/ØchreCL 发布助手.app'
staging = Path(tempfile.mkdtemp(prefix='publisher-build-', dir=BASE/'builds'))
app = staging / 'ØchreCL 发布助手.app'
resources = app / 'Contents/Resources'
binary = app / 'Contents/MacOS/OchrePublisher'
resources.mkdir(parents=True, exist_ok=True)
binary.parent.mkdir(parents=True, exist_ok=True)
subprocess.run(['swiftc', '-target', 'arm64-apple-macosx12.0', '-module-cache-path', '/tmp/ochre-swift-cache', str(HERE / 'OchrePublisher.swift'), '-o', str(binary), '-framework', 'AppKit'], check=True)
shutil.copy2(HERE / 'publish_update.py', resources / 'publish_update.py')
shutil.copy2(HERE / 'full_packages.py', resources / 'full_packages.py')
shutil.copy2(HERE / 'source_snapshot.py', resources / 'source_snapshot.py')
shutil.copy2(BASE / 'branding/ochre.icns', resources / 'publisher.icns')
(resources / 'config.json').write_text(json.dumps({'base': str(BASE), 'source': str(BASE / 'github-publish/OchreCL'), 'website': str(BASE / 'website-publish'), 'gh': str(Path.home() / '.local/bin/gh')}, indent=2))
(app / 'Contents/Info.plist').write_bytes(plistlib.dumps({'CFBundleExecutable': 'OchrePublisher', 'CFBundleName': 'OchrePublisher', 'CFBundleDisplayName': 'ØchreCL 发布助手', 'CFBundleIdentifier': 'io.fishyra.ochrecl.publisher.local', 'CFBundleVersion': '5', 'CFBundleShortVersionString': '0.3.1', 'CFBundlePackageType': 'APPL', 'CFBundleIconFile': 'publisher.icns', 'NSHighResolutionCapable': True, 'LSMinimumSystemVersion': '12.0'}))
subprocess.run(['codesign', '--force', '--deep', '--sign', '-', str(app)], check=True)
subprocess.run(['codesign', '--verify', '--deep', '--strict', str(app)], check=True)
if destination.exists():
    backup = BASE / 'backups' / ('publisher-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '.app')
    backup.parent.mkdir(exist_ok=True)
    destination.rename(backup)
app.rename(destination)
staging.rmdir()
print(destination)
