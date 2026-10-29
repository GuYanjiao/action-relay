/* The relay owns camera truth. Transport success is never recording success. */
export function createRelayController(options) {
  var connect = null;
  var visible = false;
  var destroyed = false;
  var transportOpen = false;
  var lastStatus = null;
  var lastReceived = null;
  var pending = null;
  var timerId = null;
  var timerSet = false;
  var sequence = 0;
  var epoch = 0;
  var message = '请开启中转设备';
  var relayKind = 'phone';
  var relaySeen = null;
  var probe = null;
  var relayLatency = -1;
  var now = options.now || function () { return Date.now(); };
  var prefix = now().toString(36) + '-' + Math.floor(Math.random() * 1000000).toString(36);
  var lastSeq = -1;
  var lastSession = null;
  var retiredSessions = [];
  var cameraNames = { disconnected: '未连接', connecting: '连接中', authorizing: '等待授权', ready: '已就绪' };

  function shorten(value) {
    try { return String(value || '').replace(/[\x00-\x1f\x7f]/g, ' ').slice(0, 48); }
    catch (ignored) { return '未知错误'; }
  }

  function hasTokens(status) {
    return !!status && typeof status.session === 'string' && status.session.length > 0 && status.session.length <= 128 &&
      typeof status.ticket === 'string' && status.ticket.length > 0 && status.ticket.length <= 128;
  }

  function validSession(value) {
    return typeof value === 'string' && value.length > 0 && value.length <= 128;
  }

  function packetKind(packet) {
    if (packet.relay === undefined) return lastSession === packet.session ? relayKind : 'phone';
    if (!packet.relay || (packet.relay.kind !== 'phone' && packet.relay.kind !== 'nrf52840')) return null;
    return packet.relay.kind;
  }

  function adoptPeer(packet, kind) {
    if (lastSession && lastSession === packet.session && relayKind !== kind) return false;
    if (lastSession && lastSession !== packet.session) {
      retiredSessions.push(lastSession);
      if (retiredSessions.length > 8) retiredSessions.shift();
      lastStatus = null;
      lastReceived = null;
      lastSeq = -1;
      pending = null;
      probe = null;
      relayLatency = -1;
    }
    lastSession = packet.session || null;
    relayKind = kind;
    relaySeen = now();
    transportOpen = true;
    return true;
  }

  function emit() {
    if (destroyed || !visible) return;
    var age = lastReceived === null ? Infinity : now() - lastReceived;
    var fresh = transportOpen && lastStatus !== null && age >= 0 && age < 6000;
    var status = fresh ? lastStatus : null;
    var camera = status ? status.camera : 'disconnected';
    var recording = status ? status.recording : null;
    var phase = status ? (status.recordPhase || (recording ? 'recording' : 'idle')) : 'unknown';
    var canStart = fresh && hasTokens(status) && camera === 'ready' && status.canStart === true && !pending;
    var canStop = fresh && hasTokens(status) && camera === 'ready' && status.canStop === true &&
      (!pending || pending.action === 'start');
    var peerAge = relaySeen === null ? Infinity : now() - relaySeen;
    var relayConnected = transportOpen && peerAge >= 0 && peerAge < 6000;
    var peerName = relayKind === 'nrf52840' ? '开发板' : '手机';
    var phoneText = peerName + (relayConnected ? '：已连接' : transportOpen ? '：等待状态' : '：未连接');
    var recordText = '状态未知';
    if (fresh && camera === 'ready' && typeof recording === 'boolean') recordText = recording ? '正在录像' : '已停止录像';
    else if (fresh && camera === 'authorizing') recordText = '请在相机确认';
    else if (fresh && camera === 'connecting') recordText = '正在连接相机';
    else if (fresh && camera === 'disconnected') recordText = '相机未连接';
    var meta = '状态以相机回报为准';
    if (fresh && camera === 'ready') {
      var parts = [];
      if (recording && typeof status.recordSeconds === 'number' && status.recordSeconds >= 0) {
        var seconds = Math.floor(status.recordSeconds);
        var minutes = Math.floor(seconds / 60);
        var remainder = seconds % 60;
        parts.push(minutes + ':' + (remainder < 10 ? '0' : '') + remainder);
      }
      if (typeof status.battery === 'number' && status.battery >= 0 && status.battery <= 100) parts.push('电量 ' + Math.floor(status.battery) + '%');
      if (parts.length) meta = parts.join(' · ');
    }
    options.onState({
      phoneText: phoneText,
      relayKind: relayKind,
      relayConnected: relayConnected,
      relayLatencyMs: relayConnected ? relayLatency : -1,
      cameraText: '相机：' + (cameraNames[camera] || '状态未知'),
      recordText: recordText,
      metaText: meta,
      noteText: shorten(message),
      startClass: canStart ? 'button start-on' : 'button button-off',
      stopClass: canStop ? 'button stop-on' : 'button button-off',
      recording: recording === true,
      pending: !!pending,
      fresh: fresh,
      phoneConnected: transportOpen && fresh,
      cameraReady: fresh && camera === 'ready',
      battery: status ? status.battery : -1,
      recordSeconds: status ? status.recordSeconds : -1,
      storageMb: status && typeof status.storageMb === 'number' && isFinite(status.storageMb) &&
        status.storageMb >= 0 && status.storageMb < 4294967295 ? status.storageMb : -1,
      cameraMode: status ? status.cameraMode : -1,
      remainingSeconds: status && typeof status.remainingSeconds === 'number' && isFinite(status.remainingSeconds) &&
        status.remainingSeconds >= 0 && status.remainingSeconds < 4294967295 && Math.floor(status.remainingSeconds) === status.remainingSeconds ? status.remainingSeconds : -1,
      temperatureState: status && typeof status.temperatureState === 'number' &&
        status.temperatureState >= 0 && status.temperatureState <= 3 && Math.floor(status.temperatureState) === status.temperatureState ? status.temperatureState : -1,
      videoResolution: status ? status.videoResolution : -1,
      fpsIndex: status ? status.fpsIndex : -1,
      recordPhase: phase,
      gpsState: status && relayKind !== 'nrf52840' ? status.gpsState : 'off',
      gpsEnabled: status && relayKind !== 'nrf52840' ? status.gpsEnabled === true : false,
      photoEnabled: fresh && hasTokens(status) && camera === 'ready' && status.canPhoto === true && !pending,
      cameraState: status ? status.cameraState : -1,
      startEnabled: canStart,
      stopEnabled: canStop
    });
  }

  function invalidate(text, disconnected) {
    lastReceived = null;
    lastStatus = null;
    pending = null;
    lastSeq = -1;
    lastSession = null;
    relaySeen = null;
    probe = null;
    relayLatency = -1;
    if (disconnected) transportOpen = false;
    message = text;
    emit();
  }

  function parseIncoming(event) {
    var data = event;
    for (var i = 0; i < 4; i += 1) {
      if (typeof data === 'string') {
        if (data.length > 4096) return null;
        try { data = JSON.parse(data); } catch (ignored) { return null; }
      } else if (data && typeof data === 'object' && data.v === 1 && typeof data.type === 'string') return data;
      else if (data && typeof data === 'object' && data.data !== undefined) data = data.data;
      else return null;
    }
    return data && data.v === 1 && typeof data.type === 'string' ? data : null;
  }

  function onmessage(event) {
    if (destroyed || !visible) return;
    var packet = parseIncoming(event);
    if (!packet) return;
    if (packet.type === 'relayHello') {
      var helloKind = packetKind(packet);
      if (!helloKind || !validSession(packet.session) || retiredSessions.indexOf(packet.session) >= 0) return;
      if (!adoptPeer(packet, helloKind)) return;
      if (!lastStatus) message = '中转已连接，等待相机状态';
      ping();
      requestStatus();
      emit();
      return;
    }
    if (packet.type === 'pong') {
      if (!probe || packet.id !== probe.id || packet.session !== probe.session || packet.session !== lastSession) return;
      var elapsed = now() - probe.sentAt;
      probe = null;
      if (elapsed < 0 || elapsed >= 2000) return;
      relayLatency = elapsed;
      relaySeen = now();
      emit();
      return;
    }
    if (packet.type === 'status') {
      if (retiredSessions.indexOf(packet.session) >= 0) return;
      var kind = packetKind(packet);
      if (!kind) return;
      if (lastSession && lastSession !== packet.session && !hasTokens(packet)) return;
      if (packet.seq !== undefined && !NumberIsInteger(packet.seq)) return;
      if (packet.revision !== undefined && !NumberIsInteger(packet.revision)) return;
      if (kind === 'nrf52840' && (!hasTokens(packet) || !NumberIsInteger(packet.seq) || !NumberIsInteger(packet.revision))) return;
      if (!Object.prototype.hasOwnProperty.call(cameraNames, packet.camera)) return;
      if (packet.recording !== null && typeof packet.recording !== 'boolean') return;
      if (typeof packet.canControl !== 'boolean' || typeof packet.busy !== 'boolean') return;
      if (typeof packet.seq === 'number' && lastSession === packet.session && packet.seq <= lastSeq) return;
      if (!adoptPeer(packet, kind)) return;
      if (typeof packet.seq === 'number') lastSeq = packet.seq;
      var previousSession = pending ? pending.session : (lastStatus ? lastStatus.session : null);
      if (hasTokens(packet) && previousSession && previousSession !== packet.session) {
        pending = null;
        message = '中转连接已更新，请重新操作';
      }
      transportOpen = true;
      lastStatus = packet;
      lastReceived = now();
      // A status can show camera truth, but must not silently confirm a command.
      if (!pending && packet.message) message = packet.message;
      emit();
      return;
    }
    if (packet.type === 'result' && pending && packet.id === pending.id) {
      if (packet.session && pending.session !== packet.session) return;
      if (packet.phase === 'accepted') {
        message = packet.message || '指令已受理，等待相机确认';
      } else if (packet.phase === 'confirmed' && packet.ok === true) {
        var expectedSession = pending.session;
        pending = null;
        // A confirmation packet must not re-enable buttons from a pre-command snapshot.
        lastStatus = null;
        lastReceived = null;
        message = packet.message || '相机已确认';
        // Same-session authoritative state avoids an extra watch -> phone round trip.
        // Older phone builds and malformed/stale bundled states keep the safe query fallback.
        if (packet.state && packet.state.type === 'status' && packet.state.v === 1 &&
            packet.state.session === expectedSession && hasTokens(packet.state) &&
            packetKind(packet.state) === relayKind &&
            (relayKind !== 'nrf52840' || (NumberIsInteger(packet.state.seq) && NumberIsInteger(packet.state.revision))) &&
            typeof packet.state.seq === 'number' && packet.state.seq > lastSeq &&
            packet.state.revision === packet.revision &&
            Object.prototype.hasOwnProperty.call(cameraNames, packet.state.camera) &&
            (packet.state.recording === null || typeof packet.state.recording === 'boolean') &&
            typeof packet.state.canControl === 'boolean' && typeof packet.state.busy === 'boolean') {
          onmessage(packet.state);
        } else requestStatus();
      } else if (packet.phase === 'failed' || packet.phase === 'unknown' || packet.phase === 'rejected') {
        pending = null;
        lastStatus = null;
        lastReceived = null;
        message = packet.message || '结果未确认，请查看相机';
        requestStatus();
      } else return;
      emit();
    }
  }

  function NumberIsInteger(value) {
    return typeof value === 'number' && isFinite(value) && value >= 0 && value <= 9007199254740991 && Math.floor(value) === value;
  }

  function ping() {
    if (!visible || destroyed || relayKind !== 'nrf52840' || !validSession(lastSession)) return;
    if (probe && now() - probe.sentAt >= 0 && now() - probe.sentAt < 2000) return;
    sequence += 1;
    var id = prefix + '-p' + sequence.toString(36);
    probe = { id: id, session: lastSession, sentAt: now() };
    send({ v: 1, type: 'ping', id: id, session: lastSession }, function () {
      if (probe && probe.id === id) { probe = null; relayLatency = -1; emit(); }
    });
  }

  function send(data, onFail) {
    if (!connect || typeof connect.send !== 'function') return false;
    try {
      connect.send({
        data: data,
        success: function () { /* Only transport receipt; wait for camera result. */ },
        fail: function (error, code) {
          if (destroyed || !visible) return;
          if (typeof onFail === 'function') onFail(error, code);
        }
      });
      return true;
    } catch (error) {
      if (typeof onFail === 'function') onFail(error);
      return false;
    }
  }

  function requestStatus() {
    if (!visible || destroyed || !connect) return;
    send({ v: 1, type: 'getStatus' });
  }

  function hello() {
    if (!visible || destroyed) return;
    send({ v: 1, type: 'hello' }, function (error, code) {
      invalidate('中转通信失败' + (code === undefined ? '' : '（' + shorten(code) + '）'), true);
    });
  }

  function bind() {
    if (connect) return true;
    try {
      connect = options.loadConnection();
      if (!connect || typeof connect.send !== 'function' || typeof connect.getReadyState !== 'function') throw new Error('通信接口不可用');
      connect.onmessage = onmessage;
      connect.onopen = function () {
        if (destroyed) return;
        transportOpen = true;
        lastReceived = null;
        lastStatus = null;
        pending = null;
        probe = null;
        relaySeen = null;
        relayLatency = -1;
        message = '中转已连接，正在取状态';
        emit();
        hello();
      };
      connect.onclose = function () {
        if (!destroyed) invalidate('中转已断开，请检查设备', true);
      };
      connect.onerror = function (error) {
        if (!destroyed) invalidate('中转通信异常，请检查设备', true);
      };
      return true;
    } catch (error) {
      connect = null;
      invalidate('手机通信接口不可用：' + shorten(error && error.message ? error.message : error), true);
      return false;
    }
  }

  function clearTick() {
    if (!timerSet) return;
    timerSet = false;
    options.clearTimer(timerId);
    timerId = null;
  }

  function schedule() {
    if (!visible || destroyed || timerSet) return;
    timerSet = true;
    timerId = options.setTimer(function () {
      timerSet = false;
      if (!visible || destroyed) return;
      var time = now();
      if (pending && (time - pending.sentAt >= (pending.timeoutMs || (pending.action === 'stop' ? 25000 : 8000)) || time < pending.sentAt)) {
        pending = null;
        lastStatus = null;
        lastReceived = null;
        message = '执行结果未确认，请查看相机';
        requestStatus();
      } else if (lastReceived !== null && (time - lastReceived >= 6000 || time < lastReceived)) {
        lastReceived = null;
        lastStatus = null;
        message = '状态已过期，请检查中转连接';
      }
      if (probe && (time - probe.sentAt >= 2000 || time < probe.sentAt)) { probe = null; relayLatency = -1; }
      emit();
      schedule();
    }, 1000);
  }

  function show() {
    if (visible || destroyed) return;
    visible = true;
    epoch += 1;
    var thisEpoch = epoch;
    lastStatus = null;
    lastReceived = null;
    pending = null;
    relaySeen = null;
    probe = null;
    relayLatency = -1;
    message = '正在连接中转';
    emit();
    if (bind()) {
      try {
        connect.getReadyState({
          success: function (result) {
            if (destroyed || !visible || thisEpoch !== epoch) return;
            if (lastReceived !== null) return;
            transportOpen = !!result && result.status === 1;
            message = transportOpen ? '中转已连接，正在取状态' : '请开启中转设备';
            emit();
            if (transportOpen) hello();
          },
          fail: function () {
            if (!destroyed && visible && thisEpoch === epoch) invalidate('无法读取手机连接状态', true);
          }
        });
      } catch (error) { invalidate('手机连接检查失败', true); }
    }
    schedule();
  }

  function hide() {
    if (!visible) return;
    visible = false;
    epoch += 1;
    clearTick();
    // Nothing is queued or retried when the page resumes.
    lastStatus = null;
    lastReceived = null;
    pending = null;
    relaySeen = null;
    probe = null;
    relayLatency = -1;
  }

  function refresh() {
    if (!visible || destroyed) return;
    lastStatus = null;
    lastReceived = null;
    message = '正在刷新相机状态';
    emit();
    if (!connect) {
      if (bind()) hello();
    } else hello();
  }

  function command(action) {
    if (destroyed || !visible) return false;
    if (action !== 'start' && action !== 'stop' && action !== 'photo') return false;
    var desired = action === 'start';
    var photo = action === 'photo';
    var age = lastReceived === null ? Infinity : now() - lastReceived;
    var canStart = lastStatus && lastStatus.canStart === true;
    var canPhoto = lastStatus && lastStatus.canPhoto === true;
    var canStop = lastStatus && lastStatus.canStop === true && (!pending || pending.action === 'start');
    if (!transportOpen || !lastStatus || !hasTokens(lastStatus) || age < 0 || age >= 6000 || lastStatus.camera !== 'ready' ||
        (photo ? (!canPhoto || pending) : (desired ? (!canStart || pending) : !canStop))) {
      message = pending ? '等待上一条指令的相机确认' : '尚未就绪，请先连接中转和相机';
      emit();
      return false;
    }
    if (!photo && ((desired && !canStart) || (!desired && !canStop))) {
      message = desired ? '相机已经在录像' : '相机已经停止录像';
      emit();
      return false;
    }
    sequence += 1;
    var id = prefix + '-' + sequence.toString(36);
    pending = { id: id, action: action, timeoutMs: photo ? 35000 : (desired && lastStatus.cameraState === 2 ? 18000 : 0), sentAt: now(), session: lastStatus.session, revision: lastStatus.revision };
    message = photo ? '正在请求拍照' : desired ? '正在请求开始录像' : '正在请求停止录像';
    emit();
    return send({ v: 1, type: 'command', id: id, action: action, session: lastStatus.session, ticket: lastStatus.ticket }, function (error, code) {
      if (!pending || pending.id !== id) return;
      invalidate('发送失败，请查看相机' + (code === undefined ? '' : '（' + shorten(code) + '）'), true);
    });
  }

  function destroy() {
    if (destroyed) return;
    hide();
    destroyed = true;
    if (connect) {
      // These properties belong to this page instance's single connection.
      try { connect.onmessage = null; } catch (ignored) {}
      try { connect.onopen = null; } catch (ignored) {}
      try { connect.onclose = null; } catch (ignored) {}
      try { connect.onerror = null; } catch (ignored) {}
    }
    connect = null;
    lastStatus = null;
    pending = null;
  }

  return { show: show, hide: hide, refresh: refresh, ping: ping, command: command, destroy: destroy };
}
