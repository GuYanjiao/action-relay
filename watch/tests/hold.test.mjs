import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const code=fs.readFileSync(new URL('../src/common/holdProgress.js',import.meta.url),'utf8');
const context=vm.createContext({});
vm.runInContext(code.replace('export function createHoldProgress','function createHoldProgress'),context);
function setup(result=true){
  let time=0,offset=0,next=0,canStop=true,stops=0;
  const timers=new Map(),frames=[];
  let view={progress:0,visible:false};
  const hold=context.createHoldProgress({now:()=>time+offset,
    setTimer:(fn,ms)=>{const id=++next;timers.set(id,{fn,at:time+ms});return id;},
    clearTimer:id=>timers.delete(id),canStop:()=>canStop,
    onStop:()=>{stops++;canStop=false;if(result==='throw')throw Error('transport failed');return result;},
    onProgress:(progress,visible)=>{view={progress,visible};frames.push(view);}
  });
  function advance(ms){const end=time+ms;for(;;){const due=[...timers].filter(([,t])=>t.at<=end).sort((a,b)=>a[1].at-b[1].at)[0];if(!due)break;
    time=due[1].at;timers.delete(due[0]);due[1].fn();}time=end;}
  return {hold,timers,frames,advance,view:()=>view,stops:()=>stops,available:v=>canStop=v,offset:v=>offset=v};
}
let checked=0;
{
  const t=setup();t.available(false);assert.equal(t.hold.start(),false);assert.equal(t.timers.size,0);assert.equal(t.view().visible,false);checked++;
}
{
  const t=setup();t.hold.start();t.advance(320);const before=t.view().progress;
  assert.ok(before>0&&before<100);assert.ok(new Set(t.frames.map(f=>f.progress)).size>11,'continuous native progress exceeds the old ten segments');
  t.hold.release();assert.equal(t.view().visible,true);t.advance(48);assert.ok(t.view().progress<before);
  t.advance(180);assert.equal(t.view().visible,false);assert.equal(t.stops(),0);assert.equal(t.timers.size,0);checked++;
}
{
  const t=setup();t.hold.start();t.advance(649);assert.equal(t.stops(),0);t.advance(1);
  assert.equal(t.stops(),1);assert.equal(t.view().progress,100);assert.equal(t.view().visible,true);
  t.hold.release();assert.equal(t.view().visible,false,'committed stop disappears on release without falling');
  assert.equal(t.timers.size,0);t.hold.release();t.advance(1000);assert.equal(t.stops(),1);checked++;
}
{
  const t=setup();t.hold.start();t.advance(200);t.available(false);t.advance(450);
  assert.equal(t.stops(),0);assert.equal(t.view().visible,false);assert.equal(t.timers.size,0);checked++;
}
{
  const t=setup();t.hold.start();const old=[...t.timers.values()].map(v=>v.fn);t.advance(100);t.hold.cancel();
  assert.equal(t.timers.size,0);t.hold.start();old.forEach(fn=>fn());assert.equal(t.stops(),0);
  assert.equal(t.timers.size,2,'stale page callbacks cannot erase the new hold timers');t.advance(650);assert.equal(t.stops(),1);
  t.hold.cancel();assert.equal(t.timers.size,0);checked++;
}
{
  const t=setup();t.hold.start();t.advance(200);t.hold.release();t.advance(32);t.hold.start();
  assert.equal(t.view().progress,0);t.advance(650);assert.equal(t.stops(),1);t.hold.release();assert.equal(t.timers.size,0);checked++;
}
for(const result of [false,'throw']){
  const t=setup(result);t.hold.start();t.advance(650);assert.equal(t.stops(),1);assert.equal(t.view().visible,false);
  assert.equal(t.timers.size,0);assert.equal(t.frames.some(f=>f.progress===100),false,'failed dispatch is not a completed gesture');checked++;
}
{
  const t=setup();t.hold.start();t.advance(200);const before=t.view().progress;t.offset(-1000);t.advance(100);
  assert.ok(t.view().progress>=before,'clock rollback cannot reverse a held ring');t.offset(5000);t.advance(16);
  assert.ok(t.view().progress<100);assert.equal(t.stops(),0,'clock jump cannot trigger an early stop');t.advance(334);
  assert.equal(t.stops(),1);t.hold.release();checked++;
}
console.log(`PASS: ${checked} hold gesture scenarios (smooth progress, abort, committed release, lifecycle, failures, clock changes)`);
