#!/usr/bin/env python3
"""Build the optional agent with private ASM packages, isolated from mod-loader dependencies."""
from pathlib import Path
import hashlib
import os
import shutil
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ORIGINAL = b'org/objectweb/asm'
PRIVATE = b'io/ochre/bridge/internal/asm'


def relocate_class(data):
    """Rewrite only constant-pool UTF8 entries; bytecode offsets and all other entries stay intact."""
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Not a JVM class file')
    count = struct.unpack_from('>H', data, 8)[0]
    output = bytearray(data[:10])
    offset, index = 10, 1
    widths = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4,
              11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[offset]
        output.append(tag)
        offset += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, offset)[0]
            offset += 2
            value = data[offset:offset + length]
            if len(value) != length:
                raise ValueError('Truncated constant pool')
            value = value.replace(ORIGINAL, PRIVATE).replace(ORIGINAL.replace(b'/', b'.'), PRIVATE.replace(b'/', b'.'))
            output.extend(struct.pack('>H', len(value)))
            output.extend(value)
            offset += length
        else:
            width = widths.get(tag)
            if width is None:
                raise ValueError('Unsupported constant-pool tag: ' + str(tag))
            output.extend(data[offset:offset + width])
            offset += width
            if tag in (5, 6):
                index += 1
        index += 1
    output.extend(data[offset:])
    return bytes(output)


def validate(image):
    """Reject accidental loader-facing dependencies, agents or discovery metadata in the final image."""
    with zipfile.ZipFile(image) as jar:
        assert jar.testzip() is None
        assert PRIVATE.decode() + '/ClassReader.class' in jar.namelist()
        assert PRIVATE.decode() + '/tree/ClassNode.class' in jar.namelist()
        import io
        with zipfile.ZipFile(io.BytesIO(jar.read('assets/overlay-hooks.jar'))) as helper:
            assert helper.namelist() == ['io/ochre/bridge/bootstrap/v1/OverlayHooks.class']
            assert ORIGINAL not in helper.read(helper.namelist()[0])
        for name in jar.namelist():
            if name.endswith('.class'):
                assert name.startswith('io/ochre/bridge/'), name
                payload = jar.read(name)
                assert ORIGINAL not in payload and b'org.objectweb.asm' not in payload, name
            assert not name.startswith('META-INF/services/') and name != 'module-info.class', name


def main():
    """Compile clean inputs and replace the resource only after validating the isolated agent."""
    source = ROOT / 'bridge/src'
    sources = sorted(source.rglob('*.java'))
    hooks = sorted((ROOT / 'bridge/hooks').rglob('*.java'))
    asm_root = ROOT / 'bridge/dependencies'
    dependencies = [asm_root / name / '9.10.1' / (name + '-9.10.1.jar') for name in ('asm', 'asm-tree')]
    for library in dependencies:
        if not library.is_file():
            raise SystemExit('Missing official ASM dependency: ' + str(library))
    resource = ROOT / 'HMCL/src/main/resources/assets/bridge/OchreBridge.jar'
    resource.parent.mkdir(parents=True, exist_ok=True)
    build = ROOT / 'bridge/build'
    build.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    for path in [Path(__file__), ROOT / 'bridge/ASM-LICENSE.txt', *sources, *hooks, *dependencies]:
        digest.update(str(path.relative_to(ROOT) if path.is_relative_to(ROOT) else path.name).encode())
        digest.update(path.read_bytes())
    fingerprint = digest.hexdigest()
    stamp = build / 'agent-inputs.sha256'
    if resource.exists() and stamp.exists() and stamp.read_text() == fingerprint:
        validate(resource)
        return
    with tempfile.TemporaryDirectory(prefix='agent-', dir=build) as temporary:
        classes = Path(temporary) / 'classes'
        classes.mkdir()
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/javac') if os.environ.get('JAVA_HOME') else (shutil.which('javac') or 'javac')
        subprocess.run([java, '--release', '17', '-encoding', 'UTF-8', '-cp', os.pathsep.join(map(str, dependencies)), '-d', str(classes), *map(str, sources)], check=True)
        hook_classes = Path(temporary) / 'hooks'
        hook_classes.mkdir()
        subprocess.run([java, '--release', '17', '-encoding', 'UTF-8', '-d', str(hook_classes), *map(str, hooks)], check=True)
        helper = Path(temporary) / 'overlay-hooks.jar'
        with zipfile.ZipFile(helper, 'w', zipfile.ZIP_DEFLATED) as jar:
            for path in sorted(hook_classes.rglob('*.class')):
                jar.write(path, path.relative_to(hook_classes).as_posix())
        image = Path(temporary) / 'OchreBridge.jar'
        with zipfile.ZipFile(image, 'w', zipfile.ZIP_DEFLATED) as output:
            output.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nPremain-Class: io.ochre.bridge.BridgeAgent\r\nImplementation-Version: 0.3.0-alpha\r\n\r\n')
            for path in sorted(classes.rglob('*.class')):
                output.writestr(path.relative_to(classes).as_posix(), relocate_class(path.read_bytes()))
            for library in dependencies:
                with zipfile.ZipFile(library) as dependency:
                    for name in dependency.namelist():
                        if name.startswith(ORIGINAL.decode() + '/') and name.endswith('.class'):
                            output.writestr(name.replace(ORIGINAL.decode(), PRIVATE.decode(), 1), relocate_class(dependency.read(name)))
            output.write(helper, 'assets/overlay-hooks.jar')
            output.writestr('licenses/ASM-LICENSE.txt', (ROOT / 'bridge/ASM-LICENSE.txt').read_text())
        validate(image)
        pending = resource.with_suffix('.jar.new')
        shutil.copy2(image, pending)
        pending.replace(resource)
    stamp.write_text(fingerprint)
    print('Built isolated OchreBridge agent:', hashlib.sha256(resource.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
