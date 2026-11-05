import test from 'node:test';
import assert from 'node:assert/strict';
import {plan} from './release-plan.mjs';
test('versions increase, reruns stay deterministic, and tracks have independent bases',()=>{
 const phone={track:'phone',baseVersion:'0.4.4',baseVersionCode:13};
 assert.deepEqual(plan(phone,1),{track:'phone',version:'0.4.5',version_code:14});
 assert.equal(plan(phone,2).version,'0.4.6');
 assert.equal(plan(phone,2).version_code,15);
 assert.deepEqual(plan(phone,3),plan(phone,3));
 assert.equal(plan({track:'firmware',baseVersion:'0.1.0',baseVersionCode:13},1).version,'0.1.1');
 assert.throws(()=>plan(phone,0)); assert.throws(()=>plan(phone,NaN));
 assert.throws(()=>plan({...phone,baseVersionCode:2100000000},1));
 assert.throws(()=>plan({...phone,track:'other'},1));
});
