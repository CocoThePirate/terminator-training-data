'use strict';
const fs=require('node:fs'),path=require('node:path'),net=require('node:net'),crypto=require('node:crypto'),{spawn}=require('node:child_process');
const {catalog}=require('./arena_scenarios.cjs');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
const files=(root)=>fs.existsSync(root)?fs.readdirSync(root,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(path.join(root,e.name)):e.name.endsWith('.jar')?[path.join(root,e.name)]:[]):[];
function javaArgs(file,args){fs.writeFileSync(file,args.map(a=>'"'+String(a).replaceAll('\\','\\\\').replaceAll('"','\\"')+'"').join('\n')+'\n');}
async function launch(config){
  const repo=path.resolve(__dirname,'../..'),tools=path.resolve(repo,'../toolchains'),portTools=path.join(tools,'data-port-26.2'),library=path.resolve(repo,'../../Training-Data');
  const target=path.join(library,'26.2'),manifest=JSON.parse(fs.readFileSync(path.join(target,'manifest.json'))),c=catalog();
  if(!c.modes[config.mode])throw Error('Unknown mode');const map=config.map??c.modes[config.mode].map,seed=config.seed??0;
  if(!Number.isInteger(seed)||seed<0||seed>3)throw Error('Use a seed reflection 0 through 3');
  const scenario=manifest.scenarios.find(s=>s.mode===config.mode&&s.map===map&&s.seed===seed);if(!scenario)throw Error('Unsupported mode/map');
  const username=config.username??'DataPlayer';if(!/^[A-Za-z0-9_]{3,16}$/.test(username))throw Error('Invalid development username');
  const port=config.port??48826;if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Invalid local port');
  await new Promise((resolve,reject)=>{const probe=net.createServer();probe.once('error',reject);probe.listen(port,'127.0.0.1',()=>probe.close(resolve));});
  const runtime=path.join(portTools,'server-runtime'),eula=path.join(runtime,'eula.txt');if(!fs.existsSync(eula)||!/^eula=true$/m.test(fs.readFileSync(eula,'utf8')))throw Error('Explicit EULA acceptance required before running the private server');
  const session=path.join(portTools,'data-sessions',crypto.randomUUID()),profile=path.join(session,'client'),worlds=path.join(session,'worlds');
  fs.mkdirSync(path.join(profile,'mods'),{recursive:true});fs.mkdirSync(path.join(worlds,'arena/datapacks'),{recursive:true});
  fs.cpSync(path.join(target,'datapack/terminator_training'),path.join(worlds,'arena/datapacks/terminator_training'),{recursive:true});
  fs.copyFileSync(path.join(target,'terminator-arena-26.2.jar'),path.join(profile,'mods/terminator-arena-26.2.jar'));
  fs.copyFileSync(path.join(target,'terminator-arena-26.2.jar'),path.join(runtime,'mods/terminator-arena-26.2.jar'));
  fs.writeFileSync(path.join(runtime,'server.properties'),['server-ip=127.0.0.1','server-port='+port,'online-mode=false','enable-rcon=false','enable-query=false','spawn-protection=0','view-distance=3','simulation-distance=5','pause-when-empty-seconds=-1','max-tick-time=120000','level-type=minecraft:flat','generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","lakes":false,"features":false,"structure_overrides":[]}', 'initial-enabled-packs=vanilla,file/terminator_training'].join('\n')+'\n');
  fs.writeFileSync(path.join(profile,'options.txt'),['onboardAccessibility:false','pauseOnLostFocus:false','narrator:0','renderDistance:3','simulationDistance:5','maxFps:60'].join('\n')+'\n');
  const java=path.join(tools,'jdk-25.0.5.0.2+1/bin/java.exe');
  const incoming=path.join(library,'incoming/26.2');fs.mkdirSync(incoming,{recursive:true});const previous=new Set(fs.readdirSync(incoming));
  const serverLog=fs.createWriteStream(path.join(session,'server.log'));let serverText='',serverDone=false,client,clientDone=false,clientCode=null,joined=false;
  const server=spawn(java,['-Dterminator.trainingArena=true','-Xmx2G','-jar','fabric-server-launch.jar','--universe',worlds,'--world','arena','nogui'],{cwd:runtime,windowsHide:true,stdio:['pipe','pipe','pipe']});
  const receive=data=>{const text=data.toString();serverText+=text;serverLog.write(text);if(!joined&&serverText.includes(username+' joined the game')){joined=true;server.stdin.write('op '+username+'\nexecute as @a[name='+username+'] run function terminator:roles/host\nfunction '+scenario.apply_function+'\n');}};
  server.stdout.on('data',receive);server.stderr.on('data',receive);server.once('exit',()=>serverDone=true);
  try{
    for(let i=0;i<240;i++){if(serverDone)throw Error('Server failed; see '+session);if(serverText.includes('Done ('))break;if(i===239)throw Error('Server startup timed out');await delay(500);}
    server.stdin.write('forceload add -1 -1 49 49\n');await delay(2500);
    const classpath=[path.join(portTools,'client.jar'),...files(path.join(portTools,'client-libraries')),...files(path.join(runtime,'libraries/net/fabricmc')),...files(path.join(runtime,'libraries/org/ow2/asm')),...(config.test?[path.join(tools,'headless-agent.jar')]:[])].join(path.delimiter);
    const output=path.join(library,'incoming/26.2');fs.mkdirSync(output,{recursive:true});
    const args=['-Xmx2G','-Dfabric.gameVersion=26.2','-Dfabric.gameJarPath='+path.join(portTools,'client.jar'),'-Dterminator.dataset.output='+output,
      '-Dterminator.dataset.mode='+config.mode,'-Dterminator.dataset.participant='+(config.participant??'local-player'),'-Dterminator.dataset.participantSplit='+(config.split??'training'),'-Dterminator.dataset.setup='+path.join(target,'scenarios',scenario.key+'.json')];
    if(config.test)args.push('-Dterminator.dataset.test=true','-Dterminator.dataset.expectItem='+c.kits[c.modes[config.mode].kit].items[0]?.item,
      '-javaagent:'+path.join(tools,'headless-agent.jar'),'-Djoml.nounsafe=true');
    args.push('-cp',classpath,'net.fabricmc.loader.impl.launch.knot.KnotClient','--username',username,'--version','26.2','--gameDir',profile,'--assetsDir',path.join(portTools,'client-assets'),'--assetIndex','32','--accessToken','0','--uuid',crypto.randomUUID().replaceAll('-',''),'--userType','legacy','--versionType','release','--quickPlayMultiplayer','127.0.0.1:'+port);
    const argFile=path.join(session,'client-args.txt');javaArgs(argFile,args);const clientLog=fs.createWriteStream(path.join(session,'client.log'));
    client=spawn(java,['@'+argFile],{cwd:profile,windowsHide:true,stdio:['ignore','pipe','pipe']});client.stdout.pipe(clientLog,{end:false});client.stderr.pipe(clientLog,{end:false});client.once('exit',code=>{clientDone=true;clientCode=code;clientLog.end();});
    console.log(JSON.stringify({session,mode:config.mode,map,port,output,authentication:'offline development profile; localhost only'}));
    let seconds=0;while(!clientDone){if(serverDone)throw Error('Server stopped before client');if(config.test&&seconds++>180)throw Error('Client verification timed out; see '+path.join(session,'client.log'));await delay(1000);}
    if(clientCode!==0)throw Error('Client exited with code '+clientCode+'; see '+path.join(session,'client.log'));
    if(config.test&&!joined)throw Error('Client did not join; see '+path.join(session,'client.log'));
    if(config.test){
      const captures=[];for(const id of fs.readdirSync(incoming).filter(id=>!previous.has(id))){const dir=path.join(incoming,id),meta=JSON.parse(fs.readFileSync(path.join(dir,'metadata/session.json')));if(meta.minecraft!=='26.2'||meta.data_kind!=='infrastructure_test')continue;const result=await require('./inspect_client_dataset.cjs').inspect(dir);if(!result.structurally_complete||result.frames!==60)throw Error('Incomplete target client capture');captures.push(result);}
      if(captures.length!==1)throw Error('Expected one complete native 26.2 capture');
      const digest=crypto.createHash('sha256').update(fs.readFileSync(path.join(target,'terminator-arena-26.2.jar'))).digest('hex');
      fs.writeFileSync(path.join(target,'client-validation.json'),JSON.stringify({schema:'terminator_native_client_validation/v1',minecraft:'26.2',passed:true,helper_sha256:digest,captures,session,human_gameplay_test:false},null,2)+'\n');
      console.log(JSON.stringify({native_client_capture_passed:true,frames:captures[0].frames}));
    }
    return {session,output};
  }finally{
    if(client&&!clientDone)client.kill();if(!serverDone){server.stdin.write('stop\n');for(let i=0;i<120&&!serverDone;i++)await delay(250);if(!serverDone)server.kill();}serverLog.end();
  }
}
if(require.main===module)launch(JSON.parse(fs.readFileSync(process.argv[2],'utf8'))).catch(e=>{console.error(e.stack);process.exitCode=1;});
module.exports={launch,javaArgs};
