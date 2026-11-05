import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import {X509Certificate,createHash} from 'node:crypto';
const require=createRequire(import.meta.url);
const JSZip=require('jszip');
const root=path.dirname(fileURLToPath(import.meta.url));
const sourceManifest=JSON.parse(fs.readFileSync(path.join(root,'watch/src/manifest.json')));
const versionName=process.env.ACTION_RELAY_VERSION_NAME||sourceManifest.versionName;
assert.equal(versionName,sourceManifest.versionName);
const signing=JSON.parse(fs.readFileSync(path.join(root,'signing/release-signing.json')));
const rpk=fs.readFileSync(path.join(root,`watch/dist/cn.local.actionrelay.debug.${versionName}.rpk`));
const apk=fs.readFileSync(path.join(root,`android/build/ActionRelay-${versionName}.apk`));
const certificate=new X509Certificate(fs.readFileSync(path.join(root,'watch/sign/debug/certificate.pem')));
assert.equal(certificate.fingerprint256.replaceAll(':',''),signing.certificateSha256,'Certificate fingerprint does not match fixed release certificate');
assert.ok(rpk.indexOf(certificate.raw)>=0,'Watch package must carry the selected certificate');
assert.ok(apk.indexOf(certificate.raw)>=0,'Phone package must carry the same certificate');
const watchZip=await JSZip.loadAsync(rpk,{checkCRC32:true});
const manifest=JSON.parse(await watchZip.file('manifest.json').async('string'));
assert.equal(manifest.package,'cn.local.actionrelay');
assert.deepEqual(manifest.features.map(f=>f.name),['system.interconnect','system.storage','system.vibrator']);
assert.equal(manifest.versionName,versionName);
assert.equal(manifest.versionCode,sourceManifest.versionCode);
const phoneZip=await JSZip.loadAsync(apk,{checkCRC32:true});
assert.ok(phoneZip.file('classes.dex'));assert.ok(phoneZip.file('AndroidManifest.xml'));
const nativeNames=Object.keys(phoneZip.files).filter(p=>/^lib\/[^/]+\/[^/]+\.so$/.test(p));
assert.deepEqual(nativeNames,['lib/arm64-v8a/libaction_relay_core.so'],'This release supports only the requested ARM64 ABI');
const nativeBytes=await phoneZip.file(nativeNames[0]).async('nodebuffer');
assert.equal(nativeBytes.subarray(0,4).toString('hex'),'7f454c46');
assert.equal(nativeBytes[4],2,'Native core must be ELF64');
assert.equal(nativeBytes.readUInt16LE(18),183,'Native core must target AArch64');
const phoff=Number(nativeBytes.readBigUInt64LE(32)),phsize=nativeBytes.readUInt16LE(54),phcount=nativeBytes.readUInt16LE(56);
let loadSegments=0;
for(let i=0;i<phcount;i++){
  const offset=phoff+i*phsize;
  if(nativeBytes.readUInt32LE(offset)!==1)continue;
  loadSegments++;
  assert.ok(nativeBytes.readBigUInt64LE(offset+48)>=16384n,'ELF load segments must support Android 16KB pages');
}
assert.ok(loadSegments>0,'Native ELF must have loadable segments');
for(const name of ['Rust-LICENSE-MIT.txt','Rust-LICENSE-APACHE.txt'])assert.ok(phoneZip.file('assets/native/'+name),'Rust license notice required');
for(const archive of [watchZip,phoneZip])assert.ok(!Object.keys(archive.files).some(p=>/private\.pem|\.p12$|\.jks$/.test(p)));
const report={result:'PASS',package:manifest.package,versionName,versionCode:manifest.versionCode,signingAlias:signing.alias,certificateSha256:certificate.fingerprint256,
  watchSha256:createHash('sha256').update(rpk).digest('hex'),phoneSha256:createHash('sha256').update(apk).digest('hex'),
  runtime:'Rust core + ART Android adapters',nativeAbis:['arm64-v8a'],nativeCoreSha256:createHash('sha256').update(nativeBytes).digest('hex'),rustVersion:'1.99.1',ndkVersion:'30.0.16248370',
  checks:['fixed certificate fingerprint','both package CRCs','same certificate embedded','watch feature declaration','phone DEX and manifest','no signing keys in packages']};
fs.mkdirSync(path.join(root,'artifacts'),{recursive:true});
fs.writeFileSync(path.join(root,'artifacts/signing-report.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
