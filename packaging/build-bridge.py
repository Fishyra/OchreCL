#!/usr/bin/env python3
"""Build the optional, dependency-free Java agent used by consenting 26.x instances."""
from pathlib import Path
import subprocess,zipfile,hashlib,os,shutil
root=Path(__file__).resolve().parents[1]
source=root/'bridge/src'; classes=root/'bridge/build/classes';classes.mkdir(parents=True,exist_ok=True)
java=Path(os.environ['JAVA_HOME'])/'bin/javac' if os.environ.get('JAVA_HOME') else Path(shutil.which('javac') or 'javac')
sources=sorted(source.rglob('*.java'))
resource=root/'HMCL/src/main/resources/assets/bridge/OchreBridge.jar';resource.parent.mkdir(parents=True,exist_ok=True)
if resource.exists() and resource.stat().st_mtime>max(p.stat().st_mtime for p in sources):raise SystemExit(0)
subprocess.run([str(java),'--release','17','-encoding','UTF-8','-d',str(classes),*[str(p) for p in sources]],check=True)
with zipfile.ZipFile(resource,'w',zipfile.ZIP_DEFLATED) as output:
 output.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\nPremain-Class: io.ochre.bridge.BridgeAgent\r\nImplementation-Version: 0.2.0-alpha\r\n\r\n')
 for p in sorted(classes.rglob('*.class')):output.write(p,p.relative_to(classes).as_posix())
print('Built optional OchreBridge agent:',hashlib.sha256(resource.read_bytes()).hexdigest())
