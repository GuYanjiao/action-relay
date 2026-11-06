import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const [track, version] = process.argv.slice(2);
assert.ok(['phone', 'firmware'].includes(track));
assert.match(version, /^\d+\.\d+\.\d+$/);
assert.equal(process.env.GITHUB_REF, 'refs/heads/main');
const sha = process.env.GITHUB_SHA;
const input = 'release-input', output = 'release-assets';
fs.mkdirSync(output, {recursive: true});
const files = fs.readdirSync(input, {recursive: true}).filter(f => fs.statSync(path.join(input, f)).isFile());
function file(name) {
  const matches = files.filter(f => path.basename(f) === name);
  assert.equal(matches.length, 1, `Expected exactly one ${name}`);
  return path.join(input, matches[0]);
}
function hash(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
function copy(name, as = name) { const bytes = fs.readFileSync(file(name)); fs.writeFileSync(path.join(output, as), bytes); return bytes; }
let metadata;
if (track === 'phone') {
  const report = JSON.parse(fs.readFileSync(file('signing-report.json')));
  assert.equal(report.result, 'PASS'); assert.equal(report.versionName, version);
  assert.equal(report.versionCode, Number(process.env.RELEASE_VERSION_CODE));
  const fixed = JSON.parse(fs.readFileSync('signing/release-signing.json'));
  assert.equal(report.certificateSha256.replaceAll(':', '').toUpperCase(), fixed.certificateSha256);
  assert.equal(hash(copy(`ActionRelay-${version}.apk`)), report.phoneSha256);
  assert.equal(hash(copy(`cn.local.actionrelay.debug.${version}.rpk`, `ActionRelay-Watch-${version}.rpk`)), report.watchSha256);
  fs.writeFileSync(path.join(output, 'package-verification.json'), JSON.stringify(report, null, 2) + '\n');
  metadata = {track, version, versionCode: report.versionCode, commit: sha, cameraRelayImplemented: true};
} else {
  metadata = JSON.parse(fs.readFileSync(file('build-info.json')));
  assert.equal(metadata.commit, sha); assert.equal(metadata.version, version);
  assert.equal(metadata.authKeyIncluded, false); assert.equal(metadata.heartRateBaselineRetained, true);
  assert.equal(JSON.parse(fs.readFileSync(file('partition-check.json'))).partitionCheckPassed, true);
  const bytes = copy('ActionRelay-Combined-Baseline.uf2', `ActionRelay-nRF52840-${version}.uf2`);
  const sums = fs.readFileSync(file('SHA256SUMS.txt'), 'utf8');
  assert.ok(sums.split(/\r?\n/).some(line => line.trim() === `${hash(bytes)}  ActionRelay-Combined-Baseline.uf2`));
  copy('partition-check.json'); copy('upstream.json'); copy('README.md', 'FIRMWARE-README.md');
  // Retain upstream notices with every distributed firmware, without collecting
  // unrelated files or private data from the workspace.
  let licenseCount = 0;
  for (const f of files.filter(f => f.replaceAll('\\', '/').includes('LICENSES/'))) {
    const name = path.basename(f);
    assert.ok(!fs.existsSync(path.join(output, 'LICENSE-' + name)), 'Duplicate license name');
    fs.copyFileSync(path.join(input, f), path.join(output, 'LICENSE-' + name)); ++licenseCount;
  }
  assert.ok(licenseCount, 'Firmware license notices required');
  const report = JSON.parse(fs.readFileSync(file('signing-report.json')));
  assert.equal(report.result, 'PASS'); assert.equal(report.versionName, version);
  assert.equal(report.versionCode, Number(process.env.RELEASE_VERSION_CODE));
  const fixed = JSON.parse(fs.readFileSync('signing/release-signing.json'));
  assert.equal(report.certificateSha256.replaceAll(':', '').toUpperCase(), fixed.certificateSha256);
  assert.equal(hash(copy(`cn.local.actionrelay.debug.${version}.rpk`, `ActionRelay-Watch-nRF52840-${version}.rpk`)), report.watchSha256);
  fs.writeFileSync(path.join(output, 'package-verification.json'), JSON.stringify(report, null, 2) + '\n');
  metadata = {...metadata, track, version};
}
metadata.runId = process.env.GITHUB_RUN_ID;
metadata.runNumber = Number(process.env.GITHUB_RUN_NUMBER);
fs.writeFileSync(path.join(output, 'build-info.json'), JSON.stringify(metadata, null, 2) + '\n');
const assets = fs.readdirSync(output, {recursive: true}).filter(f => fs.statSync(path.join(output, f)).isFile());
fs.writeFileSync(path.join(output, 'SHA256SUMS.txt'), assets.sort().map(f => `${hash(fs.readFileSync(path.join(output, f)))}  ${f.replaceAll('\\', '/')}\n`).join(''));
fs.writeFileSync('release-notes.md', track === 'phone'
  ? `手机中转版 ${version}\n\n下载同一版本的 APK 和 RPK。Kotlin / Compose / miuix 手机界面，配合 ARM64（arm64-v8a）Rust 核心，暂不支持32位 ARMv7。通过 Kotlin 实现的原有协议/状态机测试、Rust 单元/对照、完整协议回归、固定证书签名、16KB 页对齐和包配对校验。\n\n源码：${sha}\n构建：${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}\n\n功能与安装说明见 README；硬件兼容边界仍以设备实测为准。\n`
  : `nRF52840 实验固件 ${version}\n\n这是开发板中转路线的诊断预发布版。保留心率 ANT+ 基线，包含 S4 FE95/认证诊断；Action 4 控制和应用消息中转尚未完成，不能替代手机中转版。没有附带用户 AuthKey。\n\n源码：${sha}\n构建：${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}/actions/runs/${process.env.GITHUB_RUN_ID}\n\n刷写前请阅读 FIRMWARE-README 与分区检查报告，并备份现有程序。\n`);
