'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {describe}=require('./data_library.cjs');const {fill,expected,slot}=require('./export_scenarios_26_2.cjs');const {compileMode}=require('./arena_scenarios.cjs');
test('historical experience retains source physics and no automatic target-RL eligibility',()=>{
  const a=describe({game_profile:'java-1.21.4-fabric-0.16.10-vanilla'},'outputs/spar-recordings/a.jsonl',null);
  assert.equal(a.source_game_version,'1.21.4');assert.equal(a.target_game_version,'26.2');assert.equal(a.kind,'human_spar_bot_trace');assert.equal(a.on_policy_rl_eligible,false);assert.equal(a.expert_demonstration,false);assert.equal(a.target_physics_validated,false);
  const b=describe(null,'raw/observations.jsonl',{minecraft:'26.2',data_kind:'infrastructure_test'});assert.equal(b.source_game_version,'26.2');assert.equal(b.kind,'infrastructure_test');assert.equal(b.expert_demonstration,false);
  assert.equal(describe({depends:{minecraft:'26.2'}},'fabric.mod.json',null).source_game_version,'26.2');
});
test('large map fills honor vanilla command limits without gaps',()=>{
  const lines=fill([-1,-64,-1],[49,319,49],'minecraft:air');let next=-64,total=0;
  for(const line of lines){const a=line.split(' ');const [x,y,z,X,Y,Z]=a.slice(1,7).map(Number);assert.equal(y,next);const n=(X-x+1)*(Y-y+1)*(Z-z+1);assert(n<=32768);total+=n;next=Y+1;}
  assert.equal(total,51*51*384);assert.equal(next,320);
});
test('portable equipment slots and tunnel/void geometry keep source meaning',()=>{
  assert.equal(slot(8),'hotbar.8');assert.equal(slot(9),'inventory.0');assert.equal(slot(35),'inventory.26');assert.equal(slot(40),'weapon.offhand');
  const m=compileMode('mole').scenario;assert.equal(expected(m,3,-48,16),'minecraft:air');assert.equal(expected(m,3,-47,14),'minecraft:stone');
  const b=compileMode('bridge').scenario;assert.equal(expected(b,5,-49,20),'minecraft:white_wool');assert.equal(expected(b,5,-64,20),'minecraft:air');
});
