import fs from 'node:fs';
const [version, codeText] = process.argv.slice(2);
const code = Number(codeText);
if (process.env.GITHUB_ACTIONS !== 'true') throw Error('Version stamping is cloud-only');
if (!/^\d+\.\d+\.\d+$/.test(version) || !Number.isSafeInteger(code) || code < 1 || code > 2100000000) throw Error('Invalid package version');
for (const file of ['package.json', 'package-lock.json', 'watch/package.json', 'watch/package-lock.json', 'watch/src/manifest.json']) {
  const data = JSON.parse(fs.readFileSync(file));
  if (file.endsWith('manifest.json')) { data.versionName = version; data.versionCode = code; }
  else { data.version = version; if (data.packages?.['']) data.packages[''].version = version; }
  fs.writeFileSync(file, JSON.stringify(data, null, 2) + '\n');
}
if (fs.existsSync('android/AndroidManifest.xml')) {
let xml = fs.readFileSync('android/AndroidManifest.xml', 'utf8');
for (const [key, value] of [['versionName', version], ['versionCode', codeText]]) {
  const expression = new RegExp(`android:${key}="[^"]+"`, 'g');
  if ([...xml.matchAll(expression)].length !== 1) throw Error(`Expected exactly one ${key}`);
  xml = xml.replace(expression, `android:${key}="${value}"`);
}
fs.writeFileSync('android/AndroidManifest.xml', xml);
}
console.log(`Stamped APK/RPK ${version} (${code}) in the build checkout`);
