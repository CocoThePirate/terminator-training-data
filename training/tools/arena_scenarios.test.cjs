'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {compile,compileMode,hash,catalog}=require('./arena_scenarios.cjs');
test('reproducible configuration, kit inventory and hashes',()=>{
  const c=catalog();
  for(const kit of Object.keys(c.kits).filter(k=>!c.kits[k].mechanics))for(const map of Object.keys(c.maps))for(let seed=0;seed<4;seed++){
    const a=compile(kit,map,seed),b=compile(kit,map,seed);assert.deepEqual(a,b);
    const unsigned=structuredClone(a.scenario);delete unsigned.configuration_sha256;
    assert.equal(hash(unsigned),a.scenario.configuration_sha256);
    const items=a.scenario.kit.inventory;assert.equal(new Set(items.map(i=>i.slot)).size,items.length);
    for(const i of items){assert(i.slot>=0&&i.slot<=40);assert(i.count>0&&i.count<=64);}
  }
  const items=compile('uhc_basic','open_flat').scenario.kit.inventory;
  const count=id=>items.filter(i=>i.item==='minecraft:'+id).reduce((n,i)=>n+i.count,0);
  assert.equal(count('water_bucket'),2);assert.equal(count('lava_bucket'),2);
  assert.equal(count('cobblestone'),128);assert.equal(count('golden_apple'),8);
});
test('every mode has supported spawns, task labels and an explicit mechanics boundary',()=>{
  const c=catalog();assert.equal(Object.keys(c.modes).length,10);
  for(const [id,mode] of Object.entries(c.modes))for(const map of mode.maps)for(let seed=0;seed<4;seed++){
    const a=compileMode(id,seed,map);assert.equal(a.scenario.mode.id,id);assert(a.scenario.mode.collection_tasks.length>0);
    if(id==='fireball_fight')assert.equal(a.scenario.mechanics.adapter,'local_fireball_v1');else assert(!a.scenario.mechanics);
  }
  const bow=compileMode('bow').scenario;assert.equal(bow.environment.border_size,49);assert(bow.map.spawns[1][0]-bow.map.spawns[0][0]>40);
  const mole=compileMode('mole').scenario;assert(mole.map.boxes.some(b=>b.block==='minecraft:air'));assert(mole.kit.inventory.some(i=>i.item==='minecraft:diamond_pickaxe'));
  assert.equal(compileMode('sumo').scenario.kit.inventory.length,0);
  assert.equal(compileMode('axe').scenario.kit.inventory.find(i=>i.slot===40).item,'minecraft:shield');
  assert.throws(()=>compileMode('fireball_fight',0,'bow_range'));assert.throws(()=>compileMode('unknown'));
});
test('geometry hashes catch duplicate seeds and isolate held-out layouts',()=>{
  assert.equal(compile('sword_basic','open_flat',0).scenario.geometry_sha256,compile('uhc_basic','open_flat',3).scenario.geometry_sha256);
  const groups=new Map();for(const [id,map] of Object.entries(catalog().maps))for(let seed=0;seed<4;seed++){
    const h=compile('sword_basic',id,seed).scenario.geometry_sha256;
    if(groups.has(h))assert.equal(groups.get(h),map.split,'geometry leaked between splits');else groups.set(h,map.split);
  }
});
test('unknown kits/maps and invalid seeds are rejected',()=>{
  for(const args of [['none','open_flat',0],['sword_basic','none',0],['sword_basic','open_flat',-1],['sword_basic','open_flat',NaN],['sword_basic','open_flat',0.5]])assert.throws(()=>compile(...args));
});
