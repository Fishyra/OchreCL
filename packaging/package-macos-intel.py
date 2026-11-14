#!/usr/bin/env python3
"""Cross-assemble an Intel Mac app using verified official x86_64 launcher, JRE and OpenJFX inputs."""
from pathlib import Path
import hashlib,io,json,os,plistlib,shutil,subprocess,tarfile,zipfile
BASE=Path(__file__).resolve().parents[1]
VERSION=os.environ.get('OCHRE_RELEASE_VERSION','0.3.0-alpha.2')
CACHE=BASE/'macos-intel-cache'
WORK=BASE/'release-work'/VERSION/'macos-intel'
OUT=BASE/'builds'/VERSION

def sha(path):
    """Hash pinned inputs and the common application JAR."""
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    """Retain the validated ARM app layout, replacing every architecture-dependent executable component."""
    entries=json.loads((BASE/'packaging/macos-intel-dependencies.json').read_text())
    for entry in entries:
        if sha(CACHE/entry['name'])!=entry['sha256']:raise RuntimeError('Input digest mismatch: '+entry['name'])
    WORK.mkdir(parents=True,exist_ok=True);OUT.mkdir(parents=True,exist_ok=True)
    app=WORK/'OchreCL.app'
    if app.exists():raise RuntimeError('Use a new staging directory rather than overwriting an existing Intel app')
    shutil.copytree(BASE/'release-work'/VERSION/'mac-image/OchreCL.app',app,symlinks=True)
    runtime=app/'Contents/runtime';shutil.rmtree(runtime)
    jre=next(entry for entry in entries if '-ca-jre' in entry['name'])
    extraction=WORK/'runtime-input';extraction.mkdir()
    with tarfile.open(CACHE/jre['name']) as archive:
        for member in archive.getmembers():
            if not (extraction/member.name).resolve().is_relative_to(extraction.resolve()):raise RuntimeError('Unsafe runtime path')
        archive.extractall(extraction)
    bundle=next(path for path in extraction.iterdir() if (path/'Contents/Home/bin/java').exists())
    shutil.copytree(bundle,runtime,symlinks=True)
    jdk=next(entry for entry in entries if '-ca-jdk' in entry['name'])
    with tarfile.open(CACHE/jdk['name']) as archive:
        module=next(member for member in archive.getmembers() if member.name.endswith('/jmods/jdk.jpackage.jmod'))
        with zipfile.ZipFile(io.BytesIO(archive.extractfile(module).read())) as resources:
            executable=resources.read('classes/jdk/jpackage/internal/resources/jpackageapplauncher')
    launcher=app/'Contents/MacOS/OchreCL';launcher.write_bytes(executable);launcher.chmod(0o755)
    javafx=app/'Contents/app/javafx';shutil.rmtree(javafx);javafx.mkdir()
    for entry in entries:
        if entry['name'].startswith('javafx-'):shutil.copy2(CACHE/entry['name'],javafx/entry['name'])
    info=app/'Contents/Info.plist';metadata=plistlib.loads(info.read_bytes());metadata['LSArchitecturePriority']=['x86_64'];metadata['LSMinimumSystemVersion']='11.0';info.write_bytes(plistlib.dumps(metadata))
    for binary in [launcher,runtime/'Contents/Home/bin/java',runtime/'Contents/Home/lib/server/libjvm.dylib']:
        architecture=subprocess.check_output(['/usr/bin/lipo','-archs',str(binary)],text=True).strip()
        if architecture!='x86_64':raise RuntimeError('Wrong Intel architecture: '+str(binary)+' '+architecture)
    native=WORK/'native-check';native.mkdir()
    with zipfile.ZipFile(javafx/'javafx-graphics-21.0.8-mac.jar') as archive:
        for name in archive.namelist():
            if name.endswith(('.dylib','.jnilib')):
                path=native/Path(name).name;path.write_bytes(archive.read(name))
                if subprocess.check_output(['/usr/bin/lipo','-archs',str(path)],text=True).strip()!='x86_64':raise RuntimeError('Wrong JavaFX native architecture: '+name)
    jar=BASE/'HMCL/build/libs/HMCL-3.17.SNAPSHOT.jar'
    (app/'Contents/app/build-info.json').write_text(json.dumps({'version':VERSION,'platform':'macos-x64','launcher_sha256':sha(jar),'inputs':entries,'validation':'Cross-assembled on Apple Silicon; Intel native execution pending'},indent=2))
    subprocess.run(['/usr/bin/codesign','--force','--deep','--sign','-',str(app)],check=True)
    subprocess.run(['/usr/bin/codesign','--verify','--deep','--strict',str(app)],check=True)
    stage=WORK/'dmg-root';stage.mkdir();shutil.copytree(app,stage/'OchreCL.app',symlinks=True);(stage/'Applications').symlink_to('/Applications')
    (stage/'安装说明.txt').write_text('ØchreCL '+VERSION+' · macOS Intel x86_64 测试版\n\n适用于 Intel Mac，macOS 11 或更高版本。不是 32 位 x86 版本。\n将 OchreCL.app 拖入 Applications；内置 x86_64 Java 与 JavaFX。\n未进行 Apple 开发者签名与公证，尚未在 Intel Mac 原生实测。\n配置位于 ~/Library/Application Support/OchreCL；替换前关闭启动器并保留原数据。\n')
    target=OUT/f'OchreCL-{VERSION}-macos-x64.dmg'
    subprocess.run(['/usr/bin/hdiutil','create','-volname','OchreCL Intel','-srcfolder',str(stage),'-format','UDZO','-ov',str(target)],check=True)
    subprocess.run(['/usr/bin/hdiutil','verify',str(target)],check=True)
    print(target)
if __name__=='__main__':main()
