#!/usr/bin/env python3
"""Produce clean macOS ARM64 DMG and Windows x64 preview ZIP without running Wine."""
from pathlib import Path
import hashlib, json, os, plistlib, shutil, subprocess, zipfile

BASE = Path(__file__).resolve().parents[1]
VERSION = os.environ.get('OCHRE_RELEASE_VERSION', '0.3.0-alpha.1')
JDK = Path('/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home')
MAC_RUNTIME = Path(os.environ.get('OCHRE_MAC_RUNTIME', str(JDK)))
OUT = BASE / 'builds' / VERSION
WORK = BASE / 'release-work' / VERSION


def sha(path):
    """Compute an artifact digest."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bootstrap(source, destination, classpath=False):
    """Compile one standalone entry point and retain its source alongside build scripts."""
    classes = WORK / (source.stem + '-classes'); classes.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(JDK/'bin/javac'), '--release', '17', '-encoding', 'UTF-8', '-d', str(classes), str(source), str(BASE/'packaging/UpdateBootstrap.java')], check=True)
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n' + ('Class-Path: OchreCL.jar\r\n' if classpath else '') + '\r\n')
        for p in classes.rglob('*.class'): z.write(p, p.relative_to(classes).as_posix())


def main():
    """Package both targets from verified inputs, preserving previous releases and installed applications."""
    OUT.mkdir(parents=True, exist_ok=True); WORK.mkdir(parents=True, exist_ok=True)
    jar = BASE/'HMCL/build/libs/HMCL-3.17.SNAPSHOT.jar'
    inputs = WORK/'mac-input'; (inputs/'javafx').mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar, inputs/'OchreCL.jar')
    desktop_source = WORK/'DesktopLauncher.java'; desktop_source.write_text((BASE/'packaging/DesktopLauncher.java').read_text().replace('0.1.0-preview.2', VERSION))
    bootstrap(desktop_source, inputs/'desktop-bootstrap.jar', True)
    for module in ('base','graphics','controls'):
        p = next((BASE/'.gradle-home/caches').rglob(f'javafx-{module}-21.0.8-mac-aarch64.jar'))
        shutil.copy2(p, inputs/'javafx'/p.name)
    with zipfile.ZipFile(jar) as z:
        manifest = z.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '').replace('\r\n','\n')
    opens = next(x[11:] for x in manifest.splitlines() if x.startswith('Add-Opens: ')).split()
    macout = WORK/'mac-image'; macout.mkdir(exist_ok=True)
    app = macout/'OchreCL.app'
    if not app.exists():
        command = [str(JDK/'bin/jpackage'), '--type','app-image','--name','OchreCL','--app-version','1.3.1',
                   '--mac-package-identifier','io.fishyra.ochrecl','--vendor','Fishyra',
                   '--input',str(inputs),'--main-jar','desktop-bootstrap.jar','--main-class','io.ochrecl.bootstrap.DesktopLauncher',
                   '--dest',str(macout),'--icon',str(BASE/'branding/ochre.icns'),
                   '--runtime-image',str(MAC_RUNTIME)]
        for option in ['-Xmx768m','--module-path=$APPDIR/javafx','--add-modules=javafx.controls'] + ['--add-opens='+x+'=ALL-UNNAMED' for x in opens]:
            command += ['--java-options',option]
        subprocess.run(command,check=True)
    shutil.copy2(jar, app/'Contents/app/OchreCL.jar')
    shutil.copy2(inputs/'desktop-bootstrap.jar', app/'Contents/app/desktop-bootstrap.jar')
    info=app/'Contents/Info.plist';d=plistlib.loads(info.read_bytes());d['CFBundleDisplayName']='ØchreCL';info.write_bytes(plistlib.dumps(d))
    lic=app/'Contents/app/licenses';lic.mkdir(exist_ok=True)
    shutil.copy2(BASE/'LICENSE',lic/'HMCL-LICENSE.txt')
    for name in ('LICENSE','ASSEMBLY_EXCEPTION','ADDITIONAL_LICENSE_INFO'):
        shutil.copy2(BASE/'windows-cache/javafx'/name,lic/('OpenJFX-'+name+'.txt'))
    subprocess.run(['/usr/bin/codesign','--force','--deep','--sign','-',str(app)],check=True)
    cfg=(app/'Contents/app/OchreCL.cfg').read_text()
    assert '/Users/chenyoyo' not in cfg and 'test-data' not in cfg
    stage=WORK/'dmg-root';stage.mkdir(exist_ok=True)
    if (stage/'OchreCL.app').exists(): shutil.rmtree(stage/'OchreCL.app')
    shutil.copytree(app,stage/'OchreCL.app',symlinks=True)
    if not (stage/'Applications').is_symlink():(stage/'Applications').symlink_to('/Applications')
    (stage/'安装说明.txt').write_text('ØchreCL '+VERSION+' · macOS Apple Silicon 测试版\n\n将 OchreCL.app 拖到 Applications。已内置 Java 和 JavaFX。\n本应用未进行 Apple 开发者签名与公证，首次打开可能被系统拦截。\n配置位于 ~/Library/Application Support/OchreCL，不包含开发者账户和存档。\n此版本不会自动迁移旧开发版配置；替换应用前请关闭旧程序并备份数据。\n',encoding='utf-8')
    dmg=OUT/f'OchreCL-{VERSION}-macos-arm64.dmg'
    subprocess.run(['/usr/bin/hdiutil','create','-volname','OchreCL','-srcfolder',str(stage),'-format','UDZO','-ov',str(dmg)],check=True)
    win=WORK/'windows'/f'OchreCL-{VERSION}-windows-x64'
    if not win.exists():
        shutil.copytree(BASE/'builds/OchreCL-0.1.0-preview.1-windows-x64',win,ignore=shutil.ignore_patterns('data','.minecraft'))
    shutil.copy2(jar,win/'app/OchreCL.jar')
    source=WORK/'PortableLauncher.java';source.write_text((BASE/'packaging/PortableLauncher.java').read_text().replace('0.1.0-preview.1',VERSION))
    bootstrap(source,win/'app/portable-bootstrap.jar',True)
    fonts=BASE/'windows-cache/fonts';lock=json.loads((BASE/'packaging/windows-dependencies.json').read_text())
    for entry in lock['fonts']:assert sha(fonts/entry['name'])==entry['sha256']
    (win/'runtime/lib/fonts').mkdir(exist_ok=True)
    shutil.copy2(fonts/'NotoSansCJKsc-Regular.otf',win/'runtime/lib/fonts/NotoSansCJKsc-Regular.otf')
    shutil.copy2(fonts/'OFL.txt',win/'licenses/Noto-OFL.txt')
    (win/'使用说明.txt').write_text('ØchreCL '+VERSION+' Windows x64 便携测试包\n\n完整解压后运行 OchreCL.exe；不要只取出 EXE。\n内置 Java、JavaFX 与 Noto 中文字体回退，不包含账号及存档。\n本次仅做离线重打包，字体修复尚未在实际 Windows 验证。\n请勿在导致整机卡死的 Whisky 环境反复启动。建议真实 Windows 10/11 测试。\n暂用英文目录。设置和服务器保存在 data，分享前不要附带自己的 data。\n未签名，暂不建议作为稳定版公开发布。\n',encoding='utf-8')
    meta=json.loads((win/'build-info.json').read_text());meta.update(version=VERSION,launcher_sha256=sha(jar),validation='Offline packaging only; font fallback pending Windows validation',fonts=lock['fonts']);(win/'build-info.json').write_text(json.dumps(meta,indent=2))
    archive=OUT/f'OchreCL-{VERSION}-windows-x64.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(win.rglob('*')):
            if p.is_file():z.write(p,Path(win.name)/p.relative_to(win))
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        assert not any('/data/' in x or '/.minecraft/' in x for x in z.namelist())
    (OUT/'SHA256SUMS.txt').write_text('\n'.join(sha(p)+'  '+p.name for p in (dmg,archive))+'\n')
    print(OUT)

if __name__=='__main__':main()
