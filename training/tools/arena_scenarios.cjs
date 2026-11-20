'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const catalog=()=>JSON.parse(fs.readFileSync(path.join(__dirname,'../scenarios/catalog.json'),'utf8'));
const canonical=x=>x===null||typeof x!=='object'?JSON.stringify(x):Array.isArray(x)?'['+x.map(canonical).join(',')+']':
  '{'+Object.keys(x).sort().map(k=>JSON.stringify(k)+':'+canonical(x[k])).join(',')+'}';
const hash=x=>crypto.createHash('sha256').update(canonical(x)).digest('hex');
function compile(kitId,mapId,seed=0,modeId=null) {
  const c=catalog();assert.equal(c.schema,'arena_catalog/v1');
  assert(Object.hasOwn(c.kits,kitId),'Unknown kit');assert(Object.hasOwn(c.maps,mapId),'Unknown map');
  assert(Number.isSafeInteger(seed)&&seed>=0&&seed<=1000000000,'Invalid seed');
  const kit=c.kits[kitId],map=structuredClone(c.maps[mapId]);
  const v2=map.version===2,size=v2?map.size:17,feet=v2?map.feet_y:-60;
  assert(Number.isInteger(size)&&size>=17&&size<=49&&size%2===1,'Invalid arena size');
  assert(Number.isInteger(feet)&&feet>=-60&&feet<=0,'Invalid floor height');
  const mode=modeId??kit.default_mode;
  if(modeId)assert(c.modes[modeId]?.kit===kitId&&c.modes[modeId].maps.includes(mapId),'Mode/map mismatch');
  assert(['training','validation','evaluation'].includes(map.split),'Invalid map split');
  assert(Array.isArray(map.boxes)&&map.boxes.length<=64,'Invalid map boxes');
  const vanilla=id=>typeof id==='string'&&/^minecraft:[a-z0-9_]+$/.test(id);
  assert(vanilla(map.floor),'Invalid floor id');
  for(const b of map.boxes){
    assert(vanilla(b.block)&&b.min.length===3&&b.max.length===3,'Invalid map feature');
    for(let axis=0;axis<3;axis++)assert(Number.isInteger(b.min[axis])&&Number.isInteger(b.max[axis])&&b.min[axis]<=b.max[axis]&&b.min[axis]>=(axis===1?(v2?-8:0):(v2?0:1))&&b.max[axis]<=(axis===1?(v2?8:4):size-(v2?1:2)),'Map feature exceeds arena bounds');
  }
  // Seeded reflections preserve the empty spawn lane at z=8. Seed alone is not
  // proof of a new layout: record and compare geometry hashes.
  for(const b of map.boxes)for(const axis of [0,2])if(seed&(axis===0?1:2)) {
    const min=size-1-b.max[axis],max=size-1-b.min[axis];b.min[axis]=min;b.max[axis]=max;
  }
  const spawns=v2?map.spawns.map(p=>[seed&1?size-p[0]:p[0],feet,seed&2?size-p[1]:p[1]]):null;
  const inventory=[...kit.items,...(kit.armor??c.armor)].sort((a,b)=>a.slot-b.slot);
  assert.equal(new Set(inventory.map(i=>i.slot)).size,inventory.length,'Duplicate inventory slot');
  for(const i of inventory)assert(Number.isInteger(i.slot)&&i.slot>=0&&i.slot<=40&&Number.isInteger(i.count)&&i.count>=1&&i.count<=64&&vanilla(i.item),'Invalid inventory entry');
  assert(Number.isInteger(kit.apples)&&kit.apples>=0&&kit.apples<=64,'Invalid apple count');
  assert.equal(inventory.filter(i=>i.slot===1&&i.item==='minecraft:golden_apple').reduce((n,i)=>n+i.count,0),kit.apples,'Apple count differs from slot 1');
  const spec={schema:v2?'arena_scenario/v2':'arena_scenario/v1',id:`${kitId}.${mapId}.${seed}`,split:map.split,seed,
    environment:{difficulty:'normal',natural_regeneration:true,border_size:size,center:[size/2,size/2],feet_y:feet},
    kit:{id:kitId,version:1,policy_profile:kit.profile,inventory},
    map:{id:mapId,version:v2?2:1,floor:map.floor,boxes:map.boxes,...(v2?{void:map.void,spawns}: {})}};
  if(mode)spec.mode={id:mode,collection_tasks:c.modes[mode].collection_tasks,notes:c.modes[mode].notes};
  if(kit.mechanics){assert(v2,'Custom mechanics require v2 maps');spec.mechanics=kit.mechanics;}
  const desired=(x,y,z)=>{
    for(let i=map.boxes.length-1;i>=0;i--){const b=map.boxes[i];if([x,y,z].every((n,j)=>n>=b.min[j]&&n<=b.max[j]))return b.block;}
    return y<0&&!map.void?'minecraft:stone':'minecraft:air';
  };
  for(const p of spawns??[[6.5,-60,8.5],[10.5,-60,8.5]]){
    const [x,,z]=p;assert(x>=1&&x<size-1&&z>=1&&z<size-1,'Spawn outside arena');
    assert.equal(desired(Math.floor(x),0,Math.floor(z)),'minecraft:air','Spawn feet obstructed');
    assert.equal(desired(Math.floor(x),1,Math.floor(z)),'minecraft:air','Spawn head obstructed');
    assert.notEqual(desired(Math.floor(x),-1,Math.floor(z)),'minecraft:air','Spawn lacks support');
  }
  const cells=new Map();for(const b of map.boxes)for(let x=b.min[0];x<=b.max[0];x++)for(let y=b.min[1];y<=b.max[1];y++)for(let z=b.min[2];z<=b.max[2];z++) {
    const key=[x,y,z].join(',');if(b.block==='minecraft:air')cells.delete(key);else cells.set(key,b.block);
  }
  spec.geometry_sha256=hash({floor:map.floor,...(v2?{size,feet,void:map.void}:{}),cells:[...cells].sort(([a],[b])=>a<b?-1:a>b?1:0)});
  spec.configuration_sha256=hash(spec);
  return {host_health:20,guest_health:20,host_apples:kit.apples,guest_apples:kit.apples,distance:5,mirror:false,scenario:spec};
}
function compileMode(modeId,seed=0,mapId=null){const mode=catalog().modes[modeId];assert(mode,'Unknown mode');return compile(mode.kit,mapId??mode.map,seed,modeId);}
if(require.main===module) {
  const [command,...args]=process.argv.slice(2);
  if(command==='list')console.log(JSON.stringify(catalog(),null,2));
  else if(command==='mode') {
    const [mode,seed,out,map]=args;if(!out||fs.existsSync(out))throw new Error('mode MODE SEED NEW_SETUP.json [MAP]');
    fs.writeFileSync(out,JSON.stringify(compileMode(mode,Number(seed),map),null,2)+'\n',{flag:'wx'});console.log(out);
  } else if(command==='compile') {
    const [kit,map,seed,out]=args;if(!out||fs.existsSync(out))throw new Error('compile KIT MAP SEED NEW_SETUP.json');
    fs.writeFileSync(out,JSON.stringify(compile(kit,map,Number(seed)),null,2)+'\n',{flag:'wx'});
    console.log(out);
  } else throw new Error('Usage: arena_scenarios.cjs list | compile KIT MAP SEED NEW_SETUP.json');
}
module.exports={compile,compileMode,canonical,hash,catalog};
