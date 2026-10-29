import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const code = fs.readFileSync(path.join(root, 'src/common/relayController.js'), 'utf8');
const context = vm.createContext({});
vm.runInContext(code.replace('export function createRelayController', 'function createRelayController'), context);

function setup(config = {}) {
  let time = 1000;
  let next = 0;
  const timers = new Map();
  const sent = [];
  let view = {};
  let sends = [];
  let statusSeq = 0;
  const connect = {
    send(opts) { sent.push(JSON.parse(JSON.stringify(opts.data))); sends.push(opts); if (!config.noSendCallback) opts.success(); },
    getReadyState(opts) { if (config.deferReady) connect.readyCallback = opts; else opts.success({status: 1}); }
  };
  const controller = context.createRelayController({
    loadConnection: () => { if (config.noModule) throw new Error('no module'); return connect; },
    now: () => time,
    setTimer: (fn,ms) => { timers.set(++next, {fn,at: time + ms}); return next; },
    clearTimer: id => timers.delete(id),
    onState: state => { view = state; }
  });
  function receive(packet,wrap = true) { connect.onmessage(wrap ? {data: JSON.stringify(packet)} : packet); }
  function status(fields = {}) {
    const packet = Object.assign({v:1,type:'status',seq:++statusSeq,camera:'ready',recording:false,canControl:true,canStart:true,canStop:false,busy:false,battery:72,recordSeconds:0,recordPhase:'idle',gpsState:'off',gpsEnabled:false,message:'',session:'session-a',ticket:'ticket-a',revision:1},fields);
    if (!Object.prototype.hasOwnProperty.call(fields,'canStart')) packet.canStart = packet.recording === false;
    if (!Object.prototype.hasOwnProperty.call(fields,'canStop')) packet.canStop = packet.recording === true;
    receive(packet);
  }
  function advance(ms) {
    const end = time + ms;
    while (true) {
      let match = [...timers].filter(([,item])=>item.at <= end).sort((a,b)=>a[1].at-b[1].at)[0];
      if (!match) break;
      time = match[1].at;
      timers.delete(match[0]); match[1].fn();
    }
    time = end;
  }
  return {controller,connect,sent,sends,timers,receive,status,advance,view:()=>view};
}

let checked = 0;
{
  const t = setup(); t.controller.show();
  assert.equal(t.view().startEnabled,false);
  assert.equal(t.sent[0].type,'hello');
  assert.equal(t.controller.command('start'),false);
  assert.equal(t.sent.filter(x=>x.type==='command').length,0);
  t.status(); assert.equal(t.view().startEnabled,true); assert.equal(t.view().stopEnabled,false);
  t.controller.destroy(); checked++;
}
{
  const t=setup(); t.controller.show(); t.status();
  t.controller.command('start'); const command=t.sent.find(x=>x.type==='command');
  assert.equal(command.action,'start'); assert.ok(command.id.length<=64);
  assert.equal(command.session,'session-a'); assert.equal(command.ticket,'ticket-a');
  assert.equal(t.view().pending,true); assert.equal(t.view().recording,false);
  assert.equal(t.controller.command('start'),false);
  t.receive({v:1,type:'result',id:command.id,ok:true,phase:'accepted',message:'处理中'});
  assert.equal(t.view().pending,true); assert.equal(t.view().recording,false);
  t.receive({v:1,type:'result',id:command.id,ok:true,phase:'confirmed'});
  assert.equal(t.view().pending,false); assert.equal(t.view().startEnabled,false); assert.equal(t.view().stopEnabled,false);
  assert.equal(t.sent.at(-1).type,'getStatus');
  t.status({recording:true,recordSeconds:65});
  assert.equal(t.view().startEnabled,false); assert.equal(t.view().stopEnabled,true);
  assert.match(t.view().metaText,/1:05/);
  t.controller.destroy(); checked++;
}
{
  const t=setup(); t.controller.show(); t.status(); t.advance(6000);
  assert.equal(t.view().fresh,false); assert.equal(t.view().startEnabled,false);
  assert.equal(t.controller.command('start'),false);
  t.controller.destroy(); checked++;
}
{
  const t=setup(); t.controller.show(); t.status(); t.controller.command('start');
  for(let i=0;i<4;i++){t.advance(2000);t.status();}
  assert.equal(t.view().pending,false); assert.equal(t.sent.filter(x=>x.type==='command').length,1);
  assert.equal(t.sent.filter(x=>x.type==='getStatus').length,1);
  t.controller.destroy(); checked++;
}
{
  const t=setup(); t.controller.show(); t.status(); t.controller.command('start');
  const command=t.sent.find(x=>x.type==='command');
  t.controller.hide(); assert.equal(t.timers.size,0);
  t.controller.show(); assert.equal(t.view().startEnabled,false);
  t.receive({v:1,type:'result',id:command.id,ok:true,phase:'confirmed'});
  assert.equal(t.view().fresh,false);
  t.status(); assert.equal(t.view().startEnabled,true);
  t.controller.destroy(); assert.equal(t.connect.onmessage,null); checked++;
}
{
  const t=setup(); t.controller.show(); t.status();
  t.connect.onclose(); assert.equal(t.view().fresh,false);
  assert.equal(t.controller.command('start'),false);
  t.connect.onopen(); assert.equal(t.view().startEnabled,false);
  t.status(); assert.equal(t.view().startEnabled,true);
  t.controller.destroy(); checked++;
}
{
  const t=setup(); t.controller.show();
  t.receive({data:JSON.stringify({v:1,type:'status',camera:'ready',recording:null,canControl:true,busy:false})},false);
  assert.equal(t.view().startEnabled,false);
  t.receive({v:2,type:'status',camera:'ready',recording:false,canControl:true,busy:false});
  assert.equal(t.view().startEnabled,false);
  t.receive('not json'); assert.equal(t.view().startEnabled,false);
  t.controller.destroy(); checked++;
}
{
  const t=setup(); t.controller.show(); t.status(); t.controller.command('start');
  const send=t.sends.find(x=>x.data.type==='command'); send.fail('gone',1006);
  assert.equal(t.view().fresh,false); assert.equal(t.view().pending,false);
  assert.equal(t.sent.filter(x=>x.type==='command').length,1);
  t.controller.destroy(); checked++;
}
{
  const t=setup({deferReady:true}); t.controller.show(); t.status();
  t.connect.readyCallback.success({status:2}); assert.equal(t.view().startEnabled,true);
  t.controller.destroy(); checked++;
}
{
  const t=setup({noModule:true}); t.controller.show();
  assert.equal(t.view().startEnabled,false); assert.match(t.view().noteText,/接口不可用/);
  t.controller.destroy(); assert.equal(t.timers.size,0); checked++;
}
{
  const t=setup(); t.controller.show(); t.status({session:undefined,ticket:undefined});
  assert.equal(t.view().startEnabled,false); assert.equal(t.controller.command('start'),false);
  t.status({ticket:''}); assert.equal(t.controller.command('start'),false);
  t.status(); assert.equal(t.view().startEnabled,true);
  t.controller.command('start'); const first=t.sent.find(x=>x.type==='command');
  t.status({session:'session-b',ticket:'ticket-b'});
  assert.equal(t.view().pending,false); assert.equal(t.view().startEnabled,true);
  t.receive({v:1,type:'result',id:first.id,ok:true,phase:'confirmed'});
  assert.equal(t.view().startEnabled,true);
  t.controller.command('start'); const last=t.sent.filter(x=>x.type==='command').at(-1);
  assert.equal(last.session,'session-b'); assert.equal(last.ticket,'ticket-b');
  t.controller.destroy(); checked++;
}
{
  const t=setup();t.controller.show();t.status();t.controller.command('start');
  const command=t.sent.find(x=>x.type==='command');
  const state={v:1,type:'status',seq:5,session:'session-a',ticket:'new-ticket',revision:9,camera:'ready',recording:true,
    canControl:true,canStart:false,canStop:true,busy:false,recordPhase:'recording',recordSeconds:1};
  t.receive({v:1,type:'result',id:command.id,session:'session-a',revision:9,ok:true,phase:'confirmed',state});
  assert.equal(t.view().pending,false);assert.equal(t.view().recording,true);assert.equal(t.view().stopEnabled,true);
  assert.equal(t.sent.filter(x=>x.type==='getStatus').length,0,'paired authoritative state saves a round trip');
  t.status({seq:4,recording:false});assert.equal(t.view().recording,true,'older queued status cannot roll back result');
  t.controller.destroy();checked++;
}
for(const invalid of [{session:'session-b'},{seq:1},{revision:8},{camera:'invalid'},{recording:'true'}]){
  const t=setup();t.controller.show();t.status();t.controller.command('start');
  const command=t.sent.find(x=>x.type==='command');
  const state=Object.assign({v:1,type:'status',seq:5,session:'session-a',ticket:'ticket-new',revision:9,camera:'ready',
    recording:true,canControl:true,canStart:false,canStop:true,busy:false},invalid);
  t.receive({v:1,type:'result',id:command.id,session:'session-a',revision:9,ok:true,phase:'confirmed',state});
  assert.equal(t.view().fresh,false);assert.equal(t.view().stopEnabled,false);
  assert.equal(t.sent.at(-1).type,'getStatus','invalid bundled state uses safe fallback');
  t.controller.destroy();checked++;
}
{
  const t=setup();t.controller.show();t.status();t.status({session:'session-b',ticket:'ticket-b',recording:true});
  t.status({session:'session-a',recording:false,seq:100});
  assert.equal(t.view().recording,true,'retired session cannot restore an old camera state');
  t.controller.destroy();checked++;
}
{
  const t=setup();t.controller.show();t.status({storageMb:64000,remainingSeconds:3723,temperatureState:1,cameraMode:1,videoResolution:16,fpsIndex:6});
  assert.equal(t.view().temperatureState,1);assert.equal(t.view().storageMb,64000);assert.equal(t.view().remainingSeconds,3723);assert.equal(t.view().videoResolution,16);
  t.advance(6000);assert.equal(t.view().temperatureState,-1);assert.equal(t.view().storageMb,-1);assert.equal(t.view().remainingSeconds,-1);assert.equal(t.view().cameraMode,-1);
  for(const temperatureState of [0,1,2,3]){t.status({temperatureState});assert.equal(t.view().temperatureState,temperatureState);}
  for(const temperatureState of [null,'0',-1,4,255,1.5]){t.status({temperatureState});assert.equal(t.view().temperatureState,-1);}
  for(const storageMb of [null,'64000',-1,4294967295]){t.status({storageMb});assert.equal(t.view().storageMb,-1);}
  t.controller.destroy();checked++;
}
{
  const t=setup();t.controller.show();t.status({cameraMode:5,canPhoto:true,canStart:false,canStop:false,recording:null});
  assert.equal(t.view().photoEnabled,true);assert.equal(t.controller.command('photo'),true);
  assert.equal(t.controller.command('photo'),false,'duplicate photo tap is blocked');
  assert.equal(t.sent.filter(x=>x.type==='command').length,1);
  t.advance(9000);assert.equal(t.view().pending,true,'photo capture allows countdown instead of the video timeout');
  t.advance(27000);assert.equal(t.view().pending,false);
  assert.equal(t.sent.filter(x=>x.type==='command').length,1,'photo timeout never retries shutter');
  t.controller.destroy();checked++;
}
{
  const t=setup();t.controller.show();t.status({cameraMode:5,canStart:false,canStop:false});
  assert.equal(t.view().photoEnabled,false);assert.equal(t.controller.command('photo'),false,'old phone without canPhoto stays read-only');
  t.controller.destroy();checked++;
}
console.log(`PASS: ${checked} controller integration scenarios; no camera command retries.`);

// A companion greeting proves only the app channel; camera truth stays unknown.
{
  const t=setup();t.controller.show();t.status();t.controller.command('start');
  const old=t.sent.find(x=>x.type==='command');
  t.receive({v:1,type:'relayHello',session:'board-a',relay:{kind:'nrf52840'}});
  assert.equal(t.view().relayKind,'nrf52840');assert.equal(t.view().relayConnected,true);
  assert.equal(t.view().fresh,false);assert.equal(t.view().pending,false);
  assert.equal(t.controller.command('start'),false);
  const ping=t.sent.find(x=>x.type==='ping');assert.equal(ping.session,'board-a');
  t.advance(80);t.receive({v:1,type:'pong',id:ping.id,session:'wrong'});
  assert.equal(t.view().relayLatencyMs,-1);
  t.receive({v:1,type:'pong',id:ping.id,session:'board-a'});
  assert.equal(t.view().relayLatencyMs,80);assert.equal(t.view().startEnabled,false);
  t.receive({v:1,type:'result',id:old.id,session:'session-a',phase:'confirmed',ok:true});
  t.status({seq:100});assert.equal(t.view().fresh,false,'retired phone status cannot replace board greeting');
  t.status({session:'board-a',ticket:'board-ticket',relay:{kind:'nrf52840'},gpsEnabled:true,gpsState:'ready'});
  assert.equal(t.view().startEnabled,true);assert.equal(t.view().gpsEnabled,false);
  assert.equal(t.view().gpsState,'off','the current board has no GNSS source');
  t.controller.command('start');const command=t.sent.filter(x=>x.type==='command').at(-1);
  assert.equal(command.session,'board-a');assert.equal(command.ticket,'board-ticket');
  t.controller.destroy();checked++;
}
{
  const t=setup();t.controller.show();
  for(const session of ['',null,'x'.repeat(129)]){
    t.receive({v:1,type:'relayHello',session,relay:{kind:'nrf52840'}});
    assert.equal(t.view().relayConnected,false);
  }
  t.receive({v:1,type:'relayHello',session:'board-a',relay:{kind:'unknown'}});
  assert.equal(t.view().relayConnected,false);
  t.receive({v:1,type:'relayHello',session:'board-a',relay:{kind:'nrf52840'}});
  for(const bad of [{seq:NaN},{seq:-1},{seq:1.5},{revision:null},{ticket:''}]){
    t.status(Object.assign({session:'board-a',relay:{kind:'nrf52840'}},bad));
    assert.equal(t.view().fresh,false);
  }
  t.status({session:'board-a',relay:{kind:'phone'}});
  assert.equal(t.view().fresh,false,'same session cannot change relay identity');
  t.status({session:'board-a'});assert.equal(t.view().fresh,true,'same-session status may omit identity');
  t.controller.destroy();checked++;
}
{
  const t=setup();t.controller.show();
  t.receive({v:1,type:'relayHello',session:'board-a',relay:{kind:'nrf52840'}});
  const first=t.sent.find(x=>x.type==='ping');
  t.controller.ping();assert.equal(t.sent.filter(x=>x.type==='ping').length,1,'one outstanding probe');
  t.advance(2000);t.receive({v:1,type:'pong',id:first.id,session:'board-a'});
  assert.equal(t.view().relayLatencyMs,-1,'late pong is discarded');
  t.controller.ping();const second=t.sent.filter(x=>x.type==='ping').at(-1);
  t.controller.hide();t.receive({v:1,type:'pong',id:second.id,session:'board-a'});
  t.controller.show();assert.equal(t.view().relayLatencyMs,-1);
  assert.equal(t.view().startEnabled,false);assert.equal(t.view().relayConnected,false);
  t.controller.destroy();checked++;
}
console.log(`PASS: ${checked} total scenarios including companion identity, ping and lifecycle isolation.`);
