package cn.local.actionrelay;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.*;
import cn.local.actionrelay.protocol.DjiProtocol;

/** One camera, one GATT operation at a time. All public methods run on main. */
@SuppressLint("MissingPermission")
public final class DjiBle {
    public interface Listener {
        void onCandidate(String address, String name, int rssi);
        void onPhase(String phase, String message);
        void onTransportReady();
        void onBytes(byte[] bytes);
        void onDisconnected(String reason);
        void onLog(String message);
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic write;
    private boolean scanning, discovering, ready, writing;
    private int mtu = 23;
    private final FrameQueue queue = new FrameQueue();
    private Runnable timeout, writeTimeout;

    public DjiBle(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }
    public boolean bluetoothEnabled() { return adapter != null && adapter.isEnabled(); }
    public boolean isReady() { return ready; }
    public void scan() {
        if (!bluetoothEnabled()) { listener.onLog("请先打开手机蓝牙"); return; }
        stopScan(); scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { listener.onLog("手机蓝牙扫描不可用"); return; }
        scanning = true;
        try {
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            listener.onLog("搜索相机中，持续10秒"); main.postDelayed(stopScanTask, 10000);
        } catch (Exception e) { scanning = false; listener.onLog("扫描失败：" + e.getMessage()); }
    }
    private final Runnable stopScanTask = this::stopScan;
    public void stopScan() {
        main.removeCallbacks(stopScanTask);
        if (scanning && scanner != null) try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        scanning = false;
    }
    private static boolean isDji(ScanRecord record) {
        if (record == null) return false;
        byte[] bytes = record.getBytes();
        for (int at = 0; at < bytes.length;) {
            int length = bytes[at] & 255;
            if (length == 0 || at + length >= bytes.length) break;
            if (length >= 6 && (bytes[at+1]&255)==255 && (bytes[at+2]&255)==0xaa
                && (bytes[at+3]&255)==0x08 && (bytes[at+6]&255)==0xfa) return true;
            at += length+1;
        }
        return false;
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            main.post(() -> {
                if (!scanning) return;
                ScanRecord record = result.getScanRecord();
                if (!isDji(record)) return;
                String name = record == null ? null : record.getDeviceName();
                listener.onCandidate(result.getDevice().getAddress(), name == null ? "DJI Osmo 相机" : name, result.getRssi());
            });
        }
        @Override public void onScanFailed(int code) { main.post(() -> { stopScan(); listener.onLog("扫描失败，错误码 " + code); }); }
    };
    public void connect(String address) {
        stopScan(); disconnect("重新建立连接");
        if (!bluetoothEnabled()) { listener.onDisconnected("请先打开手机蓝牙"); return; }
        listener.onPhase("connecting", "正在连接相机");
        try {
            gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            armTimeout("相机连接超时", 20000);
        } catch (Exception e) { disconnect("连接失败：" + e.getMessage()); }
    }
    private void armTimeout(String message, long ms) {
        clearTimeout(); timeout = () -> disconnect(message); main.postDelayed(timeout, ms);
    }
    private void clearTimeout() { if (timeout != null) main.removeCallbacks(timeout); timeout = null; }
    private void discover(BluetoothGatt current) {
        if (gatt != current || discovering) return;
        if (mtu < 59) { disconnect("蓝牙MTU协商未达到遥控要求，请重新连接"); return; }
        discovering = true; clearTimeout(); armTimeout("相机服务发现超时", 12000);
        if (!current.discoverServices()) disconnect("无法发现相机服务");
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int status, int state) {
            main.post(() -> {
                if (current != gatt) { try { current.close(); } catch (Exception ignored) {} return; }
                if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnect("相机蓝牙已断开（" + status + "）"); return;
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    clearTimeout(); listener.onPhase("connecting", "已连接蓝牙，准备通信");
                    boolean requested = current.requestMtu(185);
                    if (!requested) discover(current);
                    else { timeout = () -> discover(current); main.postDelayed(timeout,3000); }
                }
            });
        }
        @Override public void onMtuChanged(BluetoothGatt current, int value, int status) {
            main.post(() -> { if(current != gatt) return; if(status==0) mtu=Math.max(23,value); discover(current); });
        }
        @Override public void onServicesDiscovered(BluetoothGatt current, int status) {
            main.post(() -> {
                if (current != gatt) return;
                if (status != 0) { disconnect("服务发现失败（"+status+"）"); return; }
                BluetoothGattService service = current.getService(DjiProtocol.SERVICE_UUID);
                BluetoothGattCharacteristic notify = service == null ? null : service.getCharacteristic(DjiProtocol.NOTIFY_UUID);
                write = service == null ? null : service.getCharacteristic(DjiProtocol.WRITE_UUID);
                if (notify==null || write==null || (write.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)==0) {
                    disconnect("相机未提供预期的遥控服务"); return;
                }
                BluetoothGattDescriptor ccc = notify.getDescriptor(DjiProtocol.CCCD_UUID);
                if (ccc==null || !current.setCharacteristicNotification(notify,true)) {
                    disconnect("无法订阅相机状态"); return;
                }
                armTimeout("相机通知设置超时",8000);
                boolean sent;
                if (Build.VERSION.SDK_INT>=33) sent=current.writeDescriptor(ccc,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==0;
                else { ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); sent=current.writeDescriptor(ccc); }
                if (!sent) disconnect("通知设置请求失败");
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt current, BluetoothGattDescriptor descriptor, int status) {
            main.post(() -> {
                if(current != gatt || !DjiProtocol.CCCD_UUID.equals(descriptor.getUuid())) return;
                clearTimeout(); if(status!=0) { disconnect("通知设置失败（"+status+"）"); return; }
                ready=true; listener.onLog("蓝牙通信通道已建立，MTU="+mtu); listener.onTransportReady();
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic characteristic, byte[] value) {
            deliver(current,characteristic,value);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic characteristic) {
            if(Build.VERSION.SDK_INT<33) deliver(current,characteristic,characteristic.getValue());
        }
        private void deliver(BluetoothGatt current, BluetoothGattCharacteristic characteristic, byte[] value) {
            if(!DjiProtocol.NOTIFY_UUID.equals(characteristic.getUuid()) || value==null) return;
            byte[] bytes=value.clone(); main.post(() -> { if(current==gatt && ready) listener.onBytes(bytes); });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt current, BluetoothGattCharacteristic characteristic, int status) {
            main.post(() -> {
                if(current!=gatt || !writing) return;
                if(writeTimeout!=null) main.removeCallbacks(writeTimeout); writeTimeout=null;
                writing=false;
                if(status!=0) { disconnect("指令传输失败（"+status+"），执行结果可能未知"); return; }
                pump();
            });
        }
    };
    public void send(byte[] frame) {
        if(!ready || gatt==null || write==null) throw new IllegalStateException("相机通信未就绪");
        if(frame.length>mtu-3) throw new IllegalStateException("指令超过协商MTU，未发送");
        queue.offerControl(frame,SystemClock.elapsedRealtime());
        pump();
        if(!ready) throw new IllegalStateException("蓝牙发送失败，连接已关闭");
    }
    public boolean sendGps(byte[] frame){
        if(!ready||gatt==null||write==null||frame==null||frame.length>mtu-3)return false;
        queue.offerGps(frame,SystemClock.elapsedRealtime());pump();return ready;
    }
    public boolean supportsGps(){return ready&&mtu>=69;}
    public void clearGps(){queue.clearGps();}
    public void setControlPriority(boolean active){
        if(gatt!=null)try{gatt.requestConnectionPriority(active?BluetoothGatt.CONNECTION_PRIORITY_HIGH:BluetoothGatt.CONNECTION_PRIORITY_BALANCED);}
        catch(RuntimeException error){listener.onLog("蓝牙连接参数未调整，继续使用当前参数");}
    }
    private void pump() {
        if(writing || !ready) return;
        FrameQueue.Packet packet=queue.poll(SystemClock.elapsedRealtime());
        if(packet==null)return;
        if(SystemClock.elapsedRealtime()>=packet.deadline){disconnect("指令在发送前已过期，未继续发送");return;}
        byte[] bytes=packet.bytes; writing=true; boolean sent;
        try {
            if(!packet.telemetry&&bytes.length>=19&&(bytes[12]&255)==0x1d&&(bytes[13]&255)==3)
                listener.onLog("控制分段：GATT排队 "+Math.max(0,SystemClock.elapsedRealtime()-packet.queuedAt)+" ms");
            if(Build.VERSION.SDK_INT>=33) sent=gatt.writeCharacteristic(write,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==0;
            else { write.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); write.setValue(bytes); sent=gatt.writeCharacteristic(write); }
            if(!sent) { disconnect("相机写入请求未被接受"); return; }
            writeTimeout=()->disconnect("相机写入超时，执行结果可能未知"); main.postDelayed(writeTimeout,4000);
        } catch(Exception error) { disconnect("相机写入异常："+error.getMessage()); }
    }
    public void disconnect(String reason) {
        clearTimeout(); if(writeTimeout!=null) main.removeCallbacks(writeTimeout); writeTimeout=null;
        BluetoothGatt previous=gatt; gatt=null; ready=false; discovering=false; writing=false; write=null; mtu=23; queue.clear();
        if(previous!=null) { try { previous.disconnect(); } catch(Exception ignored) {} try { previous.close(); } catch(Exception ignored) {} }
        listener.onDisconnected(reason);
    }
    public void close() { stopScan(); disconnect("手机中转已关闭"); }
}
