'use strict';
const fs=require('node:fs'),path=require('node:path'),{spawn}=require('node:child_process'),assert=require('node:assert/strict');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function verify(java,runtime,exportRoot){
  java=path.resolve(java);runtime=path.resolve(runtime);exportRoot=path.resolve(exportRoot);
  const manifest=JSON.parse(fs.readFileSync(path.join(exportRoot,'manifest.json'))),report=path.join(runtime,'native-check-'+require('node:crypto').randomUUID()+'.json');
  const worlds=path.join(runtime,'native-validation-worlds',require('node:crypto').randomUUID());
  fs.mkdirSync(path.join(worlds,'arena/datapacks'),{recursive:true});fs.cpSync(path.join(exportRoot,'datapack/terminator_training'),path.join(worlds,'arena/datapacks/terminator_training'),{recursive:true});
  fs.copyFileSync(path.join(exportRoot,'terminator-arena-26.2.jar'),path.join(runtime,'mods/terminator-arena-26.2.jar'));
  const args=['-Dterminator.trainingArena=true','-Dterminator.portCheck='+path.join(exportRoot,'native-check-config.json'),'-Dterminator.portReport='+report,'-Xmx2G','-jar','fabric-server-launch.jar','--universe',worlds,'--world','arena','nogui'];
  const logFile=path.join(exportRoot,'server-validation.log'),log=fs.createWriteStream(logFile),child=spawn(java,args,{cwd:runtime,windowsHide:true,stdio:['pipe','pipe','pipe']});
  let output='',exited=false,pending=null,buf='';child.once('exit',()=>{exited=true;});
  const receive=data=>{const s=data.toString();output+=s;log.write(s);buf+=s;let n;while((n=buf.indexOf('\n'))>=0){const line=buf.slice(0,n);buf=buf.slice(n+1);if(pending){pending.lines.push(line);if(line.includes(pending.marker)){const p=pending;pending=null;p.resolve(p.lines);}}}};
  child.stdout.on('data',receive);child.stderr.on('data',receive);
  child.on('error',e=>{if(pending){pending.reject(e);pending=null;}});
  async function batch(commands){if(exited)throw new Error('Server exited');const marker='PORT_BARRIER_'+require('node:crypto').randomUUID();return new Promise((resolve,reject)=>{const timer=setTimeout(()=>{pending=null;reject(new Error('Command batch timed out'));},90000);pending={marker,lines:[],resolve:x=>{clearTimeout(timer);resolve(x);},reject};child.stdin.write(commands.join('\n')+'\nsay '+marker+'\n');});}
  const cases=[];
  try{
    for(let i=0;i<240;i++){if(exited)throw new Error('Server exited during startup; inspect '+logFile);if(output.includes('Done ('))break;if(i===239)throw new Error('Server startup timeout');await delay(500);}
    for(let i=0;i<60&&!fs.existsSync(report);i++)await delay(250);
    const native=JSON.parse(fs.readFileSync(report));assert(native.passed,JSON.stringify(native));
    fs.copyFileSync(report,path.join(exportRoot,'native-check-report.json'));
    assert(!/Failed to load function|Failed to parse|Couldn't load tag|Couldn't load data pack/i.test(output),'Data pack did not load cleanly');
    await batch(['forceload add -1 -1 49 49']);await delay(2500);
    for(const m of manifest.maps){
      const lines=await batch(['setblock 2 50 2 minecraft:cobblestone','summon minecraft:arrow 3 50 3','function '+m.function,'function '+m.verify_function,
        'execute unless block 2 50 2 minecraft:air run data modify storage terminator:validation failures append value "high_block_not_reset"',
        'execute if entity @e[type=minecraft:arrow,x=-1,y=-64,z=-1,dx=51,dy=383,dz=51] run data modify storage terminator:validation failures append value "projectile_not_reset"',
        'data get storage terminator:validation failures']);
      const results=lines.filter(l=>l.includes('contents:'));
      assert(results.some(l=>/contents: \[\]/.test(l)),'Map verification failed '+m.key+': '+lines.slice(-8).join('\n'));
      assert(!lines.some(l=>/Unknown or incomplete command|Unknown function|Failed to execute function|An unexpected error occurred/i.test(l)),'Command failure '+m.key);
      cases.push({key:m.key,passed:true,block_checks:m.checks,high_block_reset:true,projectile_reset:true});
      if(cases.length%8===0)console.log(JSON.stringify({maps_verified:cases.length,total:manifest.maps.length}));
    }
    const result={schema:'terminator_target_runtime_validation/v1',minecraft:'26.2',passed:true,native_checks:native,maps:cases,human_gameplay_test:false};
    fs.writeFileSync(path.join(exportRoot,'runtime-validation.json'),JSON.stringify(result,null,2)+'\n');
    manifest.runtime_validation='passed_native_server';manifest.validation_scope='Native map and kit components, reset parity and fireball adapter; no human gameplay test.';for(const s of manifest.scenarios){s.runtime_validated=cases.some(m=>m.key===s.map+'_'+s.seed);s.scenario_roles_human_tested=false;}
    fs.writeFileSync(path.join(exportRoot,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
    console.log(JSON.stringify({passed:true,kits:native.kits_verified.length,maps:cases.length,scenarios:manifest.scenarios.length}));
  }finally{
    if(!exited){child.stdin.write('stop\n');for(let i=0;i<120&&!exited;i++)await delay(250);if(!exited)child.kill();}
    log.end();
  }
}
if(require.main===module)verify(...process.argv.slice(2)).catch(e=>{console.error(e.stack);process.exitCode=1;});
module.exports={verify};
