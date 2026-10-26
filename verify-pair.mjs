import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import {X509Certificate,createHash} from 'node:crypto';
const require=createRequire(import.meta.url);
const JSZip=require(process.env.S4_JSZIP_PATH||'jszip');
const root=path.dirname(fileURLToPath(import.meta.url));
const versionName=process.env.ACTION_RELAY_VERSION_NAME||'0.3.1';
const rpk=fs.readFileSync(path.join(root,`watch/dist/cn.local.actionrelay.debug.${versionName}.rpk`));
const apk=fs.readFileSync(path.join(root,`android/build/ActionRelay-${versionName}.apk`));
const certificate=new X509Certificate(fs.readFileSync(path.join(root,'watch/sign/debug/certificate.pem')));
assert.ok(rpk.indexOf(certificate.raw)>=0,'Watch package must carry the selected certificate');
assert.ok(apk.indexOf(certificate.raw)>=0,'Phone package must carry the same certificate');
const watchZip=await JSZip.loadAsync(rpk,{checkCRC32:true});
const manifest=JSON.parse(await watchZip.file('manifest.json').async('string'));
assert.equal(manifest.package,'cn.local.actionrelay');
assert.deepEqual(manifest.features.map(f=>f.name),['system.interconnect']);
assert.equal(manifest.versionCode,4);
const phoneZip=await JSZip.loadAsync(apk,{checkCRC32:true});
assert.ok(phoneZip.file('classes.dex'));assert.ok(phoneZip.file('AndroidManifest.xml'));
for(const archive of [watchZip,phoneZip])assert.ok(!Object.keys(archive.files).some(p=>/private\.pem|\.p12$|\.jks$/.test(p)));
console.log(JSON.stringify({result:'PASS',package:manifest.package,certificateSha256:certificate.fingerprint256,
  watchSha256:createHash('sha256').update(rpk).digest('hex'),phoneSha256:createHash('sha256').update(apk).digest('hex'),
  checks:['both package CRCs','same certificate embedded','watch feature declaration','phone DEX and manifest','no signing keys in packages']},null,2));
