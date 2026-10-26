import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const context=vm.createContext({});
vm.runInContext(fs.readFileSync(new URL('../src/common/cameraDisplay.js',import.meta.url),'utf8').replace(/export function /g,'function '),context);
assert.equal(context.storageLabel(0),'0 MB');assert.equal(context.storageLabel(999),'999 MB');
assert.equal(context.storageLabel(64000),'64.0 GB');
assert.equal(context.remainingLabel(0),'0分');assert.equal(context.remainingLabel(59),'<1分');
assert.equal(context.remainingLabel(60),'1分');assert.equal(context.remainingLabel(3599),'59分');
assert.equal(context.remainingLabel(3600),'1小时00分');assert.equal(context.remainingLabel(3723),'1小时02分');
assert.equal(context.remainingLabel(359999),'99小时59分');assert.equal(context.remainingLabel(360000),'100小时');
for(const value of [-1,null,undefined,NaN,Infinity,4294967295,'3723',3.5])assert.equal(context.remainingLabel(value),'—');
for(const value of [-1,null,undefined,NaN,Infinity,4294967295,'64000'])assert.equal(context.storageLabel(value),'—');
assert.equal(context.videoLabel(1,16,6),'4K · 60');assert.equal(context.videoLabel(1,10,3),'1080P · 30');
assert.equal(context.videoLabel(1,16,255),'4K · —');assert.equal(context.videoLabel(1,255,3),'—');
assert.equal(context.videoLabel(0,16,6),'—','slow motion is not labeled using the normal-video FPS map');
assert.equal(context.videoLabel(5,4,3),'—','photo burst count is not labeled as FPS');
assert.equal(context.videoLabel(1,'16',6),'—');
console.log('PASS: storage units, absent/invalid data, resolution/FPS mapping and mode semantics');

for (const [value,label,icon] of [[0,'正常','normal'],[1,'偏高','warm'],[2,'过热禁录','hot'],[3,'即将关机','hot']]) {
  assert.equal(context.temperatureLabel(value),label);
  assert.equal(context.temperatureIcon(value),'/common/ui/temperature-'+icon+'.png');
}
for (const value of [-1,4,255,null,undefined,'0',NaN,Infinity,0.5]) {
  assert.equal(context.temperatureLabel(value),'—');
  assert.equal(context.temperatureIcon(value),'/common/ui/temperature-off.png');
}
console.log('PASS: camera thermal states and unknown data never become Celsius values');
