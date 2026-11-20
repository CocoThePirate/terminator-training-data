'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const [input,output,version]=process.argv.slice(2);if(!input||!output)throw Error('Usage: materialize-data.cjs EXTRACTED_DATA NEW_OUTPUT [26.2|1.21.4]');
const source=path.resolve(input),dest=path.resolve(output),manifest=JSON.parse(fs.readFileSync(path.join(source,'manifest.json')));
if(fs.existsSync(dest))throw Error('Choose a fresh output directory');let count=0;
for(const entry of manifest.entries){if(version&&entry.source_game_version!==version)continue;const file=path.resolve(source,entry.path);
  if(!file.startsWith(source+path.sep))throw Error('Unsafe data path');
  if(crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')!==entry.sha256)throw Error('Object checksum mismatch');
  for(const alias of entry.aliases){const target=path.resolve(dest,alias);if(!target.startsWith(dest+path.sep))throw Error('Unsafe alias path');fs.mkdirSync(path.dirname(target),{recursive:true});if(!fs.existsSync(target)){fs.copyFileSync(file,target,fs.constants.COPYFILE_EXCL);count++;}}
}
console.log('Materialized '+count+' files into '+dest);
