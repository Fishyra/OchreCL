"""Bounded source import and JAR build-record verification; never executes supplied source."""
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

LIMIT = 256 * 1024 * 1024
EXTENSIONS = {'.java', '.css', '.json', '.png', '.svg', '.properties'}
ROOT_FILES = {'AGENTS.md', 'CHANGELOG.md', 'LICENSE', 'README.md', 'build.gradle.kts', 'settings.gradle.kts', 'gradlew', 'gradlew.bat', 'gradlew.ps1', '.gitignore'}
ROOT_DIRS = {'HMCL', 'HMCLCore', 'HMCLBoot', 'minecraft', 'gradle', 'buildSrc', 'lib', 'bridge', 'packaging', 'branding', 'docs', 'examples', 'config'}


def command(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True, timeout=180).strip()


def safe_name(name):
    """Refuse platform escapes, ambiguous separators and control characters before extraction or staging."""
    path = PurePosixPath(name)
    if not name or path.is_absolute() or '\\' in name or ':' in name or any(c in name for c in '\r\n\0') or '..' in path.parts:
        raise RuntimeError('源码包含不安全的文件路径')
    return path


def allowed(name):
    path = safe_name(name)
    if name in ROOT_FILES:
        return True
    if path.parts[0] not in ROOT_DIRS:
        return False
    if any(part.startswith('.') or part in {'build', 'out', 'node_modules', '__pycache__', 'test-data', 'release-work'} for part in path.parts):
        return False
    if path.suffix.lower() in {'.pem', '.key', '.p12', '.pfx', '.log'} or path.name.lower() in {'accounts.json', 'settings.json', 'launch.properties', 'connection.properties'}:
        return False
    if path.parts[0] in {'HMCL', 'HMCLCore', 'HMCLBoot'}:
        if path.suffix.lower() in {'.jar', '.class'}: return False
        return len(path.parts) >= 3 and path.parts[1] == 'src' or len(path.parts) == 2 and path.name == 'build.gradle.kts'
    return True


def code_input(name):
    """Matches the Gradle source-record task exactly, independently of archive wrapper folder names."""
    if name in {'build.gradle.kts', 'settings.gradle.kts', 'gradlew', 'gradlew.bat', 'gradlew.ps1', 'HMCL/build.gradle.kts', 'HMCLCore/build.gradle.kts', 'HMCLBoot/build.gradle.kts', 'buildSrc/build.gradle.kts', 'gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties', 'config/project.properties'}:
        return True
    if name.startswith('buildSrc/src/') and PurePosixPath(name).suffix in {'.java', '.groovy', '.kt', '.properties'}:
        return True
    if name.startswith('minecraft/libraries/') and name.endswith('/build.gradle.kts'):
        return True
    if name.endswith('/assets/ochre-source.json'):
        return False
    if re.match(r'^(HMCL|HMCLCore|HMCLBoot)/src/main/', name):
        return PurePosixPath(name).suffix in EXTENSIONS
    return name.endswith('.java') and (name.startswith(('bridge/src/', 'bridge/hooks/')) or bool(re.match(r'^minecraft/libraries/.*/src/main/', name)))


def read_source(value):
    """Read only public project areas, with bounded bytes, no symlinks and no arbitrary extraction paths."""
    source = Path(value).resolve()
    marker = 'HMCL/src/main/java/org/jackhuang/hmcl/Metadata.java'
    files = {}
    size = 0
    if source.is_dir():
        if not (source / marker).is_file():
            raise RuntimeError('请选择包含 HMCL、HMCLCore、bridge 的完整源码根目录')
        for path in source.rglob('*'):
            name = path.relative_to(source).as_posix()
            if not allowed(name):
                continue
            if path.is_symlink():
                raise RuntimeError('源码不能包含符号链接：' + name)
            if not path.is_file():
                continue
            size += path.stat().st_size
            if size > LIMIT or len(files) >= 20000:
                raise RuntimeError('源码包过大，不能包含运行环境或构建缓存')
            files[name] = path.read_bytes()
    else:
        if source.suffix.lower() != '.zip':
            raise RuntimeError('源码请选择文件夹或 ZIP 文件')
        with zipfile.ZipFile(source) as archive:
            entries = archive.infolist()
            if len(entries) > 30000 or sum(e.file_size for e in entries) > LIMIT:
                raise RuntimeError('源码 ZIP 过大')
            names = [e.filename for e in entries]
            prefixes = [name[:-len(marker)] for name in names if name.endswith(marker)]
            if len(prefixes) != 1:
                raise RuntimeError('源码 ZIP 须包含唯一的完整源码项目')
            prefix = prefixes[0]
            seen = set()
            for entry in entries:
                safe_name(entry.filename)
                if entry.filename in seen:
                    raise RuntimeError('源码 ZIP 包含重复路径')
                seen.add(entry.filename)
                if stat.S_ISLNK(entry.external_attr >> 16):
                    raise RuntimeError('源码 ZIP 不能包含符号链接')
                if entry.is_dir() or not entry.filename.startswith(prefix):
                    continue
                name = entry.filename[len(prefix):]
                if allowed(name):
                    files[name] = archive.read(entry)
    for required in ('settings.gradle.kts', 'build.gradle.kts', 'gradlew', marker):
        if required not in files:
            raise RuntimeError('源码不完整，缺少：' + required)
    return files


def verify(jar, files):
    """Require the exact source input set recorded when this JAR was built; legacy JARs fail closed."""
    try:
        with zipfile.ZipFile(jar) as archive:
            info = archive.getinfo('assets/ochre-source.json')
            if info.file_size > 4 * 1024 * 1024:
                raise RuntimeError('构建源码记录过大')
            record = json.loads(archive.read(info))
    except KeyError:
        raise RuntimeError('此 JAR 没有源码校验记录，请使用新版构建流程生成源码与 update.jar') from None
    expected = record.get('files', {})
    if record.get('schema') != 1 or not isinstance(expected, dict) or not 1 <= len(expected) <= 20000:
        raise RuntimeError('JAR 构建源码记录无效')
    if any(not allowed(name) or not code_input(name) or not re.fullmatch('[a-f0-9]{64}', digest) for name, digest in expected.items()):
        raise RuntimeError('JAR 源码记录包含无效路径或摘要')
    actual = {name: hashlib.sha256(data).hexdigest() for name, data in files.items() if code_input(name)}
    changed = sorted(name for name in set(actual) | set(expected) if actual.get(name) != expected.get(name))
    if changed:
        raise RuntimeError('源码与 update.jar 不匹配：' + '、'.join(changed[:5]) + (' …' if len(changed) > 5 else ''))
    return len(expected)


def prepare(value, jar, repository, version):
    """Verify before mutation, then prepare an auditable local commit without publishing anything."""
    files = read_source(value)
    count = verify(jar, files)
    repo = Path(repository).resolve()
    if command('git', 'status', '--porcelain', cwd=repo):
        raise RuntimeError('公开源码仓库存在未提交修改，已停止导入')
    tracked = command('git', 'ls-files', cwd=repo).splitlines()
    touched = []
    for name in tracked:
        if code_input(name) and name not in files:
            target = repo / safe_name(name)
            if target.is_file():
                target.unlink()
                touched.append(name)
    for name, data in files.items():
        target = repo / safe_name(name)
        if target.is_symlink() or any(p.is_symlink() for p in target.parents if p != repo and repo in p.parents):
            raise RuntimeError('公开源码目录包含符号链接，已停止导入')
        if target.is_file() and target.read_bytes() == data:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        if name == 'gradlew':
            target.chmod(0o755)
        touched.append(name)
    if touched:
        with tempfile.NamedTemporaryFile() as paths:
            paths.write(b''.join(name.encode('utf-8') + b'\0' for name in touched)); paths.flush()
            command('git', 'add', '--pathspec-from-file=' + paths.name, '--pathspec-file-nul', cwd=repo)
        if command('git', 'diff', '--cached', '--name-only', cwd=repo):
            command('git', 'commit', '-m', 'Prepare ' + version + ' source snapshot from verified inputs', cwd=repo)
    head = command('git', 'rev-parse', 'HEAD', cwd=repo)
    print('源码校验通过：' + str(count) + ' 个构建输入一致；本地源码提交已准备。', flush=True)
    return head
