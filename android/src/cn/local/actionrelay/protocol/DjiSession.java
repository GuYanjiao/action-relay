package cn.local.actionrelay.protocol;

import java.util.ArrayList;
import java.util.List;

/** Single-thread-confined DJI session. The transport serializes complete frames. */
public final class DjiSession {
    public interface Clock { long nowMs(); }
    public interface Transport { void send(byte[] frame) throws Exception; }
    public interface Listener {
        void onReady(boolean ready);
        void onStatus(DjiProtocol.CameraStatus status);
        void onLog(String message);
        void onCommandResult(String requestId, boolean desired, String outcome, String message);
        default void onDisconnectRequired(String reason) {}
    }

    public static final long STATUS_FRESH_MS = 3000;
    public static final long COMMAND_TIMEOUT_MS = 8000;
    public static final long STOP_TIMEOUT_MS = 20000;
    public static final long LATE_CONFIRMATION_MS = 30000;
    public static final long HANDSHAKE_TIMEOUT_MS = 35000;
    private static final long TARGET_SAMPLE_GAP_MS = 250;
    private static final long STATUS_RECOVERY_INTERVAL_MS = 2000;
    private final Clock clock;
    private final Transport transport;
    private final Listener listener;
    private final long controllerId;
    private final byte[] identity;
    private final DjiProtocol.StreamDecoder decoder = new DjiProtocol.StreamDecoder();
    private String phase = "idle";
    private int sequence, handshakeSequence;
    private long handshakeDeadline, statusGeneration, revision;
    private DjiProtocol.CameraStatus status;
    private Pending pending, recovery;
    private CaptureAction captureAction;
    private static final class CaptureAction {
        final String id;
        final boolean photo;
        final long deadline;
        int sequence;
        boolean switching, acked, sawCapture;
        long ackGeneration, baselinePhotos;
        CaptureAction(String id, boolean photo, long now) { this.id=id; this.photo=photo; deadline=now+(photo?30000:8000); }
    }
    private long recoveryDeadline, lastStatusRecoveryAt;
    private boolean staleLogged;
    private int lastKnownMode = -1;

    private static final class Pending {
        final int sequence;
        final boolean desired;
        final long sentAt, deadline, statusGeneration, minimumSettleMs;
        final List<String> requestIds = new ArrayList<String>();
        int ackCode = -1, matchingSamples;
        long firstMatchingAt = -1, lastMatchingAt = -1;
        Pending(int seq, String id, boolean desired, long now, long generation, boolean coversStart) {
            sequence = seq; this.desired = desired; sentAt = now;
            deadline = now + (desired ? COMMAND_TIMEOUT_MS : STOP_TIMEOUT_MS);
            statusGeneration = generation;
            minimumSettleMs = desired ? 0 : (coversStart ? 1000 : 500);
            requestIds.add(id);
        }
        void clearEvidence() { matchingSamples = 0; firstMatchingAt = lastMatchingAt = -1; }
    }

    public DjiSession(Clock clock, Transport transport, Listener listener,
                      long controllerId, byte[] identity6) {
        if (clock == null || transport == null || listener == null
                || identity6 == null || identity6.length != 6)
            throw new IllegalArgumentException("Session dependencies/identity missing");
        this.clock = clock; this.transport = transport; this.listener = listener;
        this.controllerId = controllerId; this.identity = identity6.clone();
    }
    private int nextSequence() { sequence = (sequence + 1) & 0xffff; if (sequence == 0) sequence = 1; return sequence; }
    public String getPhase() { return phase; }
    public boolean isReady() { return "ready".equals(phase); }
    public DjiProtocol.CameraStatus getStatus() { return status; }
    public long getStatusAgeMs() { return status == null ? Long.MAX_VALUE : Math.max(0, clock.nowMs() - status.receivedAtMs); }
    public long getRevision() { return revision; }
    public boolean isBusy() { return pending != null || captureAction != null; }
    public Boolean getPendingDesired() {
        return pending != null ? Boolean.valueOf(pending.desired)
                : recovery != null ? Boolean.valueOf(recovery.desired) : null;
    }
    public String getCommandPhase() {
        if (captureAction != null) return captureAction.switching ? "switching" : "capturing";
        return pending != null ? (pending.desired ? "starting" : "stopping")
                : recovery != null ? "recovering" : "idle";
    }
    public boolean canStartRecording() {
        return isReady() && pending == null && recovery == null && status != null
                && captureAction == null && getStatusAgeMs() <= STATUS_FRESH_MS && !status.isModeTransition()
                && status.temperatureState < 2 && (status.isExplicitlyStopped() || (status.mode == 1 && status.cameraState == 2));
    }
    public boolean canStopRecording() {
        if (!isReady() || lastKnownMode != 1) return false;
        if (captureAction != null) return !captureAction.photo;
        if (pending != null) return pending.desired;
        if (recovery != null) return true;
        return status != null && getStatusAgeMs() <= STATUS_FRESH_MS && status.isRecording();
    }
    public boolean canControlRecording() { return canStartRecording() || canStopRecording(); }

    public void startHandshake(boolean firstPairing, int verificationCode) {
        if ("handshaking".equals(phase) || isReady()) { listener.onLog("连接流程已在进行，未重复请求"); return; }
        phase = "handshaking"; status = null; pending = recovery = null; captureAction = null; lastKnownMode = -1;
        decoder.reset(); staleLogged = false; revision++;
        listener.onReady(false);
        handshakeSequence = nextSequence();
        handshakeDeadline = clock.nowMs() + HANDSHAKE_TIMEOUT_MS;
        try {
            transport.send(DjiProtocol.connectionRequest(handshakeSequence, controllerId,
                    identity, firstPairing, verificationCode));
            if (!"handshaking".equals(phase)) return;
            listener.onLog("已请求连接，请在相机上核对并允许；验证码 "
                    + String.format(java.util.Locale.ROOT, "%04d", verificationCode));
        } catch (Exception error) { failHandshake("连接请求发送失败：" + safeMessage(error)); }
    }
    public void onBytes(byte[] bytes) {
        if (!"handshaking".equals(phase) && !isReady()) return;
        try { for (DjiProtocol.Frame frame : decoder.feed(bytes)) handle(frame); }
        catch (IllegalArgumentException error) { listener.onLog("已忽略无效通知：" + safeMessage(error)); }
    }
    private void handle(DjiProtocol.Frame frame) {
        if (isReady() && frame.is(0, 0x19) && !frame.isResponse()
                && frame.payload.length >= 33 && DjiProtocol.u8(frame.payload[26]) == 2) {
            if (DjiProtocol.u32(frame.payload, 0) != DjiProtocol.ACTION_4_DEVICE_ID
                    || DjiProtocol.u16(frame.payload, 27) != 0) {
                failHandshake("相机撤销授权或返回了不同设备标识"); return;
            }
            try { transport.send(DjiProtocol.connectionResponse(frame.sequence, controllerId)); }
            catch (Exception error) { failHandshake("授权应答发送失败：" + safeMessage(error)); }
            return;
        }
        if (frame.is(0, 0x19) && "handshaking".equals(phase)) {
            if (frame.isResponse()) {
                if (frame.sequence != handshakeSequence) return;
                if (frame.payload.length < 5) { failHandshake("相机连接应答长度不足"); return; }
                int result = DjiProtocol.u8(frame.payload[4]);
                if (result != 0) { failHandshake("相机连接请求返回错误 " + result); return; }
                listener.onLog("相机已受理连接请求，等待相机授权结果"); return;
            }
            if (frame.payload.length < 33 || DjiProtocol.u8(frame.payload[26]) != 2) {
                failHandshake("相机授权数据格式不符合预期"); return;
            }
            if (DjiProtocol.u16(frame.payload, 27) != 0) { failHandshake("相机拒绝连接，不会自动重复配对"); return; }
            long cameraId = DjiProtocol.u32(frame.payload, 0);
            if (cameraId != DjiProtocol.ACTION_4_DEVICE_ID) {
                failHandshake("检测到的相机设备标识不是 Action 4：0x" + Long.toHexString(cameraId)); return;
            }
            try {
                transport.send(DjiProtocol.connectionResponse(frame.sequence, controllerId));
                if (!"handshaking".equals(phase)) return;
                phase = "ready";
                transport.send(DjiProtocol.subscribeStatus(nextSequence()));
                if (!isReady()) return;
                revision++; listener.onReady(true); listener.onLog("协议已连接，等待相机状态");
            } catch (Exception error) { failHandshake("连接确认发送失败：" + safeMessage(error)); }
            return;
        }
        if (!isReady()) return;
        if (frame.is(0x1d, 2) && !frame.isResponse()) {
            if (frame.payload.length < 7) { listener.onLog("已忽略过短相机状态"); return; }
            status = DjiProtocol.cameraStatus(frame, clock.nowMs());
            statusGeneration++; revision++; lastKnownMode = status.mode; staleLogged = false;
            listener.onStatus(status);
            if (captureAction != null) observeCapture();
            if (pending != null) observeTarget(pending, false);
            else if (recovery != null) {
                if (clock.nowMs() >= recoveryDeadline) { recovery = null; revision++; }
                else observeTarget(recovery, true);
            }
            return;
        }
        if (captureAction != null && frame.isResponse() && frame.sequence == captureAction.sequence &&
                frame.is(0x1d, captureAction.switching ? 4 : 3)) {
            if (frame.payload.length < 1) return;
            if (DjiProtocol.u8(frame.payload[0]) != 0) {
                finishCapture("failed", "相机拒绝切换或拍照，返回码 " + DjiProtocol.u8(frame.payload[0])); return;
            }
            captureAction.acked = true; captureAction.ackGeneration = statusGeneration;
            revision++; return;
        }
        if (frame.is(0x1d, 3) && frame.isResponse()) {
            Pending operation = pending != null && frame.sequence == pending.sequence ? pending
                    : recovery != null && frame.sequence == recovery.sequence ? recovery : null;
            if (operation == null) return;
            if (frame.payload.length < 1) { listener.onLog("录像命令应答长度不足，继续等待状态"); return; }
            operation.ackCode = DjiProtocol.u8(frame.payload[0]); revision++;
            listener.onLog(operation.ackCode == 0
                    ? (operation.desired ? "开始指令已受理，等待实际录像状态" : "停止指令已受理，等待相机结束录像及状态确认")
                    : "相机返回码 " + operation.ackCode + "，继续核对实际录像状态");
        }
    }
    private void observeTarget(Pending operation, boolean late) {
        if (statusGeneration <= operation.statusGeneration) return;
        boolean matches = operation.desired ? status.isRecording() : status.isExplicitlyStopped();
        if (!matches) { operation.clearEvidence(); return; }
        long now = clock.nowMs();
        if (operation.firstMatchingAt < 0) {
            operation.firstMatchingAt = operation.lastMatchingAt = now; operation.matchingSamples = 1;
        } else if (now - operation.lastMatchingAt >= TARGET_SAMPLE_GAP_MS) {
            operation.lastMatchingAt = now; operation.matchingSamples++;
        }
        if (operation.matchingSamples < 2 || now - operation.firstMatchingAt < TARGET_SAMPLE_GAP_MS
                || now - operation.sentAt < operation.minimumSettleMs) return;
        if (late) recovery = null; else pending = null;
        revision++;
        result(operation, "confirmed", (late ? "相机迟到回报：" : "")
                + (operation.desired ? "已确认开始录像" : "已确认停止录像"));
    }
    public boolean canTakePhoto() {
        return isReady() && pending == null && recovery == null && captureAction == null && status != null
                && getStatusAgeMs() <= STATUS_FRESH_MS && status.mode == 5 && !status.isModeTransition()
                && (status.cameraState == 1 || status.cameraState == 2) && status.temperatureState < 2;
    }
    public boolean takePhoto(String id) {
        if (id == null || id.length() == 0) throw new IllegalArgumentException("requestId required");
        if (!canTakePhoto()) { listener.onCommandResult(id,true,"rejected","相机拍照未就绪"); return false; }
        return beginCapture(id,true,status.cameraState == 2);
    }
    private boolean beginCapture(String id, boolean photo, boolean switching) {
        CaptureAction action = new CaptureAction(id,photo,clock.nowMs());
        captureAction=action; action.switching=switching; revision++;
        return sendCapture(action);
    }
    private boolean sendCapture(CaptureAction action) {
        action.sequence=nextSequence(); action.acked=false; action.ackGeneration=statusGeneration;
        action.baselinePhotos=status == null ? -1 : status.remainingPhotos;
        try { transport.send(action.switching ? DjiProtocol.switchMode(action.sequence,action.photo?5:1)
                : DjiProtocol.recording(action.sequence,true)); }
        catch (Exception error) { if(captureAction==action)finishCapture("unknown","拍摄命令发送未确认，不会重发："+safeMessage(error)); return false; }
        return captureAction==action && isReady();
    }
    private void observeCapture() {
        CaptureAction action=captureAction;
        if(action==null)return;
        if(clock.nowMs()>=action.deadline){finishCapture("unknown","切换或拍照结果未确认，不会重发");return;}
        if(status.mode!=(action.photo?5:1)) { finishCapture("unknown","相机模式已改变，已取消待执行拍摄");return; }
        if(status.temperatureState>=2){finishCapture("rejected","相机过热，已取消待执行拍摄");return;}
        if(action.switching) {
            if(!action.acked || statusGeneration<=action.ackGeneration || status.cameraState!=1 || status.isModeTransition())return;
            if(!action.photo) {
                captureAction=null; revision++;
                setRecording(true,action.id); // only after a post-ACK live-view state; exactly one start
            } else { action.switching=false; revision++; sendCapture(action); }
        } else {
            if(status.cameraState==3)action.sawCapture=true;
            boolean countChanged=action.baselinePhotos>0 && status.remainingPhotos>=0 && status.remainingPhotos<action.baselinePhotos;
            if(action.acked && statusGeneration>action.ackGeneration && status.cameraState==1 && (action.sawCapture || countChanged))
                finishCapture("confirmed","相机状态已确认拍照完成");
        }
    }
    private void finishCapture(String outcome,String message) {
        CaptureAction action=captureAction; captureAction=null; revision++;
        if(action!=null)listener.onCommandResult(action.id,true,outcome,message);
    }
    public boolean setRecording(boolean desired, String requestId) {
        if (requestId == null || requestId.length() == 0) throw new IllegalArgumentException("requestId required");
        if(captureAction!=null) {
            if(!desired && !captureAction.photo) {
                finishCapture("unknown","已取消待执行的开始录像，不会自动重发");
                if(status==null || !status.isRecording()) {
                    listener.onCommandResult(requestId,false,"unknown","开始准备已取消，请以相机当前状态为准");return true;
                }
            } else { listener.onCommandResult(requestId,desired,"rejected","上一拍摄请求仍在确认");return false; }
        }
        if (pending != null && pending.desired == desired) {
            if (!pending.requestIds.contains(requestId)) pending.requestIds.add(requestId);
            listener.onLog(desired ? "开始请求仍在确认，未重复发送" : "停止请求仍在确认，未重复发送");
            return true;
        }
        if (pending == null && recovery == null && isReady() && status != null
                && getStatusAgeMs() <= STATUS_FRESH_MS && status.hasKnownRecordingState()
                && status.isRecording() == desired) {
            listener.onCommandResult(requestId, desired, "confirmed",
                    desired ? "相机最新状态已在录像" : "相机最新状态已停止录像");
            return true;
        }
        if (desired ? !canStartRecording() : !canStopRecording()) {
            listener.onCommandResult(requestId, desired, "rejected",
                    desired ? "相机未就绪、状态过期或另一请求尚待确认" : "当前没有可停止的已知录像或未决开始请求");
            return false;
        }
        if(desired && status.cameraState==2) return beginCapture(requestId,false,true);
        boolean coversStart = !desired && ((pending != null && pending.desired)
                || (recovery != null && recovery.desired));
        boolean supersededWhilePending = pending != null;
        Pending superseded = supersededWhilePending ? pending : recovery;
        recovery = null;
        int seq = nextSequence();
        Pending operation = new Pending(seq, requestId, desired, clock.nowMs(), statusGeneration, coversStart);
        pending = operation; revision++; lastStatusRecoveryAt = clock.nowMs();
        if (supersededWhilePending && superseded.desired)
            result(superseded, "unknown", "开始请求已被停止请求覆盖，不会自动重发开始");
        if (!isReady() || pending != operation) return false;
        try { transport.send(DjiProtocol.recording(seq, desired)); }
        catch (Exception error) {
            if (pending == operation) { pending = null; revision++; result(operation,"failed","命令未能发送：" + safeMessage(error)); }
            return false;
        }
        if (!isReady()) return false;
        listener.onLog(desired ? "已发送开始录像，等待相机确认" : "已发送停止录像，正在等待相机停止及状态确认");
        return true;
    }
    public void tick(long nowMs) {
        if(captureAction!=null && nowMs>=captureAction.deadline)finishCapture("unknown","切换或拍照结果未确认，不会重发");
        if ("handshaking".equals(phase) && nowMs >= handshakeDeadline) {
            failHandshake("等待相机授权超时，请在相机上确认后重新连接"); return;
        }
        if (pending != null && nowMs >= pending.deadline) {
            Pending timedOut = pending; pending = null; recovery = timedOut;
            recovery.clearEvidence(); recoveryDeadline = nowMs + LATE_CONFIRMATION_MS; revision++;
            boolean knownRejected = timedOut.ackCode > 0 && status != null
                    && getStatusAgeMs() <= STATUS_FRESH_MS && status.hasKnownRecordingState()
                    && status.isRecording() != timedOut.desired;
            result(timedOut, knownRejected ? "failed" : "unknown", knownRejected
                    ? "相机返回错误码 " + timedOut.ackCode + "，目标状态未达成；将继续同步"
                    : timedOut.desired ? "开始结果尚未确认，将继续同步相机状态；不会自动重发"
                    : "停止结果仍未确认，将继续同步；请查看相机实际状态");
        }
        if (recovery != null && nowMs >= recoveryDeadline) {
            recovery = null; revision++; listener.onLog("迟到确认窗口已结束，请以相机最新状态为准");
        }
        if (isReady() && (pending != null || recovery != null || captureAction != null)
                && getStatusAgeMs() > STATUS_RECOVERY_INTERVAL_MS
                && nowMs - lastStatusRecoveryAt >= STATUS_RECOVERY_INTERVAL_MS) {
            lastStatusRecoveryAt = nowMs;
            try { transport.send(DjiProtocol.subscribeStatus(nextSequence())); }
            catch (Exception error) { listener.onLog("状态同步请求未能发送：" + safeMessage(error)); }
        }
        if (isReady() && status != null && nowMs - status.receivedAtMs > STATUS_FRESH_MS && !staleLogged) {
            staleLogged = true;
            listener.onLog(pending != null && !pending.desired
                    ? "停止请求已发送，正在恢复相机状态回报"
                    : "相机状态已过期，等待新状态；未确认的开始仍可请求停止");
        }
    }
    public void disconnect() {
        phase = "disconnected";
        if(captureAction!=null)finishCapture("unknown","连接已断开，未确认拍摄不会重发");
        Pending interrupted = pending; pending = recovery = null;
        status = null; lastKnownMode = -1; decoder.reset(); revision++;
        if (interrupted != null) result(interrupted, "unknown", "连接已断开，录像结果未确认；不会重发旧指令");
        listener.onReady(false);
    }
    private void failHandshake(String reason) {
        phase = "failed"; if(captureAction!=null)finishCapture("unknown","授权已失效，未确认拍摄不会重发"); status = null; lastKnownMode = -1; decoder.reset(); revision++;
        listener.onReady(false); listener.onLog(reason); listener.onDisconnectRequired(reason);
    }
    private void result(Pending operation, String outcome, String message) {
        for (String id : new ArrayList<String>(operation.requestIds))
            listener.onCommandResult(id, operation.desired, outcome, message);
    }
    private static String safeMessage(Exception error) {
        String text = error.getMessage(); return text == null ? error.getClass().getSimpleName() : text;
    }
}
