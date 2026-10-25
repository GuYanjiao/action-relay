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
  function status(fields = {}) { receive(Object.assign({v:1,type:'status',camera:'ready',recording:false,canControl:true,busy:false,battery:72,recordSeconds:0,message:'',session:'session-a',ticket:'ticket-a'},fields)); }
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
console.log(`PASS: ${checked} controller integration scenarios; no camera command retries.`);
