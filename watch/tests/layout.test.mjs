import fs from 'node:fs';
import assert from 'node:assert/strict';
const ux=fs.readFileSync(new URL('../src/pages/index/index.ux',import.meta.url),'utf8');
function style(name){
  const match=ux.match(new RegExp('\\.'+name+'\\s*\\{([^}]+)\\}'));
  assert.ok(match,'missing style '+name);
  return Object.fromEntries(match[1].split(';').filter(x=>x.includes(':')).map(x=>{const at=x.indexOf(':');return [x.slice(0,at).trim(),x.slice(at+1).trim()];}));
}
function number(s,key){assert.ok(/^-?\d+(?:\.\d+)?px$/.test(s[key]),key+' must be a design pixel');return parseFloat(s[key]);}
function box(name,base={}){const s={...base,...style(name)};return {x:number(s,'left'),y:number(s,'top'),w:number(s,'width'),h:number(s,'height')};}
const statusBase=style('status-item');
const icons=['status-phone','status-camera','status-gps','status-battery'].map(n=>box(n,statusBase));
for(const b of [...icons,box('capture'),box('timer'),box('storage-badge'),box('format-summary')]){
  for(const x of [b.x,b.x+b.w])for(const y of [b.y,b.y+b.h])assert.ok(Math.hypot(x-233,y-233)<=225,'interactive/content box escapes the round-screen safe area');
}
const centers=icons.map(b=>({x:b.x+b.w/2,y:b.y+b.h/2}));
assert.ok(centers[0].y>centers[1].y&&centers[3].y>centers[2].y,'icons follow the upper arc, not a straight row');
const radii=centers.map(p=>Math.hypot(p.x-233,p.y-233));assert.ok(Math.max(...radii)-Math.min(...radii)<2,'arc radius is consistent');
const ring=style('capture-progress');
assert.equal(ring.color,'#ff304d');assert.equal(number(ring,'stroke-width'),12);
assert.ok(number(ring,'radius')+number(ring,'stroke-width')/2<=number(ring,'width')/2,'thick ring is not clipped by its own canvas');
assert.doesNotMatch(ux,/<div class="edge-tools"/,'bottom tool buttons were removed');
console.log('PASS: round-screen safe area, concentric status arc, thick red ring and removed bottom buttons');
