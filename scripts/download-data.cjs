'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),{Readable,Transform}=require('node:stream'),{pipeline}=require('node:stream/promises');
(async()=>{
  const metadata=JSON.parse(fs.readFileSync(path.join(__dirname,'../data/release-assets.json')));
  const root=path.resolve(process.argv[2]??'downloads');fs.mkdirSync(root,{recursive:true});
  for(const asset of metadata.assets){const file=path.join(root,asset.name);if(fs.existsSync(file))throw Error('Destination already exists: '+file);
    const response=await fetch(asset.url);if(!response.ok)throw Error('Download failed: '+response.status);
    const hash=crypto.createHash('sha256');let bytes=0;
    await pipeline(Readable.fromWeb(response.body),new Transform({transform(chunk,enc,done){hash.update(chunk);bytes+=chunk.length;done(null,chunk);}}),fs.createWriteStream(file,{flags:'wx'}));
    if(hash.digest('hex')!==asset.sha256||bytes!==asset.bytes)throw Error('Checksum/size mismatch: '+file);
    console.log('Verified '+file);
  }
})().catch(e=>{console.error(e.message);process.exitCode=1;});
