import fs from 'node:fs';
import {pathToFileURL} from 'node:url';
export function plan(policy, number) {
  if (!Number.isSafeInteger(number) || number < 1) throw Error('Invalid run number');
  if (!['phone','firmware'].includes(policy.track)) throw Error('Invalid release track');
  const base = policy.baseVersion.split('.').map(Number);
  if (base.length !== 3 || base.some(n => !Number.isSafeInteger(n) || n < 0)) throw Error('Invalid base version');
  const code = policy.baseVersionCode + number;
  if (!Number.isSafeInteger(code) || code > 2100000000) throw Error('Invalid Android/Vela version code');
  return {version: `${base[0]}.${base[1]}.${base[2]+number}`, version_code: code, track: policy.track};
}
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.env.GITHUB_REF !== 'refs/heads/main') throw Error('Releases run only on main');
  const result = plan(JSON.parse(fs.readFileSync('release-policy.json')), Number(process.env.GITHUB_RUN_NUMBER));
  fs.appendFileSync(process.env.GITHUB_OUTPUT, Object.entries(result).map(([k,v])=>`${k}=${v}\n`).join(''));
  console.log(JSON.stringify(result));
}
