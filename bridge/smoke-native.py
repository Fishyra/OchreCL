#!/usr/bin/env python3
"""One isolated offline 26.2 vanilla probe: no accounts, worlds, mod loaders or existing options are used."""
from pathlib import Path
import subprocess,json,os,tempfile,base64,hashlib,time,socket,struct,uuid,argparse
parser=argparse.ArgumentParser();parser.add_argument('--loader',choices=('vanilla','fabric'),default='vanilla');args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
home=Path.home()/'Library/Application Support/minecraft'; instance=home/'versions/macOS Metal'; meta=json.loads((instance/'macOS Metal.json').read_text())
java=Path.home()/'Library/Application Support/hmcl/java/macos-arm64/mojang-java-runtime-epsilon/jre.bundle/Contents/Home/bin/java'
work=Path(tempfile.mkdtemp(prefix='ochre-bridge-native-'));cfg=work/'launch.properties';token=os.urandom(32).hex();cfg.write_text('enabled=true\ntoken='+token+'\n');cfg.chmod(0o600)
agent=root/'hmcl-source/HMCL/src/main/resources/assets/bridge/OchreBridge.jar';assert agent.is_file()
libs=[]
for library in meta['libraries']:
 artifact=library.get('downloads',{}).get('artifact',{}).get('path')
 if artifact: p=home/'libraries'/artifact
 else:
  g,a,v=library['name'].split(':')[:3];p=home/'libraries'/g.replace('.','/')/a/v/(a+'-'+v+'.jar')
 if p.is_file():libs.append(str(p))
cp=os.pathsep.join([str(instance/'macOS Metal.jar'),*libs]);arg=base64.urlsafe_b64encode(str(cfg).encode()).decode().rstrip('=')
command=[str(java),'-XstartOnFirstThread','--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow','-Xmx768m','-javaagent:'+str(agent)+'='+arg,'-Djava.library.path='+str(instance/'natives-macos-arm64'),'-cp',cp,('net.fabricmc.loader.impl.launch.knot.KnotClient' if args.loader=='fabric' else 'net.minecraft.client.main.Main'),'--username','BridgeSmoke','--version','26.2','--gameDir',str(work),'--assetsDir',str(home/'assets'),'--assetIndex',meta['assetIndex']['id'],'--uuid','00000000000000000000000000000002','--accessToken','0','--versionType','release','--width','640','--height','400','--demo']
if os.environ.get('OCHRE_TEST_FOUNDATIONS'):
 command.insert(command.index('-cp'),'-javaagent:'+str(root/'bridge/build/foundation-probe.jar'))
 command[command.index('-Xmx768m')]='-Xmx1536m'
if os.environ.get('OCHRE_TEST_CAPTURE'):
 (root/'bridge/build/overlay-preview.png').unlink(missing_ok=True)
 command.insert(command.index('-cp'),'-javaagent:'+str(root/'bridge/build/capture-agent.jar')+'='+str(root/'bridge/build/overlay-preview.png'))
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
def read_request(fields):
 # Startup shader compilation can temporarily occupy the render thread. Retry only read-only probes.
 assert fields['command'] in ('overlay.sync','options.get','hello','state.read')
 deadline=time.monotonic()+25
 while True:
  result=request(fields)
  if result.get('error')!='game.thread.timeout' or time.monotonic()>=deadline:return result
  time.sleep(.2)
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
 assert hello['bridgeVersion']=='0.3.0-alpha',hello
 assert 'script.move' in hello['capabilities'],hello
 overlay=request({'command':'overlay.sync','theme':'#EEDAB5','status':'idle','total':'0','page':'0','count':'0'});assert overlay['ok']=='true' and int(overlay['frames'])>0,overlay
 warm_deadline=time.monotonic()+35
 while int(overlay.get('frames','0'))<25 and time.monotonic()<warm_deadline:
  time.sleep(.2);overlay=read_request({'command':'overlay.sync','theme':'#EEDAB5','ink':'#39362F','card':'#FAF6EC','accent':'#80633D','status':'idle','total':'0','page':'0','count':'0'})
 assert int(overlay.get('frames','0'))>=25,overlay
 speech=request({'command':'overlay.say','text':'Hello! 气泡测试 👋\nThis message wraps beside the floating ball.','duration':'8'});assert speech.get('bubble')=='shown',speech
 bubble_deadline=time.monotonic()+7
 while True:
  time.sleep(.3)
  bubble=read_request({'command':'overlay.sync','theme':'#EEDAB5','status':'running','statement':'say("Hello!");','total':'0','page':'0','count':'0'})
  if int(bubble.get('bubbleFrames','0'))>0:break
  assert time.monotonic()<bubble_deadline,bubble
 assert bubble.get('bubbleVisible')=='true',bubble
 time.sleep(8)
 expired=read_request({'command':'overlay.sync','status':'idle','total':'0','page':'0','count':'0'});assert expired.get('bubbleVisible')=='false',expired
 bad_speech=request({'command':'overlay.say','text':'invalid duration','duration':'NaN'});assert bad_speech.get('ok')=='false',bad_speech
 invalid=request({'command':'options.set','token':'wrong','option.gamma':'1'});assert invalid['ok']=='false' and invalid['error']=='unauthorized',invalid
 before=request({'command':'options.get'});assert before['ok']=='true',before
 changed=request({'command':'options.set','option.soundCategory_master':'0.33','option.gamma':'0.7','option.fov':'0.25','option.renderDistance':'8'});assert changed.get('applied')=='true',changed
 after=request({'command':'options.get'});assert abs(float(after['soundCategory_master'])-.33)<.001,after;assert abs(float(after['gamma'])-.7)<.001,after;assert float(after['fov'])==.25 and after['renderDistance']=='8',after
 quick=request({'command':'options.set','option.sensitivity':'.4','option.bobView':'false','option.showSubtitles':'true','option.autoJump':'false','option.toggleSprint':'true','option.fov':'.5'});assert quick.get('ok')=='true',quick
 quick_state=request({'command':'options.get'});assert abs(float(quick_state['sensitivity'])-.4)<.001 and quick_state['bobView']=='false' and quick_state['showSubtitles']=='true' and quick_state['autoJump']=='false' and quick_state['toggleSprint']=='true' and float(quick_state['fov'])==.5,quick_state
 state=request({'command':'state.read'});assert state['ok']=='true' and 'language'in state,state
 raw_input=request({'command':'script.input.read'});assert raw_input.get('ok')=='true' and raw_input.get('focused') in ('true','false') and int(raw_input['keys'])>=0 and int(raw_input['mouse_buttons'])>=0,raw_input
 if os.environ.get('OCHRE_TEST_FOUNDATIONS'):
  (work/'foundation-start').write_text('ready');deadline=time.monotonic()+150
  while not (work/'foundation-ready').exists():
   if (work/'foundation-error').exists():raise AssertionError((work/'foundation-error').read_text())
   if process.poll() is not None or time.monotonic()>deadline:raise AssertionError('Temporary world startup failed: '+str(work))
   time.sleep(.25)
  snap=request({'command':'script.snapshot'});assert snap.get('in_world')=='true',snap
  owner=str(uuid.uuid4())
  # Physical key semantics use the normal game mapping, then release with the same owner.
  key=request({'command':'script.input.key','owner':owner,'key':'W','action':'press','modifiers':'0'});assert key.get('leased')=='true',key
  time.sleep(.25)
  actual_input=request({'command':'script.snapshot'});assert actual_input.get('input.forward')=='true' and actual_input.get('state.jumping') in ('true','false'),actual_input
  busy=request({'command':'script.input.key','owner':str(uuid.uuid4()),'key':'W','action':'press','modifiers':'0'});assert busy.get('ok')=='false',busy
  keyup=request({'command':'script.input.key','owner':owner,'key':'W','action':'release','modifiers':'0'});assert keyup.get('status')=='released',keyup
  observation=request({'command':'script.observe','cursor':'0'});assert int(observation.get('count','0'))>0,observation
  no_field=request({'command':'script.input.text','text':'ignored','open_chat':'false'});assert no_field.get('status')=='not_inserted',no_field
  filled=request({'command':'script.input.text','text':'Hello','open_chat':'true'});assert filled.get('status')=='filled_not_sent',filled
  typed=request({'command':'script.input.text','text':' 世界!','open_chat':'false'});assert typed.get('status')=='inserted_not_sent',typed
  unhandled=request({'command':'script.input.key','owner':owner,'key':'301','action':'press','modifiers':'0'});assert unhandled.get('ok')=='false' and 'input.key.not.handled' in unhandled.get('error',''),unhandled
  escape=request({'command':'script.input.key','owner':owner,'key':'ESCAPE','action':'press','modifiers':'0'});assert escape.get('handled')=='true',escape
  # UI closure must match the exact confirmed screen and world.
  request({'command':'script.input.text','text':'unsent draft','open_chat':'true'})
  context=request({'command':'script.context'});assert context.get('menu')=='true',context
  stale=request({'command':'script.ui.close','world':context['world'],'screen_token':'stale'});assert stale.get('ok')=='false',stale
  closed=request({'command':'script.ui.close','world':context['world'],'screen_token':context['screen_token']});assert closed.get('status')=='closed',closed
  assert request({'command':'script.context'}).get('menu')=='false'
  # An abandoned hold expires and cannot block another script indefinitely.
  request({'command':'script.input.key','owner':owner,'key':'SHIFT','action':'press','modifiers':'0'});time.sleep(1.1)
  next_owner=str(uuid.uuid4());reacquired=request({'command':'script.input.key','owner':next_owner,'key':'SHIFT','action':'press','modifiers':'0'});assert reacquired.get('leased')=='true',reacquired
  request({'command':'script.input.release','owner':next_owner})
  # Commands prepare only this disposable demo world; ordinary block APIs perform the actual actions.
  import math
  snap=request({'command':'script.snapshot'});x,y,z=[math.floor(float(snap[a])) for a in ('x','y','z')];coords={'x':str(x),'y':str(y-1),'z':str(z),'world':snap['world']}
  read=request({'command':'script.block.read',**coords});assert read.get('loaded')=='true',read
  # An invalid target must never be treated as a successful interaction.
  far=request({'command':'script.block.mine','owner':owner,'x':str(x+1000),'y':str(y),'z':str(z),'face':'up'});assert far.get('ok')=='false',far
  blocked=request({'command':'script.chat','text':'/op BridgeSmoke'});assert blocked.get('ok')=='false',blocked
  def aim_at(x,y,z):
   state=request({'command':'state.read'})
   result=request({'command':'script.look','x':str(x),'y':str(y),'z':str(z),'startYaw':state['player.YRot'],'startPitch':state['player.XRot'],'fraction':'1'})
   assert result.get('ok')=='true',result
  aim_at(2.5,90.5,.5)
  target=request({'command':'script.block.target'});assert target.get('id')=='minecraft:stone' and target.get('x')=='2' and target.get('y')=='90',target
  mine=request({'command':'script.block.mine','owner':owner,'x':'2','y':'90','z':'0','world':snap['world'],'expected':'minecraft:stone','face':'west'});assert mine.get('ok')=='true',mine
  time.sleep(.35)
  removed=request({'command':'script.block.read','x':'2','y':'90','z':'0'});assert removed.get('isAir')=='true',removed
  stop=request({'command':'script.block.stop','owner':owner});assert stop.get('ok')=='true',stop
  aim_at(2.5,89.999,.5)
  place=request({'command':'script.block.place','owner':owner,'x':'2','y':'89','z':'0','world':snap['world'],'expected':'minecraft:stone','face':'up'});assert place.get('ok')=='true',place
  time.sleep(.35)
  restored=request({'command':'script.block.read','x':'2','y':'90','z':'0'});assert restored.get('id')=='minecraft:stone',restored
  # Expanded queries and slot operations execute only inside this disposable world.
  vital=request({'command':'script.inspect','kind':'vitals'});assert vital.get('ok')=='true' and 'food' in vital and 'in_lava' in vital,vital
  screen=request({'command':'script.inspect','kind':'screen'});assert screen.get('open')=='false' and screen.get('in_world')=='true',screen
  diagnostics=request({'command':'script.inspect','kind':'status'});assert all(name in diagnostics['hooks'] for name in ('attack','damage','consume','break','place','use','scroll')),diagnostics
  target_any=request({'command':'script.target'});assert target_any.get('kind') in ('block','entity','miss'),target_any
  current=request({'command':'script.snapshot'});geometry=request({'command':'script.collision',**{a:current[a] for a in ('x','y','z')},'path':'true'});assert geometry.get('clear')=='true' and geometry.get('path_clear')=='true',geometry
  tags=request({'command':'script.item.tags'});assert tags.get('item')=='minecraft:stone' and int(tags['count'])>=0,tags
  slots=request({'command':'script.container.read','offset':'24','limit':'24'});assert slots.get('ok')=='true' and slots.get('total')=='46' and 'slot.0.accepts_held' in slots,slots
  stale_menu=request({'command':'script.container.act','action':'move','from':'36','to':'37','count':'1','menu_token':'stale'});assert stale_menu.get('ok')=='false' and 'container.changed' in stale_menu.get('error',''),stale_menu
  split=request({'command':'script.container.act','action':'move','from':'36','to':'37','count':'1','menu_token':slots['menu_token']});assert split.get('status')=='submitted_to_server',split
  time.sleep(.25)
  moved=request({'command':'script.container.read','offset':'36','limit':'2'});assert moved.get('slot.1.item')=='minecraft:stone' and moved.get('slot.1.count')=='1',moved
  returned=request({'command':'script.container.act','action':'move','from':'37','to':'36','count':'0','menu_token':moved['menu_token']});assert returned.get('status')=='submitted_to_server',returned
  def fixture(commands):
   (work/'fixture-result').unlink(missing_ok=True);(work/'fixture-request').write_text('\n'.join(commands));deadline=time.monotonic()+15
   while not (work/'fixture-result').exists():
    if (work/'foundation-error').exists():raise AssertionError((work/'foundation-error').read_text())
    assert time.monotonic()<deadline,'Fixture request timed out'
    time.sleep(.1)
  fixture(['$fov_probe'])
  # Server damage packets and the local completion callback are independently observable.
  fixture(['gamemode survival BridgeSmoke','damage BridgeSmoke 1 minecraft:generic','summon minecraft:cow 1 90 0 {NoAI:1b,Silent:1b}','damage @e[type=minecraft:cow,limit=1,sort=nearest] 1 minecraft:player_attack by BridgeSmoke'])
  fixture(['item replace entity BridgeSmoke weapon.mainhand with minecraft:apple 3','$consume'])
  # Observe exact passive mining/placement callbacks, not only request success.
  events=[];cursor=0
  for _ in range(30):
   page=request({'command':'script.observe','cursor':str(cursor)});count=int(page['count']);events += [{k[len('event.'+str(i)+'.'):]:v for k,v in page.items() if k.startswith('event.'+str(i)+'.')} for i in range(count)];cursor=int(page['cursor'])
   if count==0:break
  kinds={e['type'] for e in events};assert {'block_break','block_interact','block_place_result','block_place','hurt','attack_hit','item_use_complete','food_consumed'}<=kinds,(kinds,events)
  (root/'bridge/build/expanded-smoke.json').write_text(json.dumps({'vitals':vital,'screen':screen,'diagnostics':diagnostics,'target':target_any,'geometry':geometry,'tags':tags,'slots':slots,'move':moved,'events':events},indent=2))
  report_data={'snapshot':snap,'observations':observation,'input':key,'text':typed,'block':read,'farRejected':far,'chatCommandRejected':blocked,'target':target,'mine':mine,'afterMine':removed,'place':place,'afterPlace':restored}
  (root/'bridge/build/foundation-smoke.json').write_text(json.dumps(report_data,indent=2))
  print('FOUNDATIONS_NATIVE_OK: world snapshot, movement events, held/released key, Unicode text events, loaded block reads, actual mining/placement readback, reach and command rejection')
  if os.environ.get('OCHRE_TEST_NAVIGATION'):
   def navigate(goal, bridge, straight=False):
    world=request({'command':'script.context'})['world']
    plan=request({'command':'script.nav.plan','world':world,'goal':goal,'bridge':str(bridge).lower(),'straight':str(straight).lower()})
    assert plan.get('ok')=='true',plan
    start=plan['start'];route=plan['route'].split(';')
    try:
     for segment in [start+',false',*route]:
      target,build=segment.rsplit(',',1);placed='false';deadline=time.monotonic()+4
      while time.monotonic()<deadline:
       result=request({'command':'script.nav.step','owner':owner,'world':world,'from':start,'to':target,'bridge':build,'placed':placed})
       assert result.get('ok')=='true',(start,target,build,result)
       if result.get('status')=='arrived':break
       if result.get('status')=='placed':
        assert placed=='false'
        placed='true';time.sleep(.25)
       else:time.sleep(.05)
      else:raise AssertionError(('Navigation stalled',start,target,request({'command':'script.snapshot'})))
      start=target
    finally:
     request({'command':'script.release','owner':owner})
    return route
   fixture(['kill @e[type=minecraft:cow]','fill -6 89 -5 12 94 5 minecraft:air',
            'fill 0 89 0 5 89 2 minecraft:stone','fill 1 90 0 1 91 0 minecraft:stone',
            'fill 3 90 0 3 90 2 minecraft:stone','tp BridgeSmoke 0.5 90 0.5'])
   steps=navigate('5,90,0',False)
   assert any(step.split(',')[1]=='91' for step in steps) and any(step.split(',')[2]!='0' for step in steps),steps
   print('NAVIGATION_WALK_OK:',steps,flush=True)
   fixture(['fill -6 89 -5 12 94 5 minecraft:air','fill 0 89 0 1 89 0 minecraft:stone',
            'fill 4 89 0 5 89 0 minecraft:stone','tp BridgeSmoke 0.5 90 0.5'])
   world=request({'command':'script.context'})['world']
   blocked=request({'command':'script.nav.plan','world':world,'goal':'5,90,0','bridge':'false'})
   assert blocked.get('error')=='walk_to.no_walkable_route_in_loaded_area',blocked
   missing=request({'command':'script.nav.plan','world':world,'goal':'5,90,0','bridge':'true'})
   assert missing.get('error')=='walk_to.select_enough_solid_bridge_blocks',missing
   fixture(['item replace entity BridgeSmoke weapon.mainhand with minecraft:stone 8'])
   steps=navigate('5,90,0',True)
   assert sum(step.endswith(',true') for step in steps)==2,steps
   for x in (2,3):
    assert request({'command':'script.block.read','x':x,'y':89,'z':0}).get('id')=='minecraft:stone'
   print('NAVIGATION_BRIDGE_OK:',steps,flush=True)
   fixture(['fill -6 89 -5 12 94 5 minecraft:air','setblock 0 89 0 minecraft:stone',
            'tp BridgeSmoke 0.5 90 0.5','item replace entity BridgeSmoke weapon.mainhand with minecraft:cobblestone 5'])
   for x in range(1,6):
    assert navigate(f'{x},90,0',True,straight=True)==[f'{x},90,0,true']
    assert request({'command':'script.block.read','x':x,'y':89,'z':0}).get('id')=='minecraft:cobblestone'
   world=request({'command':'script.context'})['world']
   empty=request({'command':'script.nav.plan','world':world,'goal':'6,90,0','bridge':'true','straight':'true'})
   assert empty.get('error')=='walk_to.select_enough_solid_bridge_blocks',empty
   print('NAVIGATION_STRAIGHT_BRIDGE_OK: built five blocks with no far landing and stopped without materials',flush=True)
 unknown=request({'command':'script.execute'});assert unknown['ok']=='false',unknown
 inventory=request({'command':'script.inventory'});assert inventory['ok']=='true' if os.environ.get('OCHRE_TEST_FOUNDATIONS') else inventory['ok']=='false' and inventory['error']=='game.not.in.world',inventory
 released=request({'command':'script.release','owner':str(uuid.uuid4())});assert released['ok']=='true',released
 if os.environ.get('OCHRE_TEST_CAPTURE'):
  (work/'capture-ready').write_text('ready')
  capture=root/'bridge/build/overlay-preview.png';deadline=time.monotonic()+20
  while not capture.exists() and time.monotonic()<deadline:time.sleep(.2)
  assert capture.exists(),'Overlay capture missing'
  deadline=time.monotonic()+20
  while not list((work/'screenshots').glob('*.png')) and time.monotonic()<deadline:time.sleep(.2)
  screenshots=list((work/'screenshots').glob('*.png'));assert screenshots,'GPU screenshot missing'
  subprocess.run(['/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java',str(root/'bridge/test/io/ochre/bridge/GpuFrameTest.java'),str(screenshots[-1])],check=True)
  __import__('shutil').copy2(screenshots[-1],root/'bridge/build/overlay-gpu.png')

 report={'game':'26.2','loader':('Fabric 0.19.5' if args.loader=='fabric' else 'none / vanilla main'),'hello':hello,'before':before,'after':after,'state':state,'unauthorizedRejected':True,'unknownCommandRejected':True,'overlay':overlay,'quickSettings':quick_state,'bubble':bubble,'bubbleExpired':expired,'gameDirectory':str(work)}
 (root/('bridge/build/native-smoke-'+args.loader+'.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2));print('PASS: '+args.loader+' 26.2 handshake, rendered overlay, real settings read/write, auth and unsupported-command rejection');print('Isolated logs:',work)
finally:
 process.terminate()
 try:process.wait(timeout=10)
 except subprocess.TimeoutExpired:process.kill();process.wait()
 log.close()
