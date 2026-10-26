import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const code = fs.readFileSync(path.join(root,'build/pages/index/index.js'),'utf8');
const sent=[];
let timerId=0;
const timers=new Map();
const connect={send:opts=>{sent.push(opts.data);opts.success();},getReadyState:opts=>opts.success({status:1})};
const native=name=>{assert.equal(name,'@app-module/system.interconnect');return {instance:()=>connect};};
const global={$app_require$:native,setTimeout:fn=>{timers.set(++timerId,fn);return timerId;},clearTimeout:id=>timers.delete(id)};
const context=vm.createContext({$app_require$:native,aiot:{
  __ce__:(tag,data,children)=>{
    const opts={...data.__opts__};
    for(const field of ['src','value','classList'])if(typeof opts[field]==='function')opts[field]=opts[field].call(data.__vm__);
    return {tag,opts,children:children.flat(Infinity).filter(Boolean)};
  },
  __ci__:(data,render)=>data.__opts__.shown.call(data.__vm__)?render():[]
}});
const init=vm.runInContext('('+code.replace(/^export default /,'')+')',context);
const entries={};init(global,global,{},entries,()=>{});
const out={};entries.entry(out);
const page=Object.assign({},out.default.data,out.default);
page.onInit(); assert.equal(page.startEnabled,false);
connect.onmessage({data:JSON.stringify({v:1,type:'status',camera:'ready',recording:false,canControl:true,canStart:true,canStop:false,busy:false,session:'s',seq:1,revision:1,ticket:'t'})});
assert.equal(page.startEnabled,true);assert.equal(page.stopEnabled,false);
const dom=page.template(page);
const rendered=JSON.stringify(dom);
assert.equal(page.captureIcon,'/common/ui/capture-record.png');
assert.equal(typeof page.tapCapture,'function');
assert.match(rendered,/"remote"/);
assert.match(rendered,/"status-strip"/);
assert.match(rendered,/"capture"/);
assert.doesNotMatch(rendered,/"capture-label"/);
assert.doesNotMatch(rendered,/"heading"|"led"|"model"|"status-label"|ACTION 4|待机/);
assert.equal((rendered.match(/"status-icon"/g)||[]).length,4);
function byClass(node,name){return (node.opts.classList||[]).includes(name)?node:(node.children||[]).map(n=>byClass(n,name)).find(Boolean);}
const capture=byClass(dom,'capture');assert.equal(typeof capture.opts.events.click,'function');
capture.opts.events.click({});const command=sent.find(x=>x.type==='command');assert.ok(command);
assert.equal(command.session,'s');assert.equal(command.ticket,'t');
assert.equal(page.startEnabled,false);assert.equal(page.stopEnabled,false);
connect.onmessage({data:JSON.stringify({v:1,type:'result',id:command.id,ok:true,phase:'confirmed'})});
assert.equal(page.startEnabled,false);assert.equal(page.stopEnabled,false);
connect.onmessage({data:JSON.stringify({v:1,type:'status',camera:'ready',recording:true,canControl:true,canStart:false,canStop:true,busy:false,session:'s',seq:2,revision:2,ticket:'t2'})});
assert.equal(page.startEnabled,false);assert.equal(page.stopEnabled,true);
assert.equal(page.captureIcon,'/common/ui/capture-stop.png');
page.applyState({phoneConnected:true,cameraReady:true,gpsEnabled:true,gpsState:'provider_off',recordPhase:'idle',canStart:true,canStop:false});
assert.equal(page.gpsIcon,'/common/ui/gps-error.png');
page.applyState({phoneConnected:true,cameraReady:true,gpsEnabled:true,gpsState:'ready',recordPhase:'idle',canStart:true,canStop:false});
assert.equal(page.gpsIcon,'/common/ui/gps-on.png');
page.onHide();assert.equal(timers.size,0);
page.onShow();assert.equal(page.startEnabled,false);assert.equal(page.stopEnabled,false);
page.onDestroy();assert.equal(timers.size,0);assert.equal(connect.onmessage,null);
console.log('PASS: compiled page wiring, independent controls, ticket forwarding, freshness on resume.');
