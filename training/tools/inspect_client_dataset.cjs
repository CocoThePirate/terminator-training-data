'use strict';
const fs=require('node:fs'),path=require('node:path'),readline=require('node:readline'),assert=require('node:assert/strict');
async function inspect(directory){
  const read=name=>JSON.parse(fs.readFileSync(path.join(directory,'metadata',name),'utf8'));
  const session=read('session.json'),end=read('end.json');
  assert.equal(session.schema,'client_dataset_session/v1');
  const owners={},counts={frames:0,menu_frames:0,block_snapshots:0,tick_gaps:0,attack_attempts:0};let previous;
  for await(const line of readline.createInterface({input:fs.createReadStream(path.join(directory,'observations.jsonl')),crlfDelay:Infinity})){
    const f=JSON.parse(line);assert.equal(f.schema,'client_dataset_frame/v1');assert(Number.isSafeInteger(f.tick));
    if(previous!==undefined){assert(f.tick>previous,'non-increasing ticks');counts.tick_gaps+=f.tick-previous-1;}previous=f.tick;
    assert(Object.values(f.keys).every(x=>typeof x==='boolean'));assert(Number.isFinite(f.yaw_delta)&&Number.isFinite(f.pitch_delta));
    const own=f.observation.own;assert(Number.isFinite(own.health));assert(own.selected_slot>=0&&own.selected_slot<=8);
    assert(!Object.hasOwn(f.observation,'server_setup_feedback'),'server feedback in observations');
    for(const e of f.observation.loaded_entities)assert(!Object.hasOwn(e,'health'),'hidden entity health');
    assert.equal(new Set(own.inventory.map(i=>i.slot)).size,own.inventory.length);
    counts.frames++;counts.menu_frames+=Number(f.menu_open);counts.block_snapshots+=Number(!!f.observation.local_blocks);
    counts.attack_attempts+=f.client_attack_attempts;owners[f.input_owner]=(owners[f.input_owner]||0)+1;
  }
  assert.equal(counts.frames+end.dropped_frames,end.frames,'frame count differs from manifest');
  const splits=['training','validation','evaluation'];
  const participant=session.participant_split,map=session.scenario?.scenario?.split;
  assert(splits.includes(participant));if(map)assert(splits.includes(map));
  return {schema:'client_dataset_inspection/v1',directory:path.resolve(directory),label:session.label,
    data_kind:session.data_kind,mode:session.scenario?.scenario?.mode?.id??session.declared_mode??null,collection_task:session.collection_task??'unassigned',effective_split:splits[Math.max(splits.indexOf(participant),splits.indexOf(map))],
    geometry_sha256:session.scenario?.scenario?.geometry_sha256??null,...counts,input_owners:owners,
    structurally_complete:!!end.complete&&counts.frames>0&&counts.tick_gaps===0,
    expert_demonstration:false,on_policy_rl_eligible:false,
    note:'Raw capture only. Quality, action alignment and outcomes require review before training.'};
}
if(require.main===module)inspect(process.argv[2]).then(x=>console.log(JSON.stringify(x,null,2))).catch(e=>{console.error(e.message);process.exitCode=1;});
module.exports={inspect};
