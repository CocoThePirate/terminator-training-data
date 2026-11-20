'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {catalog,compile,compileMode,hash}=require('./arena_scenarios.cjs');
const slot=n=>n<9?'hotbar.'+n:n<36?'inventory.'+(n-9):['armor.feet','armor.legs','armor.chest','armor.head','weapon.offhand'][n-36];
function expected(spec,x,y,z){
  const e=spec.environment,size=e.border_size,feet=e.feet_y,v2=spec.schema==='arena_scenario/v2';
  if(v2&&(x===-1||z===-1||x===size||z===size)&&y>=feet&&y<=feet+5)return 'minecraft:stone';
  if(!v2){if(y===-64)return 'minecraft:bedrock';if(y<-61)return 'minecraft:dirt';if(y===-61)return spec.map.floor;
    if((x===-1||z===-1||x===size||z===size)&&y<=-55)return 'minecraft:stone';}
  for(let i=spec.map.boxes.length-1;i>=0;i--){const b=spec.map.boxes[i];if(x>=b.min[0]&&x<=b.max[0]&&z>=b.min[2]&&z<=b.max[2]&&y-feet>=b.min[1]&&y-feet<=b.max[1])return b.block;}
  if(!v2||spec.map.void)return 'minecraft:air';
  return y===-64?'minecraft:bedrock':y<feet-1?'minecraft:dirt':y===feet-1?spec.map.floor:'minecraft:air';
}
function fill(min,max,block){
  const a=[];const area=(max[0]-min[0]+1)*(max[2]-min[2]+1),height=Math.floor(32768/area);assert(height>=1);
  for(let y=min[1];y<=max[1];y+=height)a.push(`fill ${min[0]} ${y} ${min[2]} ${max[0]} ${Math.min(max[1],y+height-1)} ${max[2]} ${block}`);return a;
}
function mapCommands(spec){const {border_size:s,feet_y:f}=spec.environment;
  let a=['difficulty normal','gamerule minecraft:spawn_mobs false','gamerule minecraft:natural_health_regeneration true','gamerule minecraft:advance_time false','gamerule minecraft:advance_weather false','gamerule minecraft:random_tick_speed 0','gamerule minecraft:respawn_radius 0','gamerule minecraft:pvp true',`worldborder center ${s/2} ${s/2}`,`worldborder set ${s}`,`kill @e[type=!minecraft:player,x=-1,y=-64,z=-1,dx=${s+1},dy=383,dz=${s+1}]`,...fill([-1,-64,-1],[s,319,s],'minecraft:air')];
  if(!spec.map.void)a.push(...fill([-1,-64,-1],[s,-64,s],'minecraft:bedrock'),...fill([-1,-63,-1],[s,f-2,s],'minecraft:dirt'),...fill([-1,f-1,-1],[s,f-1,s],spec.map.floor));
  for(const [min,max] of [[[ -1,f,-1],[-1,f+5,s]],[[s,f,-1],[s,f+5,s]],[[-1,f,-1],[s,f+5,-1]],[[-1,f,s],[s,f+5,s]]])a.push(...fill(min,max,'minecraft:stone'));
  for(const b of spec.map.boxes)a.push(...fill([b.min[0],f+b.min[1],b.min[2]],[b.max[0],f+b.max[1],b.max[2]],b.block));return a;
}
function verifyCommands(spec,key){const s=spec.environment.border_size,f=spec.environment.feet_y,points=new Set();
  const add=(x,y,z)=>{if(y>=-64&&y<=319)points.add([x,y,z].join(' '));};
  for(let x=-1;x<=s;x++)for(let z=-1;z<=s;z++){add(x,f-1,z);add(x,-64,z);}
  for(const b of spec.map.boxes){for(const x of [b.min[0],b.max[0]])for(const y of [b.min[1],b.max[1]])for(const z of [b.min[2],b.max[2]])add(x,f+y,z);add(Math.floor((b.min[0]+b.max[0])/2),f+Math.floor((b.min[1]+b.max[1])/2),Math.floor((b.min[2]+b.max[2])/2));}
  for(let i=0;i<128;i++)add((i*17)%(s+2)-1,f-8+(i*7)%18,(i*23)%(s+2)-1);
  const lines=[`data modify storage terminator:validation failures set value []`];
  for(const p of [...points].sort()){const [x,y,z]=p.split(' ').map(Number),block=expected(spec,x,y,z);lines.push(`execute unless block ${p} ${block} run data modify storage terminator:validation failures append value {position:[I;${x},${y},${z}],expected:"${block}"}`);}
  lines.push(`data modify storage terminator:validation checked set value ${points.size}`,`data modify storage terminator:validation map set value "${key}"`);return lines;
}
function exportPort(root,reports){
  root=path.resolve(root);assert(!fs.existsSync(path.join(root,'manifest.json')),'Choose a fresh target export directory');
  const c=catalog(),registries=JSON.parse(fs.readFileSync(path.join(reports,'registries.json'))),commands=JSON.parse(fs.readFileSync(path.join(reports,'commands.json')));
  const known=(r,id)=>assert(registries[r]?.entries[id],'Unknown 26.2 registry ID: '+id);
  for(const k of Object.values(c.kits))for(const i of [...k.items,...(k.armor??c.armor)])known('minecraft:item',i.item);
  for(const m of Object.values(c.maps)){known('minecraft:block',m.floor);for(const b of m.boxes)known('minecraft:block',b.block);}
  for(const rule of ['spawn_mobs','natural_health_regeneration','advance_time','advance_weather','random_tick_speed','respawn_radius','pvp'])assert(commands.children.gamerule.children['minecraft:'+rule],'26.2 gamerule missing: '+rule);
  const pack=path.join(root,'datapack','terminator_training'),write=(relative,text)=>{const file=path.join(pack,relative);fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,text);};
  write('pack.mcmeta',JSON.stringify({pack:{description:'Terminator training scenarios for Minecraft Java 26.2',min_format:[107,1],max_format:[107,1]}},null,2));
  const fn=(id,lines)=>write('data/terminator/function/'+id+'.mcfunction',lines.join('\n')+'\n');
  const maps=[],kits=[],scenarios=[];
  for(const [id,k] of Object.entries(c.kits)){
    const inventory=[...k.items,...(k.armor??c.armor)],lines=['clear @s','effect clear @s',...inventory.map(i=>`item replace entity @s ${slot(i.slot)} with ${i.item} ${i.count}`),'tag @s add terminator_reset'];fn('kits/'+id,lines);kits.push({id,inventory,function:'terminator:kits/'+id});
  }
  for(const id of Object.keys(c.maps))for(let seed=0;seed<4;seed++){
    const spec=compile('sword_basic',id,seed).scenario,key=id+'_'+seed,verify=verifyCommands(spec,key);
    fn('maps/'+key,mapCommands(spec));fn('verify/'+key,verify);
    maps.push({id,seed,key,function:'terminator:maps/'+key,verify_function:'terminator:verify/'+key,geometry_sha256:spec.geometry_sha256,checks:verify.length-3});
  }
  const guard=['execute unless data storage terminator:runtime adapter run tellraw @a {"text":"Install and enable the 26.2 training-arena helper in this private world first.","color":"red"}','execute unless data storage terminator:runtime adapter run return fail'];
  for(const [id,mode] of Object.entries(c.modes))for(const map of mode.maps)for(let seed=0;seed<4;seed++){
    const setup=compileMode(id,seed,map),spec=setup.scenario,key=id+'_'+map+'_'+seed;
    const spawns=spec.map.spawns??[[6,-60,8.5],[11,-60,8.5]],roles=['host','guest'];
    const lines=[...guard,'tag @a remove terminator_fireball','function terminator:maps/'+map+'_'+seed];
    for(let i=0;i<2;i++){const a=spawns[i],b=spawns[1-i],yaw=Math.atan2(-(b[0]-a[0]),b[2]-a[2])*180/Math.PI,selector='@a[tag=terminator_'+roles[i]+']';
      lines.push(`execute as ${selector} run function terminator:kits/${mode.kit}`,`tp ${selector} ${a.join(' ')} ${yaw} 0`);
      if(spec.mechanics)lines.push(`tag ${selector} add terminator_fireball`);
    }
    lines.push(`data modify storage terminator:validation scenario set value "${key}"`);
    fn('apply/'+key,lines);fn('setup/'+key,[...guard,'execute if entity @s[type=minecraft:player,tag=!terminator_guest] run function terminator:roles/host','forceload add -1 -1 49 49',`schedule function terminator:apply/${key} 2s replace`]);
    const dir=path.join(root,'scenarios');fs.mkdirSync(dir,{recursive:true});fs.writeFileSync(path.join(dir,key+'.json'),JSON.stringify(setup,null,2)+'\n');
    scenarios.push({mode:id,map,seed,key,function:'terminator:setup/'+key,apply_function:'terminator:apply/'+key,source_configuration_sha256:spec.configuration_sha256,geometry_sha256:spec.geometry_sha256,source_physics:'1.21.4',target_game:'26.2',mechanics:spec.mechanics?'local_fireball_26_2_v1':'vanilla_26.2',runtime_validated:false});
  }
  for(const role of ['host','guest'])fn('roles/'+role,[`tag @a remove terminator_${role}`,`tag @s remove terminator_${role==='host'?'guest':'host'}`,`tag @s add terminator_${role}`]);
  const manifest={schema:'terminator_scenario_port/v1',source_game:'1.21.4',target_game:'26.2',java_major:25,datapack_version:[107,1],registry_ids_verified:true,
    runtime_validation:'pending',kits,maps,scenarios,
    historical_recordings:'Retain 1.21.4 source physics; no automatic on-policy eligibility or target-physics claim.',
    client_recorder:{artifact:'terminator-arena-26.2.jar',schemas:['client_dataset_session/v1','client_dataset_frame/v1'],runtime_validation:'pending_native_client_check'}};
  fs.writeFileSync(path.join(root,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
  fs.copyFileSync(path.join(reports,'registries.json'),path.join(root,'registries-26.2.json'));
  fs.writeFileSync(path.join(root,'native-check-config.json'),JSON.stringify({kits,scenarios},null,2)+'\n');
  console.log(JSON.stringify({root,kits:kits.length,map_variants:maps.length,scenarios:scenarios.length,registry_ids_verified:true}));return manifest;
}
if(require.main===module)exportPort(process.argv[2],process.argv[3]);
module.exports={exportPort,expected,mapCommands,fill,slot,verifyCommands};
