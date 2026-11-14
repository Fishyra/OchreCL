#!/usr/bin/env python3
"""Build a Linux x64 portable tarball from verified official runtimes without executing foreign binaries."""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, tarfile, zipfile
BASE=Path(__file__).resolve().parents[1]
VERSION=os.environ.get('OCHRE_RELEASE_VERSION','0.2.0-beta.2')
JDK=Path('/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home')
OUT=BASE/'builds'/VERSION
WORK=BASE/'release-work'/VERSION/'linux'

def sha(path):
    """Hash an artifact to match its official dependency lock."""
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    """Assemble the launcher, JRE, JavaFX and licenses into a clean extracted directory."""
    lock=json.loads((BASE/'packaging/linux-dependencies.json').read_text());cache=BASE/'linux-cache'
    for entry in [lock['runtime']]+lock['javafx']:assert sha(cache/entry['name'])==entry['sha256']
    OUT.mkdir(parents=True,exist_ok=True);WORK.mkdir(parents=True,exist_ok=True)
    root=WORK/f'OchreCL-{VERSION}-linux-x64'
    if root.exists():shutil.rmtree(root)
    (root/'app/javafx').mkdir(parents=True)
    extracted=WORK/'runtime-input';extracted.mkdir(exist_ok=True)
    archive=cache/lock['runtime']['name']
    with tarfile.open(archive) as tar:
        for member in tar.getmembers():
            target=(extracted/member.name).resolve()
            if not target.is_relative_to(extracted.resolve()):raise RuntimeError('Unsafe runtime archive path')
        tar.extractall(extracted)
    runtime=next(p for p in extracted.iterdir() if p.is_dir() and (p/'bin/java').exists())
    shutil.copytree(runtime,root/'runtime',symlinks=True)
    jar=BASE/'HMCL/build/libs/HMCL-3.17.SNAPSHOT.jar';shutil.copy2(jar,root/'app/OchreCL.jar')
    for entry in lock['javafx']:shutil.copy2(cache/entry['name'],root/'app/javafx'/entry['name'])
    classes=WORK/'bootstrap-classes';classes.mkdir(exist_ok=True)
    source=WORK/'LinuxLauncher.java';source.write_text((BASE/'packaging/LinuxLauncher.java').read_text().replace('0.2.0-beta.2',VERSION))
    subprocess.run([str(JDK/'bin/javac'),'--release','17','-encoding','UTF-8','-d',str(classes),str(source),str(BASE/'packaging/UpdateBootstrap.java')],check=True)
    with zipfile.ZipFile(root/'app/linux-bootstrap.jar','w',zipfile.ZIP_DEFLATED) as output:
        output.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\nClass-Path: OchreCL.jar\r\n\r\n')
        for file in classes.rglob('*.class'):output.write(file,file.relative_to(classes).as_posix())
    with zipfile.ZipFile(jar) as z:manifest=z.read('META-INF/MANIFEST.MF').decode().replace('\r\n ','').replace('\r\n','\n')
    opens=next(x[11:] for x in manifest.splitlines() if x.startswith('Add-Opens: ')).split()
    options=' '.join('--add-opens='+item+'=ALL-UNNAMED' for item in opens)
    script='''#!/bin/sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$HERE/runtime/bin/java" -Xmx768m --module-path "$HERE/app/javafx" --add-modules javafx.controls OPTIONS -cp "$HERE/app/linux-bootstrap.jar:$HERE/app/OchreCL.jar" io.ochrecl.bootstrap.LinuxLauncher "$@"
'''.replace('OPTIONS',options)
    (root/'OchreCL').write_text(script);(root/'OchreCL').chmod(0o755)
    (root/'ochrecl.png').write_bytes((BASE/'branding/ochre.iconset/icon_256x256.png').read_bytes())
    fonts=root/'runtime/lib/fonts';fonts.mkdir(exist_ok=True);shutil.copy2(BASE/'windows-cache/fonts/NotoSansCJKsc-Regular.otf',fonts/'NotoSansCJKsc-Regular.otf')
    licenses=root/'licenses';licenses.mkdir();shutil.copy2(BASE/'LICENSE',licenses/'HMCL-LICENSE.txt');shutil.copy2(BASE/'windows-cache/fonts/OFL.txt',licenses/'Noto-OFL.txt')
    for name in ('LICENSE','ASSEMBLY_EXCEPTION','ADDITIONAL_LICENSE_INFO'):shutil.copy2(BASE/'windows-cache/javafx'/name,licenses/('OpenJFX-'+name+'.txt'))
    (root/'使用说明.txt').write_text('ØchreCL '+VERSION+' · Linux x64 测试版\n\n完整解压后运行 ./OchreCL，无需另装 Java。不要将启动文件与 runtime、app 分开。\n适用于 glibc x86_64 桌面 Linux，需要 GTK 3 与图形会话；不是 Alpine/musl 或 ARM 包。\n用户数据保存在 ${XDG_DATA_HOME:-~/.local/share}/ochrecl，升级时保留该目录。\n可运行 ./OchreCL --package-check 检查打包依赖。此包在 macOS 上交叉装配，尚未在 Linux 原生桌面验证。\n')
    (root/'build-info.json').write_text(json.dumps({'version':VERSION,'platform':'linux-x64','launcher_sha256':sha(jar),'dependencies':lock,'validation':'Cross-assembled on macOS; Linux native GUI test pending'},indent=2))
    target=OUT/f'{root.name}.tar.gz'
    with tarfile.open(target,'w:gz') as tar:tar.add(root,arcname=root.name)
    with tarfile.open(target) as tar:
        entries={member.name:member for member in tar.getmembers()}
        assert entries[root.name+'/OchreCL'].mode & 0o111
        assert not any('/data/' in name or '/.minecraft/' in name for name in entries)
        assert hashlib.sha256(tar.extractfile(root.name+'/app/OchreCL.jar').read()).hexdigest()==sha(jar)
    print(target)
if __name__=='__main__':main()
