/* A held stop is explicit; release after dispatch never runs the cancellation animation. */
export function createHoldProgress(options) {
  var duration = 650;
  var frameMs = 16;
  var returnMs = 180;
  var now = options.now;
  var deadline = null;
  var frame = null;
  var generation = 0;
  var stage = 'idle';
  var progress = 0;

  function clearTimers() {
    if (deadline !== null) options.clearTimer(deadline);
    if (frame !== null) options.clearTimer(frame);
    deadline = null;
    frame = null;
  }
  function emit(visible) { options.onProgress(progress, visible); }
  function cancel() {
    generation += 1;
    clearTimers();
    stage = 'idle';
    progress = 0;
    emit(false);
  }
  function start() {
    cancel();
    if (!options.canStop()) return false;
    stage = 'holding';
    var run = generation;
    var startedAt = now();
    emit(true);
    function tick() {
      if (run !== generation || stage !== 'holding') return;
      frame = null;
      if (!options.canStop()) { cancel(); return; }
      progress = Math.min(99.9, Math.max(progress, (now() - startedAt) * 100 / duration));
      emit(true);
      if (run === generation && stage === 'holding') frame = options.setTimer(tick, frameMs);
    }
    deadline = options.setTimer(function () {
      if (run !== generation || stage !== 'holding') return;
      deadline = null;
      if (!options.canStop()) { cancel(); return; }
      clearTimers();
      // Set the phase before command() can synchronously update the page state.
      stage = 'committed';
      var accepted = false;
      try { accepted = options.onStop() === true; } catch (ignored) {}
      if (run !== generation) return;
      if (!accepted) { cancel(); return; }
      progress = 100;
      emit(true);
    }, duration);
    frame = options.setTimer(tick, frameMs);
    return true;
  }
  function release() {
    if (stage === 'idle' || stage === 'returning') return;
    if (stage === 'committed') { cancel(); return; }
    clearTimers();
    stage = 'returning';
    var run = generation;
    var from = progress;
    var startedAt = now();
    function fall() {
      if (run !== generation || stage !== 'returning') return;
      frame = null;
      var fraction = Math.max(0, Math.min(1, (now() - startedAt) / returnMs));
      progress = Math.min(progress, from * (1 - fraction) * (1 - fraction));
      if (fraction >= 1 || progress <= 0) { cancel(); return; }
      emit(true);
      frame = options.setTimer(fall, frameMs);
    }
    fall();
  }
  return { start: start, release: release, cancel: cancel };
}
