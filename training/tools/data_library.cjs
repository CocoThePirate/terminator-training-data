'use strict';
// Append-only, local snapshots. Never follows embedded paths or directory links.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),readline=require('node:readline');
const {inspect}=require('./inspect_client_dataset.cjs');
const repo=path.resolve(__dirname,'../..'),workspace=path.resolve(repo,'../..');
const digest=async file=>{const h=crypto.createHash('sha256');for await(const b of fs.createReadStream(file))h.update(b);return h.digest('hex');};
const excluded=/^(?:\.git|\.gradle|node_modules|target|build|caches|gradle-home|saves|assets|libraries|versions|natives|downloads|crash-reports|data-port-26\.2|jdk.*|rust.*|cargo.*|gradle-.*|baritone-.*)$/i;
function walk(dir){if(!fs.existsSync(dir))return [];const files=[];for(const e of fs.readdirSync(dir,{withFileTypes:true})){if(e.isSymbolicLink())continue;const p=path.join(dir,e.name);if(e.isDirectory()){if(!excluded.test(e.name))files.push(...walk(p));}else if(/\.(jsonl|json|csv|tsv)$/i.test(e.name)&&!['usercache.json','log.json','version-metadata.json'].includes(e.name))files.push(p);else if(/\.zip$/i.test(e.name)&&/(bout|experiment|training)/i.test(e.name))files.push(p);}return files;}
function describe(first,relative,session){
  let kind='configuration_or_report',version=first?.minecraft??first?.source_game_version??first?.depends?.minecraft??null,versionBasis=version?'explicit':'unknown';
  const profile=first?.game_profile??first?.scenario?.game_profile;
  if(typeof profile==='string'){const m=profile.match(/java-(1\.\d+\.\d+|26\.\d+)/);if(m){version=m[1];versionBasis='explicit_profile';}}
  if(/checkpoint|candidate/i.test(relative)||first?.tree||first?.weights||first?.checkpoint_schema)kind='checkpoint_provenance';
  if(/\.jsonl$/i.test(relative))kind='unreviewed_experience';
  if(/spar-recordings/i.test(relative))kind='human_spar_bot_trace';
  if(/navigation-events|navigation-status/i.test(relative))kind='diagnostic_events';
  if(session){version=session.minecraft;versionBasis='session_metadata';kind=session.data_kind==='infrastructure_test'?'infrastructure_test':'unrated_client_experience';}
  if(session&&/\/metadata\/(session|end)\.json$/i.test(relative))kind='dataset_metadata';
  if(/\.zip$/i.test(relative))kind='historical_archive';
  if(!version){version='1.21.4';versionBasis='project_context_inference';}
  return {kind,source_game_version:version,version_basis:versionBasis,mode:session?.scenario?.scenario?.mode?.id??session?.declared_mode??null,
    participant_split:session?.participant_split??null,expert_demonstration:false,on_policy_rl_eligible:false,
    target_game_version:'26.2',native_target_capture_declared:version==='26.2'&&versionBasis==='session_metadata',target_physics_validated:false,actor_input_review_required:true};
}
const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function build(destination=path.join(workspace,'Training-Data')){
  const root=path.resolve(destination);if(!root.startsWith(workspace+path.sep))throw new Error('Library must remain within the current current workspace');
  fs.mkdirSync(root,{recursive:true});const id=new Date().toISOString().replace(/[:.]/g,'-')+'-'+crypto.randomUUID().slice(0,8);
  const snapshot=path.join(root,'snapshots',id);fs.mkdirSync(snapshot,{recursive:true});
  const sources=[['outputs',path.resolve(repo,'../../outputs')],['experiments',path.resolve(repo,'../toolchains')],['repository-training',path.join(repo,'training')],['incoming',path.join(root,'incoming')]];
  const entries=[],skipped=[],inspections=new Map(),byHash=new Map();let logicalBytes=0,physicalBytes=0;
  for(const [label,sourceRoot] of sources)for(const file of walk(sourceRoot).sort()){
    const relative=label+'/'+path.relative(sourceRoot,file).split(path.sep).join('/');
    const stat=fs.statSync(file);let sha=await digest(file);
    const after=fs.statSync(file);if(after.size!==stat.size||after.mtimeMs!==stat.mtimeMs){skipped.push({source:relative,reason:'source_changed_during_snapshot'});continue;}
    const objectRelative='objects/'+sha.slice(0,2)+'/'+sha+path.extname(file).toLowerCase(),object=path.join(root,objectRelative);
    if(!byHash.has(objectRelative)){
      fs.mkdirSync(path.dirname(object),{recursive:true});
      if(!fs.existsSync(object))fs.copyFileSync(file,object,fs.constants.COPYFILE_EXCL);
      if(await digest(object)!==sha)throw new Error('Object checksum mismatch: '+relative);
      fs.chmodSync(object,0o444);byHash.set(objectRelative,true);physicalBytes+=stat.size;
    }
    const view=path.join(snapshot,'source',relative);fs.mkdirSync(path.dirname(view),{recursive:true});
    try{fs.linkSync(object,view);}catch{fs.copyFileSync(object,view,fs.constants.COPYFILE_EXCL);fs.chmodSync(view,0o444);}
    let first=null,rows=null,invalidRows=0,session=null,inspection=null;
    const sessionDir=path.basename(path.dirname(file))==='metadata'?path.dirname(path.dirname(file)):path.dirname(file),sessionPath=path.join(sessionDir,'metadata/session.json');
    if(fs.existsSync(sessionPath))try{session=JSON.parse(fs.readFileSync(sessionPath,'utf8'));}catch{}
    if(/\.jsonl$/i.test(file)){
      rows=0;for await(const line of readline.createInterface({input:fs.createReadStream(object),crlfDelay:Infinity})){if(!line.trim())continue;rows++;try{const x=JSON.parse(line);if(!first)first=x;}catch{invalidRows++;}}
      if(session){if(!inspections.has(sessionDir)){try{inspections.set(sessionDir,await inspect(sessionDir));}catch(e){inspections.set(sessionDir,{structurally_complete:false,error:e.message});}}inspection=inspections.get(sessionDir);}
    }else if(/\.json$/i.test(file)){try{first=JSON.parse(fs.readFileSync(object,'utf8'));}catch{invalidRows=1;}}
    const entry={source:relative,path:path.relative(root,view).split(path.sep).join('/'),object:objectRelative,sha256:sha,bytes:stat.size,rows,invalid_rows:invalidRows,
      schema:first?.schema??first?.type??null,...describe(first,relative,session),inspection};
    if(inspection?.effective_split)entry.effective_split=inspection.effective_split;
    entries.push(entry);logicalBytes+=stat.size;
    if(entries.length%100===0)process.stdout.write(JSON.stringify({indexed:entries.length})+'\n');
  }
  const summary={files:entries.length,unique_objects:byHash.size,logical_bytes:logicalBytes,unique_bytes:physicalBytes,skipped:skipped.length,
    by_kind:entries.reduce((a,e)=>(a[e.kind]=(a[e.kind]||0)+1,a),{}),jsonl_rows:entries.reduce((n,e)=>n+(e.rows||0),0),invalid_jsonl_files:entries.filter(e=>e.rows!==null&&e.invalid_rows).length};
  const manifest={schema:'terminator_data_library/v1',snapshot:id,created_utc:new Date().toISOString(),summary,entries,skipped,
    policy:'Original physics versions retained. No recording is automatically expert imitation or 26.2 on-policy experience.'};
  const json=JSON.stringify(manifest,null,2)+'\n';fs.writeFileSync(path.join(snapshot,'manifest.json'),json);fs.writeFileSync(path.join(root,'manifest.json'),json);
  fs.copyFileSync(path.join(repo,'training/scenarios/catalog.json'),path.join(root,'catalog.json'));
  const rows=entries.map(e=>`<tr><td><a href="${esc(e.path)}">${esc(e.source)}</a></td><td>${esc(e.kind)}</td><td>${esc(e.source_game_version)} (${esc(e.version_basis)})</td><td>${esc(e.mode??'')}</td><td>${e.rows??''}</td><td>${e.invalid_rows?'REVIEW: invalid JSON':e.inspection?.structurally_complete===false?'REVIEW: incomplete':'Unreviewed'}</td></tr>`).join('\n');
  fs.writeFileSync(path.join(root,'index.html'),`<!doctype html><meta charset="utf-8"><title>Terminator training data</title><style>body{font:16px system-ui;margin:32px;background:#111827;color:#eee}a{color:#93c5fd}input{padding:12px;width:80%;margin:16px 0}td,th{text-align:left;padding:8px;border-bottom:1px solid #374151}table{width:100%}small{color:#cbd5e1}</style><h1>Terminator training data</h1><p>${summary.files} files; ${summary.unique_objects} unique objects; ${summary.jsonl_rows} JSONL rows. Snapshot ${esc(id)}.</p><p>Original recordings retain their source physics. Infrastructure tests, bot traces and human data require review before training.</p><p><a href="manifest.json">Machine-readable manifest</a> · <a href="catalog.json">Kits/maps/modes</a> · <a href="26.2/manifest.json">26.2 scenario export</a> · <a href="START-HERE.txt">Instructions</a></p><input id="q" placeholder="Filter by mode, source path, category or version" aria-label="Filter files"><table><thead><tr><th>File</th><th>Category</th><th>Source version</th><th>Mode</th><th>Rows</th><th>Review</th></tr></thead><tbody>${rows}</tbody></table><script>document.getElementById('q').addEventListener('input',e=>{const q=e.target.value.toLowerCase();for(const r of document.querySelectorAll('tbody tr'))r.hidden=!r.textContent.toLowerCase().includes(q)});</script>`);
  fs.writeFileSync(path.join(root,'START-HERE.txt'),'Open index.html for the searchable library. manifest.json is the machine-readable index.\n\nSnapshots preserve original file names and relative folders. Objects are checksum-verified read-only copies; the snapshot views use hard links where supported. Never edit a snapshot; edit source data then rebuild. Original sources are untouched.\n\nThe 26.2 directory contains rebuilt scenarios and target compatibility results. Historical 1.21.4 outcomes retain their source physics; they are not newly recorded 26.2 fights. All experience requires quality and observation/privacy review before training. A snapshot can include failed or incomplete infrastructure captures, clearly flagged for exclusion.\n\nNo world saves, credentials, game binaries, compiler caches or legacy user folders are collected. Checkpoints are indexed only for provenance, not as training samples. Historical archives may duplicate rows already extracted; do not count archives and extracted rows twice. JSONL row totals count source aliases and are not unique episodes.\n');
  process.stdout.write(JSON.stringify({root,snapshot:id,summary})+'\n');return manifest;
}
if(require.main===module)build(process.argv[2]).catch(e=>{console.error(e.stack);process.exitCode=1;});
module.exports={build,walk,describe,digest};
