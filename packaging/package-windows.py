#!/usr/bin/env python3
"""Build an isolated Windows x64 app-image and portable ZIP with a pinned bundled runtime.

On Windows use --jdk and --runtime. On macOS the optional --wine/--prefix runs
Windows jpackage inside an isolated Whisky prefix; no user bottle is modified.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import zipfile

BASE = Path(__file__).resolve().parents[1]
VERSION = '0.1.0-preview.1'
FX = {
    'javafx-base-21.0.8-win.jar': 'b1aecc348fcd904a9869d661eecda9fb0d4d300724a1344bfe074cbf2ba01b3b',
    'javafx-graphics-21.0.8-win.jar': 'f32a1bcbeae70cf416b8da7e7c3e6698f18ba09783d7ff77047edf119a044ccd',
    'javafx-controls-21.0.8-win.jar': 'dc1eef3dbdc721c8d42a4084d76c2f48b8b5ba35e5d76fdfce7bb210def621ac',
}

def digest(path):
    """Return the SHA-256 of a packaged input."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    """Validate inputs, create the native app-image and publish a clean portable archive."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True, type=Path)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--javafx', type=Path, default=BASE / 'windows-cache/javafx')
    parser.add_argument('--wine', type=Path)
    parser.add_argument('--prefix', type=Path)
    parser.add_argument('--javac', default='javac')
    args = parser.parse_args()
    jar = BASE / 'HMCL/build/libs/HMCL-3.17.SNAPSHOT.jar'
    for name, expected in FX.items():
        if digest(args.javafx / name) != expected:
            raise ValueError('JavaFX checksum mismatch: ' + name)
    if not (args.runtime / 'bin/java.exe').is_file():
        raise ValueError('A Windows runtime with bin/java.exe is required')
    for dll in ('glass.dll', 'prism_sw.dll'):
        with zipfile.ZipFile(args.javafx / 'javafx-graphics-21.0.8-win.jar') as archive:
            if dll not in archive.namelist():
                raise ValueError('Missing Windows JavaFX native library: ' + dll)
    work = BASE / 'windows-cache/package-work'
    if work.exists():
        shutil.rmtree(work)
    inputs = work / 'input'
    classes = work / 'classes'
    inputs.mkdir(parents=True)
    classes.mkdir()
    shutil.copy2(jar, inputs / 'OchreCL.jar')
    subprocess.run([args.javac, '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), str(BASE / 'packaging/PortableLauncher.java')], check=True)
    with zipfile.ZipFile(inputs / 'portable-bootstrap.jar', 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nMain-Class: io.ochrecl.bootstrap.PortableLauncher\r\nClass-Path: OchreCL.jar\r\n\r\n')
        for file in classes.rglob('*.class'):
            archive.write(file, file.relative_to(classes).as_posix())
    fxdir = inputs / 'javafx'
    fxdir.mkdir()
    for name in FX:
        shutil.copy2(args.javafx / name, fxdir / name)
    icon = work / 'ochre.ico'
    png = (BASE / 'branding/ochre.iconset/icon_256x256.png').read_bytes()
    icon.write_bytes(struct.pack('<HHH', 0, 1, 1) + struct.pack('<BBBBHHII', 0, 0, 0, 0, 1, 32, len(png), 22) + png)
    output = work / 'image'
    output.mkdir()
    env = dict(os.environ)
    if args.wine:
        if not args.prefix:
            parser.error('--wine requires an isolated --prefix')
        env.update(WINEPREFIX=str(args.prefix.resolve()), WINEDEBUG='-all', WINEDLLOVERRIDES='mscoree,mshtml=')
        def target(path):
            return 'Z:' + str(path.resolve()).replace('/', '\\')
        command = [str(args.wine), str(args.jdk.resolve() / 'bin/jpackage.exe')]
    else:
        def target(path):
            return str(path.resolve())
        command = [str(args.jdk.resolve() / 'bin/jpackage.exe')]
    command += ['--type', 'app-image', '--name', 'OchreCL', '--app-version', '0.1.0', '--vendor', 'OchreCL',
                '--description', 'ØchreCL Minecraft Launcher', '--input', target(inputs), '--main-jar', 'portable-bootstrap.jar',
                '--main-class', 'io.ochrecl.bootstrap.PortableLauncher', '--dest', target(output), '--icon', target(icon),
                '--runtime-image', target(args.runtime)]
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '').replace('\r\n', '\n')
    opens = next(line[len('Add-Opens: '):] for line in manifest.splitlines() if line.startswith('Add-Opens: ')).split()
    options = ['-Xmx768m', '--module-path=$APPDIR/javafx', '--add-modules=javafx.controls']
    options += ['--add-opens=' + name + '=ALL-UNNAMED' for name in opens if not name.startswith('jdk.attach/')]
    for option in options:
        command += ['--java-options', option]
    subprocess.run(command, check=True, env=env, timeout=180)
    app = output / 'OchreCL'
    if not (app / 'OchreCL.exe').is_file():
        raise RuntimeError('jpackage did not produce OchreCL.exe')
    licenses = app / 'licenses'
    licenses.mkdir()
    shutil.copy2(BASE / 'LICENSE', licenses / 'HMCL-LICENSE.txt')
    for name in ('LICENSE', 'ASSEMBLY_EXCEPTION', 'ADDITIONAL_LICENSE_INFO'):
        shutil.copy2(args.javafx / name, licenses / ('OpenJFX-' + name + '.txt'))
    (licenses / '来源说明.txt').write_text('ØchreCL 基于 HMCL 开发：https://github.com/HMCL-dev/HMCL\nJava：Azul Zulu OpenJDK；许可证位于 runtime/legal。\nJavaFX：OpenJFX 21.0.8，https://github.com/openjdk/jfx\nArclight 图标：https://github.com/IzzelAliz/Arclight/blob/Dipper/.github/arclightlogo.jpg\n完整修改源码随本次发布的 source.zip 提供。\n', encoding='utf-8')
    (app / '使用说明.txt').write_text('ØchreCL Windows x64 便携测试版 ' + VERSION + '\n\n请先完整解压，再双击 OchreCL.exe。不要只取出 EXE。\n已内置启动器 Java 与 JavaFX，无需另装 Java。\n游戏和服务器所需的其他 Java 版本仍由启动器按需管理。\n设置、账号、服务器与工具保存在旁边的 data 文件夹；迁移时复制整个目录。\n不要覆盖或分享自己的 data 文件夹，里面可能包含登录信息和服务器存档。\n请放在有写入权限的目录，不要放入 Program Files。\n系统：Windows 10/11 x64；ARM 与 32 位未验证。\n此版本未签名，仍需真实 Windows 端到端测试。\nWhisky 的英文测试容器中中文路径尚未通过，请暂用英文字母目录；真实 Windows 中文路径待测。\n已关闭上游 HMCL 自动更新。\n', encoding='utf-8')
    (app / 'build-info.json').write_text(json.dumps({'version': VERSION, 'platform': 'windows-x64', 'launcher_sha256': digest(jar), 'javafx_sha256': FX, 'runtime': 'Zulu JRE 17.0.21.0.3; JavaFX 21.0.8 separate modules', 'source': 'companion source.zip'}, indent=2), encoding='utf-8')
    destination = BASE / 'builds' / ('OchreCL-' + VERSION + '-windows-x64')
    if destination.exists():
        archive = BASE / 'windows-cache' / ('previous-image-' + str(__import__('time').time_ns()))
        shutil.move(str(destination), archive)
    shutil.copytree(app, destination)
    print(destination, flush=True)


if __name__ == '__main__':
    main()
