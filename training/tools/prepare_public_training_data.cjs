'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),readline=require('node:readline'),{once}=require('node:events');
const personKey=/^(?:participant_id|player_id|username|java_username|target_name|player_name|opponent_name|actor_name|guest_name|host_name|human_name)$/i;
const secretKey=/^(?:access_token|refresh_token|password|api_key|client_secret|authorization|cookie|session_token|private_key)$/i;
const uuid=/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi;
const localPath=/(?:[A-Za-z]:[\\/]|\\\\|\/(?:Users|home|mnt|tmp)\/)/;
const email=/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi;
const ip=/\b(?:\d{1,3}\.){3}\d{1,3}\b/g;
const token=/\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,})\b/g;
function sanitizer(key,names){
  const mac=s=>crypto.createHmac('sha256',key).update(s.toLowerCase()).digest('hex');
  const identity=s=>'actor_'+mac(s).slice(0,10);
  const namesRegex=names.size?new RegExp('(?<![A-Za-z0-9_])(?:'+[...names].sort((a,b)=>b.length-a.length).map(s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('|')+')(?![A-Za-z0-9_])','gi'):null;
  const text=s=>{
    if(localPath.test(s))return '[redacted_local_path]';
    s=s.replace(email,'[redacted_email]').replace(ip,'[redacted_ip]').replace(token,'[redacted_secret]');
    s=s.replace(uuid,x=>{const h=mac(x);return [h.slice(0,8),h.slice(8,12),h.slice(12,16),h.slice(16,20),h.slice(20,32)].join('-');});
    return namesRegex&&!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(s)?s.replace(namesRegex,identity):s;
  };
  function clean(value,keyName=''){
    if(secretKey.test(keyName)&&value!==null)return '[redacted_secret]';
    if(typeof value==='string'){
      if(personKey.test(keyName)&&names.has(value.toLowerCase()))return identity(value);
      if(/uuid/i.test(keyName)&&/^[0-9a-f]{32}$/i.test(value))return mac(value).slice(0,32);
      if(/^(?:schema|game_profile|policy_profile|mode|split|participant_split|effective_split|item|block|type|dimension|input_owner|phase|strategy)$/.test(keyName))return value;
      return text(value);
    }
    if(Array.isArray(value))return value.map(v=>clean(v));
    if(value&&typeof value==='object'){
      if(Object.keys(value).length===1&&(Array.isArray(value.Windows)||Array.isArray(value.Unix)))return '[redacted_local_path]';
      return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,clean(v,k)]));
    }
    return value;
  }
  return {clean,text};
}
async function rows(file,callback){
  if(file.endsWith('.jsonl')){for await(const line of readline.createInterface({input:fs.createReadStream(file),crlfDelay:Infinity}))if(line.trim())await callback(JSON.parse(line));}
  else await callback(JSON.parse(fs.readFileSync(file,'utf8')));
}
async function prepare(library,destination,privateKeyFile,archives){
  library=path.resolve(library);destination=path.resolve(destination);if(fs.existsSync(destination))throw Error('Choose a fresh public-data directory');
  const source=JSON.parse(fs.readFileSync(path.join(library,'manifest.json'))),objects=new Map();
  for(const entry of source.entries){if(!/\.(jsonl|json)$/i.test(entry.path))continue;const key=entry.sha256+path.extname(entry.path);if(!objects.has(key))objects.set(key,{entry,file:path.join(library,entry.path),aliases:[]});objects.get(key).aliases.push(entry.source);}
  function collectArchive(dir){if(!dir||!fs.existsSync(dir))return;for(const e of fs.readdirSync(dir,{withFileTypes:true})){const f=path.join(dir,e.name);if(e.isDirectory())collectArchive(f);else if(/\.(json|jsonl)$/i.test(f)){const sha=crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex'),key=sha+path.extname(f),alias='historical-archives/'+path.relative(archives,f).split(path.sep).join('/');if(!objects.has(key))objects.set(key,{entry:{sha256:sha,kind:'historical_archive_member',source_game_version:'1.21.4',version_basis:'archive_context_inference',mode:null,effective_split:null},file:f,aliases:[]});objects.get(key).aliases.push(alias);}}}
  if(archives){archives=path.resolve(archives);collectArchive(archives);}
  const names=new Set(),reserved=new Set(['terminator','dataplayer','portprobe','local-player','unassigned','host','guest','player','human','bot','opponent','actor','learner','unknown','none','training','validation','evaluation','scripted','baseline','candidate']);
  function learn(x){if(!x||typeof x!=='object')return;for(const [k,v] of Object.entries(x)){if(personKey.test(k)&&typeof v==='string'&&v.length>=3&&!reserved.has(v.toLowerCase())&&!v.includes('minecraft:'))names.add(v.toLowerCase());learn(v);}}
  let n=0;for(const {file}of objects.values()){await rows(file,learn);if(++n%200===0)console.log(JSON.stringify({privacy_inventory:n,total:objects.size}));}
  fs.mkdirSync(path.dirname(privateKeyFile),{recursive:true});if(!fs.existsSync(privateKeyFile))fs.writeFileSync(privateKeyFile,crypto.randomBytes(32),{flag:'wx'});
  const {clean,text}=sanitizer(fs.readFileSync(privateKeyFile),names);fs.mkdirSync(path.join(destination,'objects'),{recursive:true});
  const entries=[],publicObjects=new Set();n=0;
  for(const item of objects.values()){
    const extension=path.extname(item.file),temporary=path.join(destination,'objects','pending-'+n+extension),writer=fs.createWriteStream(temporary),hash=crypto.createHash('sha256');let count=0,bytes=0;
    await rows(item.file,async row=>{const line=JSON.stringify(clean(row))+'\n';hash.update(line);bytes+=Buffer.byteLength(line);if(!writer.write(line))await once(writer,'drain');count++;});writer.end();await once(writer,'finish');
    const sha=hash.digest('hex'),relative='objects/'+sha+extension,target=path.join(destination,relative);
    if(publicObjects.has(relative))fs.unlinkSync(temporary);else{fs.renameSync(temporary,target);publicObjects.add(relative);}
    entries.push({path:relative,sha256:sha,source_sha256:item.entry.sha256,bytes,rows:extension==='.jsonl'?count:null,kind:item.entry.kind,source_game_version:item.entry.source_game_version,version_basis:item.entry.version_basis,
      mode:item.entry.mode??null,effective_split:item.entry.effective_split??null,aliases:item.aliases.map(text),expert_demonstration:false,on_policy_rl_eligible:false,actor_input_review_required:true});
    if(++n%200===0)console.log(JSON.stringify({sanitized:n,total:objects.size}));
  }
  const manifest={schema:'terminator_public_dataset/v1',source_snapshot:source.snapshot,created_utc:new Date().toISOString(),pseudonymized:true,original_local_files_modified:false,
    summary:{source_aliases:entries.reduce((n,e)=>n+e.aliases.length,0),source_objects:entries.length,published_objects:publicObjects.size,published_bytes:[...publicObjects].reduce((n,p)=>n+fs.statSync(path.join(destination,p)).size,0),jsonl_rows:entries.reduce((n,e)=>n+(e.rows??0),0)},
    redactions:['Local absolute paths and serialized OS paths','Player/participant names and IDs pseudonymized with a private HMAC key','Entity UUIDs pseudonymized consistently','Email, IP address and credential-shaped values removed'],
    caveats:['Pseudonymization is not a guarantee of anonymity.','Infrastructure tests and bot traces are not expert human demonstrations.','1.21.4 trajectories retain 1.21.4 physics labels.','Archive containers excluded; their JSON/JSONL members were imported and deduplicated.','Unique byte objects and JSONL rows are not counts of unique fights.'],entries};
  fs.writeFileSync(path.join(destination,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
  console.log(JSON.stringify({public_data:destination,summary:manifest.summary,pseudonymized_identity_count:names.size}));return manifest;
}
if(require.main===module)prepare(...process.argv.slice(2)).catch(e=>{console.error(e.stack);process.exitCode=1;});
module.exports={sanitizer,prepare};
