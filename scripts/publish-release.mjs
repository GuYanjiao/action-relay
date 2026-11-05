import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
const [track, version] = process.argv.slice(2);
if (process.env.GITHUB_REF !== 'refs/heads/main' || !['phone', 'firmware'].includes(track)) throw Error('Invalid publication context');
const tag = `v${version}`;
const base = `${process.env.GITHUB_API_URL}/repos/${process.env.GITHUB_REPOSITORY}`;
const headers = {Authorization: `Bearer ${process.env.GITHUB_TOKEN}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'};
async function request(url, method = 'GET', data) {
  const response = await fetch(url, {method, headers: {...headers, ...(data ? {'Content-Type': 'application/json'} : {})}, body: data ? JSON.stringify(data) : undefined});
  if (!response.ok) throw Error(`GitHub ${method}: ${response.status} ${await response.text()}`);
  return response.status === 204 ? undefined : response.json();
}
const existing = await fetch(`${base}/releases/tags/${encodeURIComponent(tag)}`, {headers});
if (existing.status !== 404 && !existing.ok) throw Error(`Release lookup: ${existing.status}`);
const release = existing.ok ? await existing.json() : await request(`${base}/releases`, 'POST', {
  tag_name: tag, target_commitish: process.env.GITHUB_SHA, name: `${track === 'phone' ? 'Action Relay' : 'nRF52840 实验固件'} ${version}`,
  draft: true, prerelease: track === 'firmware', body: fs.readFileSync('release-notes.md', 'utf8'), make_latest: 'false'
});
if (release.target_commitish !== process.env.GITHUB_SHA) throw Error('Release belongs to another source commit');
const root = 'release-assets';
const files = fs.readdirSync(root, {recursive: true}).filter(f => fs.statSync(path.join(root, f)).isFile());
for (const f of files) {
  const bytes = fs.readFileSync(path.join(root, f));
  const digest = 'sha256:' + createHash('sha256').update(bytes).digest('hex');
  const name = f.replaceAll('\\', '/').replaceAll('/', '-');
  const old = release.assets.find(a => a.name === name);
  if (old) { if (old.digest !== digest) throw Error(`Existing asset differs: ${name}`); continue; }
  if (!release.draft) throw Error('Never modify an already published release');
  const url = release.upload_url.replace(/\{.*$/, '') + '?name=' + encodeURIComponent(name);
  const uploaded = await fetch(url, {method: 'POST', headers: {...headers, 'Content-Type': 'application/octet-stream'}, body: bytes});
  if (!uploaded.ok) throw Error(`Asset upload ${name}: ${uploaded.status}`);
}
if (release.draft) await request(`${base}/releases/${release.id}`, 'PATCH', {draft: false, make_latest: track === 'phone' ? 'true' : 'false'});
console.log(`Published ${release.html_url}`);
