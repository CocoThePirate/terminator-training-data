const test=require('node:test'),assert=require('node:assert/strict');
const {sanitizer}=require('./prepare_public_training_data.cjs');
test('pseudonyms preserve cross-frame identity and protect local paths',()=>{
  const {clean}=sanitizer(Buffer.alloc(32,7),new Set(['testperson']));
  const row={player_id:'TestPerson',target_name:'testperson',message:'hit TestPerson',checkpoint_path:{Windows:[67,58,92]},uuid:'d9f995ea-37dd-4d15-b351-2ac71fcd6c1e',email:'example@example.com'};
  const a=clean(row),b=clean(row);assert.deepEqual(a,b);assert.equal(a.player_id,a.target_name);assert(!JSON.stringify(a).includes('TestPerson'));assert.equal(a.checkpoint_path,'[redacted_local_path]');assert.equal(a.email,'[redacted_email]');assert.notEqual(a.uuid,row.uuid);
});
test('human identifiers cannot corrupt resource IDs or semantic mode/owner labels',()=>{
  const {clean}=sanitizer(Buffer.alloc(32,9),new Set(['bow','minecraft','human']));
  const row={player_id:'bow',item:'minecraft:bow',block:'minecraft:stone',mode:'bow',input_owner:'human',count:64,position:[3.5,-48,24.5],keys:{forward:true}};
  const a=clean(row);assert.notEqual(a.player_id,'bow');for(const k of ['item','block','mode','input_owner','count','position','keys'])assert.deepEqual(a[k],row[k]);
});
