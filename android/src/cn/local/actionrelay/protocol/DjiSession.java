package cn.local.actionrelay.protocol;

/**
 * Single-thread-confined DJI Action 4 control session. Invoke all methods on one
 * Android Handler/executor. Transport must queue frames in order and report BLE
 * failures by disconnecting the session; GATT write success is not camera success.
 */
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
    public static final long COMMAND_TIMEOUT_MS = 5000;
    public static final long HANDSHAKE_TIMEOUT_MS = 35000;
    private final Clock clock;
    private final Transport transport;
    private final Listener listener;
    private final long controllerId;
    private final byte[] identity;
    private final DjiProtocol.StreamDecoder decoder = new DjiProtocol.StreamDecoder();
    private String phase = "idle";
    private int sequence, handshakeSequence;
    private long handshakeDeadline;
    private DjiProtocol.CameraStatus status;
    private long statusGeneration;
    private Pending pending;
    private boolean staleLogged;

    private static final class Pending {
        final int sequence;
        final String requestId;
        final boolean desired;
        final long deadline, statusGeneration;
        boolean acknowledged;
        Pending(int seq, String id, boolean desired, long now, long generation) {
            sequence = seq; requestId = id; this.desired = desired;
            deadline = now + COMMAND_TIMEOUT_MS; statusGeneration = generation;
        }
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
    public boolean canControlRecording() {
        return isReady() && status != null && getStatusAgeMs() <= STATUS_FRESH_MS
                && status.isControllableState() && pending == null;
    }

    public void startHandshake(boolean firstPairing, int verificationCode) {
        if ("handshaking".equals(phase) || isReady()) {
            listener.onLog("连接流程已在进行，未重复请求"); return;
        }
        phase = "handshaking"; status = null; pending = null;
        decoder.reset(); staleLogged = false;
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
        try {
            for (DjiProtocol.Frame frame : decoder.feed(bytes)) handle(frame);
        } catch (IllegalArgumentException error) { listener.onLog("已忽略无效通知：" + safeMessage(error)); }
    }

    private void handle(DjiProtocol.Frame frame) {
        if (isReady() && frame.is(0, 0x19) && !frame.isResponse()
                && frame.payload.length >= 33 && DjiProtocol.u8(frame.payload[26]) == 2) {
            if (DjiProtocol.u32(frame.payload, 0) != DjiProtocol.ACTION_4_DEVICE_ID
                    || DjiProtocol.u16(frame.payload, 27) != 0) {
                failHandshake("相机撤销授权或返回了不同设备标识"); return;
            }
            try {
                // Retransmitted camera authorization is answered idempotently.
                transport.send(DjiProtocol.connectionResponse(frame.sequence, controllerId));
            } catch (Exception error) { failHandshake("授权应答发送失败：" + safeMessage(error)); }
            return;
        }
        if (frame.is(0, 0x19) && "handshaking".equals(phase)) {
            if (frame.isResponse()) {
                if (frame.sequence != handshakeSequence) return;
                if (frame.payload.length < 5) { failHandshake("相机连接应答长度不足"); return; }
                int result = DjiProtocol.u8(frame.payload[4]);
                if (result != 0) { failHandshake("相机连接请求返回错误 " + result); return; }
                listener.onLog("相机已受理连接请求，等待相机授权结果");
                return;
            }
            if (frame.payload.length < 33 || DjiProtocol.u8(frame.payload[26]) != 2) {
                failHandshake("相机授权数据格式不符合预期"); return;
            }
            int result = DjiProtocol.u16(frame.payload, 27);
            if (result != 0) { failHandshake("相机拒绝连接，不会自动重复配对"); return; }
            long cameraId = DjiProtocol.u32(frame.payload, 0);
            if (cameraId != DjiProtocol.ACTION_4_DEVICE_ID) {
                failHandshake("检测到的相机设备标识不是 Action 4：0x" + Long.toHexString(cameraId)); return;
            }
            try {
                // Camera's active request uses its own SEQ; echo it in this response.
                transport.send(DjiProtocol.connectionResponse(frame.sequence, controllerId));
                if (!"handshaking".equals(phase)) return;
                phase = "ready";
                transport.send(DjiProtocol.subscribeStatus(nextSequence()));
                if (!isReady()) return;
                listener.onReady(true);
                listener.onLog("协议已连接，等待相机状态");
            } catch (Exception error) { failHandshake("连接确认发送失败：" + safeMessage(error)); }
            return;
        }
        if (!isReady()) return;
        if (frame.is(0x1d, 2) && !frame.isResponse()) {
            if (frame.payload.length < 7) { listener.onLog("已忽略过短相机状态"); return; }
            status = DjiProtocol.cameraStatus(frame, clock.nowMs());
            statusGeneration++;
            staleLogged = false;
            listener.onStatus(status);
            if (pending != null && statusGeneration > pending.statusGeneration
                    && status.isNormalVideoMode()
                    && ((pending.desired && status.cameraState == 3)
                      || (!pending.desired && (status.cameraState == 1 || status.cameraState == 5)))) {
                complete("confirmed", pending.desired ? "相机已确认开始录像" : "相机已确认停止录像");
            }
            return;
        }
        if (frame.is(0x1d, 3) && frame.isResponse() && pending != null
                && frame.sequence == pending.sequence) {
            if (frame.payload.length < 1) { listener.onLog("录像命令应答长度不足，继续等待状态"); return; }
            int result = DjiProtocol.u8(frame.payload[0]);
            if (result != 0) complete("failed", "相机拒绝录像命令，错误码 " + result);
            else {
                pending.acknowledged = true;
                listener.onLog("录像命令已应答，等待相机实际状态确认");
            }
        }
    }

    public boolean setRecording(boolean desired, String requestId) {
        if (requestId == null || requestId.length() == 0) throw new IllegalArgumentException("requestId required");
        if (!canControlRecording()) {
            listener.onCommandResult(requestId, desired, "rejected",
                    pending != null ? "上一条指令尚未确认" : "相机未就绪、状态已过期或未处于普通视频模式");
            return false;
        }
        if (status.isRecording() == desired) {
            listener.onCommandResult(requestId, desired, "confirmed",
                    desired ? "相机当前已在录像" : "相机当前未在录像");
            return true;
        }
        int seq = nextSequence();
        pending = new Pending(seq, requestId, desired, clock.nowMs(), statusGeneration);
        try { transport.send(DjiProtocol.recording(seq, desired)); }
        catch (Exception error) { complete("failed", "命令未能发送：" + safeMessage(error)); return false; }
        if (!isReady()) return false;
        listener.onLog(desired ? "已发送开始录像，等待相机确认" : "已发送停止录像，等待相机确认");
        return true;
    }

    public void tick(long nowMs) {
        if ("handshaking".equals(phase) && nowMs >= handshakeDeadline) {
            failHandshake("等待相机授权超时，请在相机上确认后重新连接"); return;
        }
        if (pending != null && nowMs >= pending.deadline)
            complete("unknown", pending.acknowledged ? "相机已应答，但录像结果未确认" : "录像命令结果未确认，请查看相机");
        if (isReady() && status != null && nowMs - status.receivedAtMs > STATUS_FRESH_MS && !staleLogged) {
            staleLogged = true;
            listener.onLog("相机状态已过期，暂停录像按钮，等待新状态");
        }
    }

    public void disconnect() {
        phase = "disconnected";
        if (pending != null) complete("unknown", "连接已断开，录像结果未确认；不会重发旧指令");
        status = null; decoder.reset(); listener.onReady(false);
    }

    private void failHandshake(String reason) {
        phase = "failed"; status = null; decoder.reset();
        listener.onReady(false); listener.onLog(reason); listener.onDisconnectRequired(reason);
    }
    private void complete(String outcome, String message) {
        Pending result = pending;
        if (result == null) return;
        pending = null;
        listener.onCommandResult(result.requestId, result.desired, outcome, message);
    }
    private static String safeMessage(Exception error) {
        String text = error.getMessage(); return text == null ? error.getClass().getSimpleName() : text;
    }
}
