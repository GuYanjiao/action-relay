/* Read-only telemetry. The R SDK mode-switch command does not set resolution or FPS. */
export function remainingLabel(value) {
  if (typeof value !== 'number' || !isFinite(value) || value < 0 || value >= 4294967295 || Math.floor(value) !== value) return '—';
  var hours = Math.floor(value / 3600);
  var minutes = Math.floor(value % 3600 / 60);
  var pad = function (n) { return (n < 10 ? '0' : '') + n; };
  if (hours > 99) return hours + '小时';
  if (hours > 0) return hours + '小时' + pad(minutes) + '分';
  return minutes > 0 ? minutes + '分' : (value > 0 ? '<1分' : '0分');
}
export function storageLabel(value) {
  if (typeof value !== 'number' || !isFinite(value) || value < 0 || value >= 4294967295) return '—';
  // Protocol reports MB; use decimal GB rather than silently treating MB as MiB.
  return value >= 1000 ? (value / 1000).toFixed(1) + ' GB' : Math.floor(value) + ' MB';
}
export function videoLabel(mode, resolution, fpsIndex) {
  // In slow-motion/photo modes the FPS field has a different meaning.
  if (mode !== 1 || typeof resolution !== 'number' || typeof fpsIndex !== 'number') return '—';
  var sizes = { 10: '1080P', 16: '4K', 45: '2.7K', 66: '1080P', 67: '2.7K', 95: '2.7K', 103: '4K', 109: '4K' };
  var rates = { 1: 24, 2: 25, 3: 30, 4: 48, 5: 50, 6: 60, 10: 100, 7: 120, 19: 200, 8: 240 };
  if (!Object.prototype.hasOwnProperty.call(sizes, resolution)) return '—';
  return sizes[resolution] + ' · ' + (Object.prototype.hasOwnProperty.call(rates, fpsIndex) ? rates[fpsIndex] : '—');
}
