import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const code = fs.readFileSync(path.join(root,'build/pages/index/index.js'),'utf8');
const sent=[];
const vibrations=[];
let feedbackValue='1';
let timerId=0;
let time=1000;
const ClockDate=class extends Date { static now(){return time;} };
const timers=new Map();
const connect={send:opts=>{sent.push(opts.data);opts.success();},getReadyState:opts=>opts.success({status:1})};
const native=name=>{
  if(name==='@app-module/system.interconnect') return {instance:()=>connect};
  if(name==='@system.storage'||name==='@app-module/system.storage') return {
    get:opts=>opts.success(feedbackValue),
    set:opts=>{feedbackValue=opts.value;}
  };
  if(name==='@system.vibrator'||name==='@app-module/system.vibrator') return {vibrate:opts=>vibrations.push(opts.mode)};
  assert.fail(`unexpected native module: ${name}`);
};
const global={$app_require$:native,setTimeout:(fn,ms)=>{timers.set(++timerId,{fn,at:time+ms});return timerId;},clearTimeout:id=>timers.delete(id)};
function advance(ms){const end=time+ms;for(;;){const due=[...timers].filter(([,v])=>v.at<=end).sort((a,b)=>a[1].at-b[1].at)[0];if(!due)break;time=due[1].at;timers.delete(due[0]);due[1].fn();}time=end;}
const context=vm.createContext({Date:ClockDate,$app_require$:native,aiot:{
  __ce__:(tag,data,children)=>{
    const opts={...data.__opts__};
    for(const field of ['src','value','classList','percent'])if(typeof opts[field]==='function')opts[field]=opts[field].call(data.__vm__);
    return {tag,opts,children:children.flat(Infinity).filter(Boolean)};
  },
  __ci__:(data,render)=>data.__opts__.shown.call(data.__vm__)?render():[]
}});
const init=vm.runInContext('('+code.replace(/^export default /,'')+')',context);
const entries={};init(global,global,{},entries,()=>{});
const out={};entries.entry(out);
const page=Object.assign({},out.default.data,out.default);
page.onInit(); assert.equal(page.startEnabled,false);
assert.equal(page.refreshMode,true);assert.equal(page.captureIcon,'/common/ui/refresh.png');
page.startCapturePress();assert.equal(sent.filter(x=>x.type==='command').length,0);assert.deepEqual(vibrations,[]);
connect.onmessage({data:JSON.stringify({v:1,type:'status',camera:'ready',recording:false,canControl:true,canStart:true,canStop:false,busy:false,session:'s',seq:1,revision:1,ticket:'t'})});
assert.equal(page.startEnabled,true);assert.equal(page.stopEnabled,false);
const dom=page.template(page);
const rendered=JSON.stringify(dom);
assert.equal(page.captureIcon,'/common/ui/capture-record.png');
assert.equal(page.feedbackEnabled,true);
assert.equal(typeof page.startCapturePress,'function');
assert.match(rendered,/"remote"/);
assert.match(rendered,/"status-strip"/);
assert.match(rendered,/"capture"/);
assert.doesNotMatch(rendered,/"edge-tools"|"edge-tool"|"storage-caption"/);
assert.match(rendered,/sd-card\.png/);
assert.doesNotMatch(rendered,/"capture-label"/);
assert.doesNotMatch(rendered,/"heading"|"led"|"model"|"status-label"|ACTION 4|待机/);
assert.equal((rendered.match(/"status-icon"/g)||[]).length,6);
function byClass(node,name){return (node.opts.classList||[]).includes(name)?node:(node.children||[]).map(n=>byClass(n,name)).find(Boolean);}
const capture=byClass(dom,'capture');assert.equal(typeof capture.opts.events.touchstart,'function');
capture.opts.events.touchstart({});const command=sent.find(x=>x.type==='command');assert.ok(command);
assert.deepEqual(vibrations,['short']);
assert.equal(command.session,'s');assert.equal(command.ticket,'t');
assert.equal(page.startEnabled,false);assert.equal(page.stopEnabled,false);assert.equal(page.refreshMode,false,'pending recording commands do not become refresh taps');
connect.onmessage({data:JSON.stringify({v:1,type:'result',id:command.id,ok:true,phase:'confirmed'})});
assert.equal(page.startEnabled,false);assert.equal(page.stopEnabled,false);
connect.onmessage({data:JSON.stringify({v:1,type:'status',camera:'ready',recording:true,canControl:true,canStart:false,canStop:true,busy:false,session:'s',seq:2,revision:2,ticket:'t2',storageMb:64000,remainingSeconds:3723,temperatureState:1,cameraMode:1,videoResolution:16,fpsIndex:6})});
assert.equal(page.startEnabled,false);assert.equal(page.stopEnabled,true);
assert.equal(page.captureIcon,'/common/ui/capture-stop.png');
assert.equal(page.temperatureText,'偏高');assert.equal(page.temperatureIcon,'/common/ui/temperature-warm.png');
assert.equal(page.remainingText,'1小时02分');assert.equal(page.videoFormatText,'4K · 60');
capture.opts.events.touchstart({});
assert.equal(sent.filter(x=>x.type==='command'&&x.action==='stop').length,0);
assert.equal(page.pressRingVisible,true);
assert.deepEqual(vibrations,['short','long']);
assert.equal(page.pressProgress,0);
advance(200);const partial=page.pressProgress;assert.ok(partial>0&&partial<100);
const partialDom=page.template(page);const ring=byClass(partialDom,'capture-progress');assert.equal(ring.tag,'progress');assert.equal(ring.opts.percent,partial);
capture.opts.events.touchend({});advance(32);assert.ok(page.pressProgress<partial);advance(180);
assert.equal(page.pressRingVisible,false);assert.equal(sent.filter(x=>x.type==='command'&&x.action==='stop').length,0);
capture.opts.events.touchstart({});advance(650);
assert.equal(sent.filter(x=>x.type==='command'&&x.action==='stop').length,1);
assert.deepEqual(vibrations,['short','long','long']);
assert.equal(page.pressProgress,100);
capture.opts.events.touchend({});assert.equal(page.pressRingVisible,false);assert.equal(page.pressProgress,0);
page.applyState({phoneConnected:true,cameraReady:true,gpsEnabled:true,gpsState:'provider_off',recordPhase:'idle',canStart:true,canStop:false});
assert.equal(page.gpsIcon,'/common/ui/gps-error.png');
page.applyState({phoneConnected:true,cameraReady:true,gpsEnabled:true,gpsState:'ready',recordPhase:'idle',canStart:true,canStop:false});
assert.equal(page.gpsIcon,'/common/ui/gps-on.png');
connect.onmessage({data:JSON.stringify({v:1,type:'status',camera:'ready',recording:null,canControl:false,canStart:false,canStop:false,canPhoto:true,cameraMode:5,busy:false,session:'s',seq:3,revision:3,ticket:'photo-ticket'})});
assert.equal(page.photoEnabled,true);assert.equal(page.captureIcon,'/common/ui/capture-photo.png');assert.equal(page.timerText,'拍照');
page.startCapturePress();assert.equal(sent.filter(x=>x.type==='command'&&x.action==='photo').length,1);
assert.equal(page.timerText,'拍摄中');
page.startCapturePress();assert.equal(sent.filter(x=>x.type==='command'&&x.action==='photo').length,1);
page.onHide();assert.equal(timers.size,0);
assert.equal(page.temperatureText,'—');assert.equal(page.temperatureIcon,'/common/ui/temperature-off.png');
assert.equal(page.remainingText,'—');assert.equal(page.videoFormatText,'—');
page.onShow();assert.equal(page.startEnabled,false);assert.equal(page.stopEnabled,false);
page.onDestroy();assert.equal(timers.size,0);assert.equal(connect.onmessage,null);
console.log('PASS: compiled page wiring, refresh vs record, remaining time, native arc, release behavior and lifecycle.');
