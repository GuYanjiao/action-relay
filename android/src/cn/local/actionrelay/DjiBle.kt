package cn.local.actionrelay

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.local.actionrelay.protocol.DjiProtocol

/** One camera and one outstanding GATT operation. All mutations run on main. */
@SuppressLint("MissingPermission")
// Android 12 compatibility paths still require the legacy GATT overloads.
@Suppress("DEPRECATION")
class DjiBle(context: Context, private val listener: Listener) {
    interface Listener {
        fun onCandidate(address: String, name: String, rssi: Int)
        fun onPhase(phase: String, message: String)
        fun onTransportReady()
        fun onBytes(bytes: ByteArray)
        fun onDisconnected(reason: String)
        fun onLog(message: String)
    }
    private val context=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanner: BluetoothLeScanner?=null
    private var gatt: BluetoothGatt?=null
    private var write: BluetoothGattCharacteristic?=null
    private var scanning=false
    private var discovering=false
    private var ready=false
    private var writing=false
    private var mtu=23
    private val queue=FrameQueue()
    private var timeout: Runnable?=null
    private var writeTimeout: Runnable?=null
    fun bluetoothEnabled()=adapter?.isEnabled==true
    fun isReady()=ready
    fun scan() {
        if(!bluetoothEnabled()){listener.onLog("请先打开手机蓝牙");return}
        stopScan();val current=adapter?.bluetoothLeScanner
        scanner=current
        if(current==null){listener.onLog("手机蓝牙扫描不可用");return}
        scanning=true
        try{
            current.startScan(null,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback)
            listener.onLog("搜索相机中，持续10秒");main.postDelayed(stopScanTask,10000)
        }catch(error: Exception){scanning=false;listener.onLog("扫描失败：${error.message}")}
    }
    private val stopScanTask=Runnable{stopScan()}
    fun stopScan() {
        main.removeCallbacks(stopScanTask)
        if(scanning)try{scanner?.stopScan(scanCallback)}catch(_: Exception){}
        scanning=false
    }
    private fun isDji(record: ScanRecord?): Boolean {
        val bytes=record?.bytes ?: return false;var at=0
        while(at<bytes.size){
            val length=bytes[at].toInt() and 255
            if(length==0 || at+length>=bytes.size)break
            if(length>=6 && bytes[at+1].toInt() and 255==255 && bytes[at+2].toInt() and 255==0xaa && bytes[at+3].toInt() and 255==8 && bytes[at+6].toInt() and 255==0xfa)return true
            at+=length+1
        }
        return false
    }
    private val scanCallback=object: ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { main.post {
            if(!scanning || !isDji(result.scanRecord))return@post
            listener.onCandidate(result.device.address,result.scanRecord?.deviceName ?: "DJI Osmo 相机",result.rssi)
        } }
        override fun onScanFailed(code: Int) { main.post{stopScan();listener.onLog("扫描失败，错误码 $code")} }
    }
    fun connect(address: String) {
        stopScan();disconnect("重新建立连接")
        if(!bluetoothEnabled()){listener.onDisconnected("请先打开手机蓝牙");return}
        listener.onPhase("connecting","正在连接相机")
        try{gatt=adapter?.getRemoteDevice(address)?.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);armTimeout("相机连接超时",20000)}
        catch(error: Exception){disconnect("连接失败：${error.message}")}
    }
    private fun armTimeout(message: String, ms: Long) { clearTimeout();timeout=Runnable{disconnect(message)}.also{main.postDelayed(it,ms)} }
    private fun clearTimeout() { timeout?.let(main::removeCallbacks);timeout=null }
    private fun discover(current: BluetoothGatt) {
        if(gatt!==current || discovering)return
        if(mtu<59){disconnect("蓝牙MTU协商未达到遥控要求，请重新连接");return}
        discovering=true;armTimeout("相机服务发现超时",12000)
        if(!current.discoverServices())disconnect("无法发现相机服务")
    }
    private val callback=object: BluetoothGattCallback() {
        override fun onConnectionStateChange(current: BluetoothGatt, status: Int, state: Int) { main.post{
            if(current!==gatt){try{current.close()}catch(_: Exception){};return@post}
            if(status!=BluetoothGatt.GATT_SUCCESS || state==BluetoothProfile.STATE_DISCONNECTED){disconnect("相机蓝牙已断开（$status）");return@post}
            if(state==BluetoothProfile.STATE_CONNECTED){
                clearTimeout();listener.onPhase("connecting","已连接蓝牙，准备通信")
                if(!current.requestMtu(185))discover(current)
                else timeout=Runnable{discover(current)}.also{main.postDelayed(it,3000)}
            }
        } }
        override fun onMtuChanged(current: BluetoothGatt, value: Int, status: Int) { main.post{if(current===gatt){if(status==0)mtu=maxOf(23,value);discover(current)}} }
        override fun onServicesDiscovered(current: BluetoothGatt, status: Int) { main.post{
            if(current!==gatt)return@post
            if(status!=0){disconnect("服务发现失败（$status）");return@post}
            val service=current.getService(DjiProtocol.SERVICE_UUID)
            val notify=service?.getCharacteristic(DjiProtocol.NOTIFY_UUID)
            val writer=service?.getCharacteristic(DjiProtocol.WRITE_UUID);write=writer
            if(notify==null || writer==null || writer.properties and BluetoothGattCharacteristic.PROPERTY_WRITE==0){disconnect("相机未提供预期的遥控服务");return@post}
            val ccc=notify.getDescriptor(DjiProtocol.CCCD_UUID)
            if(ccc==null || !current.setCharacteristicNotification(notify,true)){disconnect("无法订阅相机状态");return@post}
            armTimeout("相机通知设置超时",8000)
            val sent=if(Build.VERSION.SDK_INT>=33)current.writeDescriptor(ccc,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==0
            else{ccc.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;current.writeDescriptor(ccc)}
            if(!sent)disconnect("通知设置请求失败")
        } }
        override fun onDescriptorWrite(current: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { main.post{
            if(current!==gatt || descriptor.uuid!=DjiProtocol.CCCD_UUID)return@post
            clearTimeout();if(status!=0){disconnect("通知设置失败（$status）");return@post}
            ready=true;listener.onLog("蓝牙通信通道已建立，MTU=$mtu");listener.onTransportReady()
        } }
        override fun onCharacteristicChanged(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { deliver(current,characteristic,value) }
        @Deprecated("Legacy Android callback")
        override fun onCharacteristicChanged(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { if(Build.VERSION.SDK_INT<33)deliver(current,characteristic,characteristic.value) }
        private fun deliver(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
            if(characteristic.uuid!=DjiProtocol.NOTIFY_UUID || value==null)return
            val bytes=value.clone();main.post{if(current===gatt && ready)listener.onBytes(bytes)}
        }
        override fun onCharacteristicWrite(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { main.post{
            if(current!==gatt || !writing)return@post
            writeTimeout?.let(main::removeCallbacks);writeTimeout=null;writing=false
            if(status!=0){disconnect("指令传输失败（$status），执行结果可能未知");return@post};pump()
        } }
    }
    fun send(frame: ByteArray) {
        check(ready && gatt!=null && write!=null){"相机通信未就绪"}
        check(frame.size<=mtu-3){"指令超过协商MTU，未发送"}
        queue.offerControl(frame,SystemClock.elapsedRealtime());pump();check(ready){"蓝牙发送失败，连接已关闭"}
    }
    fun sendGps(frame: ByteArray?): Boolean {
        if(!ready || gatt==null || write==null || frame==null || frame.size>mtu-3)return false
        queue.offerGps(frame,SystemClock.elapsedRealtime());pump();return ready
    }
    fun supportsGps()=ready && mtu>=69
    fun clearGps()=queue.clearGps()
    fun setControlPriority(active: Boolean) {
        try{gatt?.requestConnectionPriority(if(active)BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED)}
        catch(_: RuntimeException){listener.onLog("蓝牙连接参数未调整，继续使用当前参数")}
    }
    private fun pump() {
        if(writing || !ready)return
        val current=gatt ?: return;val writer=write ?: return
        val packet=queue.poll(SystemClock.elapsedRealtime()) ?: return
        if(SystemClock.elapsedRealtime()>=packet.deadline){disconnect("指令在发送前已过期，未继续发送");return}
        val bytes=packet.bytes;writing=true
        try{
            if(!packet.telemetry && bytes.size>=19 && bytes[12].toInt() and 255==0x1d && bytes[13].toInt() and 255==3)listener.onLog("控制分段：GATT排队 ${maxOf(0,SystemClock.elapsedRealtime()-packet.queuedAt)} ms")
            val sent=if(Build.VERSION.SDK_INT>=33)current.writeCharacteristic(writer,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==0
            else{writer.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;writer.value=bytes;current.writeCharacteristic(writer)}
            if(!sent){disconnect("相机写入请求未被接受");return}
            writeTimeout=Runnable{disconnect("相机写入超时，执行结果可能未知")}.also{main.postDelayed(it,4000)}
        }catch(error: Exception){disconnect("相机写入异常：${error.message}")}
    }
    fun disconnect(reason: String) {
        clearTimeout();writeTimeout?.let(main::removeCallbacks);writeTimeout=null
        val previous=gatt;gatt=null;ready=false;discovering=false;writing=false;write=null;mtu=23;queue.clear()
        try{previous?.disconnect()}catch(_: Exception){};try{previous?.close()}catch(_: Exception){}
        listener.onDisconnected(reason)
    }
    fun close() {stopScan();disconnect("手机中转已关闭")}
}
