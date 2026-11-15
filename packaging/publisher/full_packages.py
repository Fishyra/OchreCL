"""Build four clean distributions from a supplied JAR and existing verified runtimes."""
import hashlib
import json
import os
import plistlib
import shutil
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def execute(*args, env=None):
    subprocess.run(args, env=env, check=True, timeout=1800)


def jar_check(data, expected, label):
    if hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError('安装包中的启动器 JAR 不一致：' + label)


def bootstrap_check(data, version, entry):
    import io
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if version.encode() not in archive.read(entry):
            raise RuntimeError('原生包引导器版本号不一致')


def verify(folder, version, expected):
    """Inspect archive payloads and mount local DMGs read-only; never run foreign code."""
    entry = 'io/ochrecl/bootstrap/'
    windows = folder / f'OchreCL-{version}-windows-x64.zip'
    with zipfile.ZipFile(windows) as archive:
        if archive.testzip(): raise RuntimeError('Windows ZIP CRC 错误')
        prefix = f'OchreCL-{version}-windows-x64/'
        if any('/data/' in name or '/.minecraft/' in name for name in archive.namelist()):
            raise RuntimeError('安装包包含用户数据')
        jar_check(archive.read(prefix + 'app/OchreCL.jar'), expected, 'Windows')
        bootstrap_check(archive.read(prefix + 'app/portable-bootstrap.jar'), version, entry + 'PortableLauncher.class')
    with tarfile.open(folder / f'OchreCL-{version}-linux-x64.tar.gz') as archive:
        prefix = f'OchreCL-{version}-linux-x64/'
        if any('/data/' in name or '/.minecraft/' in name for name in archive.getnames()):
            raise RuntimeError('安装包包含用户数据')
        jar_check(archive.extractfile(prefix + 'app/OchreCL.jar').read(), expected, 'Linux')
        bootstrap_check(archive.extractfile(prefix + 'app/linux-bootstrap.jar').read(), version, entry + 'LinuxLauncher.class')
        if not archive.getmember(prefix + 'OchreCL').mode & 0o111: raise RuntimeError('Linux 启动文件缺少执行权限')
    for platform, architecture in [('macos-arm64', 'arm64'), ('macos-x64', 'x86_64')]:
        dmg = folder / f'OchreCL-{version}-{platform}.dmg'
        execute('hdiutil', 'verify', str(dmg))
        with tempfile.TemporaryDirectory(prefix='ochre-dmg-check-') as mount:
            mounted = False
            try:
                execute('hdiutil', 'attach', '-readonly', '-nobrowse', '-mountpoint', mount, str(dmg))
                mounted = True
                app = Path(mount) / 'OchreCL.app'
                jar_check((app / 'Contents/app/OchreCL.jar').read_bytes(), expected, platform)
                bootstrap_check((app / 'Contents/app/desktop-bootstrap.jar').read_bytes(), version, entry + 'DesktopLauncher.class')
                actual = subprocess.check_output(['lipo', '-archs', str(app / 'Contents/MacOS/OchreCL')], text=True).strip()
                if actual != architecture: raise RuntimeError('macOS 启动程序架构不符')
                execute('codesign', '--verify', '--deep', '--strict', str(app))
            finally:
                if mounted: execute('hdiutil', 'detach', mount)


def build(config, jar, version, source, source_head):
    """Reuse only completely verified results; failed attempts keep separate work directories."""
    base = Path(config['base'])
    scripts = base / 'scripts'
    dependency_paths = [scripts / name for name in (
        'package-release.py', 'package-linux.py', 'package-macos-intel.py', 'macos_document.py',
        'DesktopLauncher.java', 'PortableLauncher.java', 'LinuxLauncher.java', 'UpdateBootstrap.java',
        'windows-dependencies.json', 'linux-dependencies.json', 'macos-intel-dependencies.json')]
    runtime = base / 'release-work/mac-runtime/zulu17.70.113-ca-jre17.0.21.0.3-macosx_aarch64/Contents/Home'
    if not (runtime / 'bin/java').is_file(): raise RuntimeError('缺少已验证的 macOS ARM Java 模板')
    expected = sha(jar)
    identity = {'jar': expected, 'version': version, 'source': source_head,
                'document_icon': sha(base / 'branding/ochre-script.icns'), 'scripts': {p.name: sha(p) for p in dependency_paths}, 'verifier': sha(__file__)}
    key = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()[:16]
    folder = base / 'builds/publisher-packages' / (version + '-' + key)
    folder.mkdir(parents=True, exist_ok=True)
    manifest = folder / 'verified.json'
    names = [f'OchreCL-{version}-{target}' for target in (
        'macos-arm64.dmg', 'macos-x64.dmg', 'windows-x64.zip', 'linux-x64.tar.gz')]
    names += ['OchreCL-update.jar', 'OchreCL-source.zip']
    if manifest.exists():
        cached = json.loads(manifest.read_text())
        if cached['identity'] != identity or cached['assets'] != {n: sha(folder / n) for n in names}:
            raise RuntimeError('本地已验证的打包缓存发生变化，请保留后移走该目录再重试')
        print('四种完整安装包已验证，复用本地打包结果。', flush=True)
        return [folder / n for n in names] + [folder / 'SHA256SUMS.txt']
    with tempfile.TemporaryDirectory(prefix='ochre-input-') as temp:
        frozen = Path(temp) / 'OchreCL-update.jar'; shutil.copy2(jar, frozen)
        if sha(frozen) != expected: raise RuntimeError('拖入的文件在读取时发生变化')
        work = Path(tempfile.mkdtemp(prefix='publisher-', dir=base / 'release-work'))
        env = os.environ.copy()
        env.update(OCHRE_RELEASE_VERSION=version, OCHRE_INPUT_JAR=str(frozen),
                   OCHRE_OUTPUT_ROOT=str(folder), OCHRE_WORK_ROOT=str(work), OCHRE_MAC_RUNTIME=str(runtime))
        for i, name in enumerate(('package-release.py', 'package-linux.py', 'package-macos-intel.py'), 1):
            print(f'打包阶段 {i}/3：{name}', flush=True)
            execute('/usr/bin/python3', str(scripts / name), env=env)
        shutil.copy2(frozen, folder / 'OchreCL-update.jar')
    execute('git', '-C', str(source), 'archive', '--format=zip', '--output=' + str(folder / 'OchreCL-source.zip'), '--prefix=OchreCL-' + version + '/', source_head)
    print('验证四平台 JAR、版本引导器、归档完整性及 macOS 架构…', flush=True)
    verify(folder, version, expected)
    hashes = {name: sha(folder / name) for name in names}
    (folder / 'SHA256SUMS.txt').write_text(''.join(value + '  ' + name + '\n' for name, value in hashes.items()))
    manifest.write_text(json.dumps({'identity': identity, 'assets': hashes}, indent=2))
    print('四平台本地打包与校验完成：' + str(folder), flush=True)
    return [folder / n for n in names] + [folder / 'SHA256SUMS.txt']
