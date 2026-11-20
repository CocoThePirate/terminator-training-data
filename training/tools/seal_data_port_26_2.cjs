'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {compileMode}=require('./arena_scenarios.cjs');
const sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function seal(root){
  root=path.resolve(root);const read=name=>JSON.parse(fs.readFileSync(path.join(root,name),'utf8'));
  const manifest=read('manifest.json'),native=read('runtime-validation.json'),client=read('client-validation.json');
  assert(native.passed&&client.passed,'Native checks must pass before sealing');
  assert.equal(native.maps.length,manifest.maps.length);assert.equal(native.native_checks.kits_verified.length,manifest.kits.length);
  const helper=sha(path.join(root,'terminator-arena-26.2.jar'));assert.equal(client.helper_sha256,helper,'Client capture used a different helper build');
  for(const s of manifest.scenarios)assert.equal(compileMode(s.mode,s.seed,s.map).scenario.configuration_sha256,s.source_configuration_sha256,'Source catalog changed; regenerate and revalidate the port');
  fs.copyFileSync(path.resolve(__dirname,'../scenarios/catalog.json'),path.join(root,'catalog-source.json'));
  assert(client.captures.every(c=>c.structurally_complete&&c.frames>0&&c.data_kind==='infrastructure_test'));
  const files=[];function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const f=path.join(dir,e.name);if(e.isDirectory())walk(f);else files.push({path:path.relative(root,f).split(path.sep).join('/'),sha256:sha(f)});}}
  walk(path.join(root,'datapack'));walk(path.join(root,'scenarios'));
  manifest.artifacts={helper_sha256:helper,source_catalog_sha256:sha(path.join(root,'catalog-source.json')),generated_files:files};
  manifest.client_recorder={artifact:'terminator-arena-26.2.jar',runtime_validation:'passed_native_26.2_client',schemas:['client_dataset_session/v1','client_dataset_frame/v1'],report:'client-validation.json',frames:client.captures.reduce((n,c)=>n+c.frames,0),collection_profile_only:true,combat_policy_ported:false,hotkey_control_takeover_ported:false};
  manifest.sealed_utc=new Date().toISOString();fs.writeFileSync(path.join(root,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
  fs.writeFileSync(path.join(root,'data-compatibility.json'),JSON.stringify({schema:'terminator_data_compatibility/v1',target_game:'26.2',
    historical_source:'1.21.4',historical_files_rewritten:false,historical_trajectory_physics_equivalence:'not_established',
    historical_use:'Diagnosis and explicitly reviewed offline datasets; retain source version and behavior provenance.',
    native_target_capture:'Available through the passive 26.2 recorder; human captures remain unrated until reviewed.',
    automatically_expert:false,automatically_on_policy_rl_eligible:false,
    actor_observations:'Client-visible state only; privileged setup/reset/validation feedback stays separate.',
    runtime_reports:['runtime-validation.json','native-check-report.json','client-validation.json'],
    original_one_account_sparring:'1.21.4 launcher and checkpoints retained; neural sparring/controller not migrated by this data port.'},null,2)+'\n');
  fs.writeFileSync(path.join(root,'START-HERE.txt'),'Minecraft Java 26.2 training-data port\n\nKits: '+manifest.kits.length+'\nMap variants: '+manifest.maps.length+'\nMode configurations: '+manifest.scenarios.length+'\n\nNative server and client capture checks passed. The helper mod and data pack are data-collection infrastructure; no fighter checkpoint was trained or migrated. Historical trajectories retain source-game physics and must not be relabeled as native 26.2 experience.\n\nLaunch on this prepared PC: outputs/Start-TerminatorData26.ps1 -Mode mole -ParticipantId local-player\nRecord in chat: !terminator record start LABEL / !terminator record stop\nNew captures: ../incoming/26.2\nProject instructions: work/terminator/docs/DATA-LIBRARY-26.2.md\n\nThe launcher creates a private, authentication-disabled development server bound only to 127.0.0.1. It is not a remotely authenticated service. The original 1.21.4 sparring workflow remains available.\n');
  console.log(JSON.stringify({sealed:true,helper_sha256:helper,kits:manifest.kits.length,maps:manifest.maps.length,scenarios:manifest.scenarios.length}));
}
if(require.main===module)seal(process.argv[2]);module.exports={seal};
