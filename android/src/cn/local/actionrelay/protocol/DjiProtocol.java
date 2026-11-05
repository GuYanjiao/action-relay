package cn.local.actionrelay.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.nio.ByteBuffer;
import cn.local.actionrelay.core.NativeCore;

/**
 * DJI R SDK API adapter; Rust implements framing, CRC and stream reassembly.
 * Reference: dji-sdk/Osmo-GPS-Controller-Demo, commit
 * 92fe23e5a749f189593f980a26a105c3bb66aa1c (protocol/, utils/crc/, logic/).
 * See THIRD_PARTY_NOTICES.txt distributed with this source.
 * No Android classes, GPS, wake advertising, or toggle-shutter commands are used.
 */
public final class DjiProtocol {
    private DjiProtocol() {}
    public static final UUID SERVICE_UUID = uuid16(0xfff0);
    public static final UUID NOTIFY_UUID = uuid16(0xfff4);
    public static final UUID WRITE_UUID = uuid16(0xfff5);
    public static final UUID CCCD_UUID = uuid16(0x2902);
    public static final int CMD_NO_RESPONSE = 0x00;
    public static final int CMD_OPTIONAL_RESPONSE = 0x01;
    public static final int CMD_WAIT_RESULT = 0x02;
    public static final int ACK_NO_RESPONSE = 0x20;
    public static final long ACTION_4_DEVICE_ID = 0xff33L;
    // Preserve the exact value used by DJI's start/stop implementation.
    public static final long RECORD_CONTROL_DEVICE_ID = 0x33ff0000L;
    private static final int MIN_FRAME_LENGTH = 18;
    private static final int MAX_FRAME_LENGTH = 1023;

    public static UUID uuid16(int uuid) {
        return UUID.fromString(String.format(java.util.Locale.ROOT,
                "0000%04x-0000-1000-8000-00805f9b34fb", uuid & 0xffff));
    }

    public static int u8(byte value) { return value & 0xff; }
    public static int u16(byte[] bytes, int at) {
        return u8(bytes[at]) | u8(bytes[at + 1]) << 8;
    }
    public static long u32(byte[] bytes, int at) {
        return ((long) u8(bytes[at])) | ((long) u8(bytes[at + 1]) << 8)
                | ((long) u8(bytes[at + 2]) << 16) | ((long) u8(bytes[at + 3]) << 24);
    }
    private static void put16(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value; bytes[at + 1] = (byte) (value >>> 8);
    }
    private static void put32(byte[] bytes, int at, long value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (8 * i));
    }

    public static int crc16(byte[] bytes, int offset, int length) {
        return (int)NativeCore.crc(bytes,offset,length,false);
    }

    public static long crc32(byte[] bytes, int offset, int length) {
        return NativeCore.crc(bytes,offset,length,true);
    }

    public static byte[] frame(int commandSet, int commandId, int commandType,
                               int sequence, byte[] payload) {
        return NativeCore.frame(commandSet,commandId,commandType,sequence,payload);
    }

    public static byte[] connectionRequest(int sequence, long controllerId,
                                           byte[] identityMac, boolean firstPairing,
                                           int verificationCode) {
        if (identityMac == null || identityMac.length != 6)
            throw new IllegalArgumentException("Expected six identity bytes");
        if (verificationCode < 0 || verificationCode > 9999)
            throw new IllegalArgumentException("Verification code must be 0..9999");
        byte[] payload = new byte[33];
        put32(payload, 0, controllerId);
        payload[4] = 6;
        System.arraycopy(identityMac, 0, payload, 5, 6);
        // fw_version=0 suppresses firmware-update transfers to the controller.
        payload[26] = firstPairing ? (byte) 1 : (byte) 0;
        put16(payload, 27, verificationCode);
        return frame(0, 0x19, CMD_WAIT_RESULT, sequence, payload);
    }

    public static byte[] connectionResponse(int cameraSequence, long controllerId) {
        byte[] payload = new byte[9];
        put32(payload, 0, controllerId);
        // ret_code=0, camera index reserved=0 (one selected camera).
        return frame(0, 0x19, ACK_NO_RESPONSE, cameraSequence, payload);
    }

    public static byte[] subscribeStatus(int sequence) {
        return frame(0x1d, 5, CMD_NO_RESPONSE, sequence, new byte[] {3, 20, 0, 0, 0, 0});
    }

    public static byte[] recording(int sequence, boolean start) {
        byte[] payload = new byte[9];
        put32(payload, 0, RECORD_CONTROL_DEVICE_ID);
        payload[4] = start ? (byte) 0 : (byte) 1;
        return frame(0x1d, 3, CMD_OPTIONAL_RESPONSE, sequence, payload);
    }

    public static byte[] switchMode(int sequence, int mode) {
        if (mode != 1 && mode != 5) throw new IllegalArgumentException("Only normal video/photo supported");
        byte[] payload = new byte[9];
        put32(payload, 0, RECORD_CONTROL_DEVICE_ID);
        payload[4] = (byte)mode;
        return frame(0x1d, 4, CMD_OPTIONAL_RESPONSE, sequence, payload);
    }

    public static final class Frame {
        public final int commandType, sequence, commandSet, commandId;
        public final byte[] payload;
        Frame(byte[] bytes) {
            commandType = u8(bytes[3]); sequence = u16(bytes, 8);
            commandSet = u8(bytes[12]); commandId = u8(bytes[13]);
            payload = Arrays.copyOfRange(bytes, 14, bytes.length - 4);
        }
        public boolean isResponse() { return (commandType & 0x20) != 0; }
        public boolean is(int set, int id) { return commandSet == set && commandId == id; }
    }

    public static Frame decode(byte[] bytes) {
        NativeCore.validate(bytes);
        return new Frame(bytes);
    }

    /** A fixed direct buffer belongs to this decoder and is collected by the JVM.
     * One JNI call processes a whole BLE notification, never one call per byte. */
    public static final class StreamDecoder {
        private final ByteBuffer state=NativeCore.streamState();
        public long discardedBytes;
        public synchronized List<Frame> feed(byte[] bytes) {
            List<Frame> frames=new ArrayList<Frame>();
            if(bytes==null||bytes.length==0)return frames;
            byte[] decoded=NativeCore.feed(state,bytes);
            discardedBytes=NativeCore.read64(decoded,0);
            int offset=8;
            while(offset<decoded.length){
                int length=u16(decoded,offset);offset+=2;
                frames.add(new Frame(Arrays.copyOfRange(decoded,offset,offset+length)));offset+=length;
            }
            return frames;
        }
        public synchronized void reset(){NativeCore.clear(state,false);discardedBytes=0;}
    }

    public static final class CameraStatus {
        public final int mode, cameraState, recordingSeconds, batteryPercent, temperatureState;
        public final int powerMode, nextMode;
        public final int videoResolution, fpsIndex;
        public final long remainingCapacityMb, remainingRecordSeconds, remainingPhotos;
        public final long receivedAtMs;
        CameraStatus(byte[] payload, long nowMs) {
            mode = u8(payload[0]); cameraState = u8(payload[1]);
            recordingSeconds = u16(payload, 5);
            videoResolution = u8(payload[2]); fpsIndex = u8(payload[3]);
            long capacity = payload.length >= 19 ? u32(payload,15) : -1;
            remainingCapacityMb = capacity == 0xffffffffL ? -1 : capacity;
            long remaining = payload.length >= 27 ? u32(payload,23) : -1;
            remainingRecordSeconds = remaining == 0xffffffffL ? -1 : remaining;
            long photos = payload.length >= 23 ? u32(payload,19) : -1;
            remainingPhotos = photos == 0xffffffffL ? -1 : photos;
            powerMode = payload.length > 28 ? u8(payload[28]) : -1;
            nextMode = payload.length > 29 ? u8(payload[29]) : -1;
            temperatureState = payload.length > 30 ? u8(payload[30]) : -1;
            int battery = payload.length > 37 ? u8(payload[37]) : -1;
            batteryPercent = battery <= 100 ? battery : -1;
            receivedAtMs = nowMs;
        }
        public boolean isNormalVideoMode() { return mode == 1; }
        public boolean isRecording() { return isNormalVideoMode() && cameraState == 3; }
        public boolean isPreRecording() { return isNormalVideoMode() && cameraState == 5; }
        public boolean isExplicitlyStopped() {
            return isNormalVideoMode() && (cameraState == 1 || cameraState == 5);
        }
        public boolean hasKnownRecordingState() {
            return isNormalVideoMode() && (cameraState == 1 || cameraState == 3 || cameraState == 5);
        }
        public boolean isModeTransition() { return nextMode >= 0 && nextMode != mode; }
        public boolean isControllableState() {
            return hasKnownRecordingState();
        }
    }

    public static CameraStatus cameraStatus(Frame frame, long nowMs) {
        if (!frame.is(0x1d, 2) || frame.isResponse() || frame.payload.length < 7)
            throw new IllegalArgumentException("Not a camera-status push");
        return new CameraStatus(frame.payload, nowMs);
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02X", u8(b)));
        return out.toString();
    }
}
