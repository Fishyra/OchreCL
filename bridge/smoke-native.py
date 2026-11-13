#!/usr/bin/env python3
"""One isolated offline 26.2 vanilla probe: no accounts, worlds, mod loaders or existing options are used."""
from pathlib import Path
import subprocess,json,os,tempfile,base64,hashlib,time,socket,struct,uuid
root=Path(__file__).resolve().parents[1]
home=Path.home()/'Library/Application Support/minecraft'; instance=home/'versions/macOS Metal'; meta=json.loads((instance/'macOS Metal.json').read_text())
java=Path.home()/'Library/Application Support/hmcl/java/macos-arm64/mojang-java-runtime-epsilon/jre.bundle/Contents/Home/bin/java'
work=Path(tempfile.mkdtemp(prefix='ochre-bridge-native-'));cfg=work/'launch.properties';token=os.urandom(32).hex();cfg.write_text('enabled=true\ntoken='+token+'\n');cfg.chmod(0o600)
agent=root/'HMCL/src/main/resources/assets/bridge/OchreBridge.jar';assert agent.is_file()
libs=[]
for library in meta['libraries']:
 artifact=library.get('downloads',{}).get('artifact',{}).get('path')
 if artifact: p=home/'libraries'/artifact
 else:
  g,a,v=library['name'].split(':')[:3];p=home/'libraries'/g.replace('.','/')/a/v/(a+'-'+v+'.jar')
 if p.is_file():libs.append(str(p))
cp=os.pathsep.join([str(instance/'macOS Metal.jar'),*libs]);arg=base64.urlsafe_b64encode(str(cfg).encode()).decode().rstrip('=')
command=[str(java),'-XstartOnFirstThread','--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow','-Xmx768m','-javaagent:'+str(agent)+'='+arg,'-Djava.library.path='+str(instance/'natives-macos-arm64'),'-cp',cp,'net.minecraft.client.main.Main','--username','BridgeSmoke','--version','26.2','--gameDir',str(work),'--assetsDir',str(home/'assets'),'--assetIndex',meta['assetIndex']['id'],'--uuid','00000000000000000000000000000002','--accessToken','0','--versionType','release','--width','640','--height','400','--demo']
def exact(s,n):
 out=b''
 while len(out)<n:
  data=s.recv(n-len(out))
  if not data:raise EOFError()
  out+=data
 return out
def request(fields):
 endpoint=dict(line.split('=',1) for line in (work/'connection.properties').read_text().splitlines() if '='in line and not line.startswith('#'))
 fields={'token':token,'protocol':'1',**fields}
 payload=struct.pack('>ii',0x4f434852,len(fields))
 for k,v in fields.items():
  for value in [k,v]: data=str(value).encode();payload+=struct.pack('>i',len(data))+data
 with socket.create_connection(('127.0.0.1',int(endpoint['port'])),timeout=1) as s:
  s.settimeout(3);s.sendall(payload);magic,count=struct.unpack('>ii',exact(s,8));assert magic==0x4f434852;result={}
  for _ in range(count):
   k=exact(s,struct.unpack('>i',exact(s,4))[0]).decode();v=exact(s,struct.unpack('>i',exact(s,4))[0]).decode();result[k]=v
  return result
log=open(work/'game.log','w');process=subprocess.Popen(command,cwd=work,stdout=log,stderr=subprocess.STDOUT)
try:
 deadline=time.monotonic()+85;last=''
 while time.monotonic()<deadline:
  if process.poll() is not None:raise RuntimeError('Game exited: '+str(process.returncode))
  try:
   hello=request({'command':'hello'});last=str(hello)
   if hello.get('ok')=='true':break
  except Exception as error:last=str(error)
  time.sleep(1)
 else:raise RuntimeError('Bridge did not become ready: '+last)
 assert hello['gameVersion'].startswith('26.2'),hello
 invalid=request({'command':'options.set','token':'wrong','option.gamma':'1'});assert invalid['ok']=='false' and invalid['error']=='unauthorized',invalid
 before=request({'command':'options.get'});assert before['ok']=='true',before
 changed=request({'command':'options.set','option.soundCategory_master':'0.33','option.gamma':'0.7','option.fov':'0.25','option.renderDistance':'8'});assert changed.get('applied')=='true',changed
 after=request({'command':'options.get'});assert abs(float(after['soundCategory_master'])-.33)<.001,after;assert abs(float(after['gamma'])-.7)<.001,after;assert float(after['fov'])==.25 and after['renderDistance']=='8',after
 state=request({'command':'state.read'});assert state['ok']=='true' and 'language'in state,state
 unknown=request({'command':'script.execute'});assert unknown['ok']=='false',unknown
 report={'game':'26.2','loader':'none / vanilla main','hello':hello,'before':before,'after':after,'state':state,'unauthorizedRejected':True,'unknownCommandRejected':True,'gameDirectory':str(work)}
 (root/'bridge/build/native-smoke.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print('PASS: loader-free 26.2 handshake, real settings read/write, auth and unsupported-command rejection');print('Isolated logs:',work)
finally:
 process.terminate()
 try:process.wait(timeout=10)
 except subprocess.TimeoutExpired:process.kill();process.wait()
 log.close()
