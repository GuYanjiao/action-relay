package cn.local.actionrelay.protocol

import java.util.Locale

/** Confined to the relay thread. Acceptance, observed state and confirmation remain distinct. */
class DjiSession(private val clock: Clock, private val transport: Transport, private val listener: Listener,
    private val controllerId: Long, identity6: ByteArray) {
    fun interface Clock { fun nowMs(): Long }
    fun interface Transport { @Throws(Exception::class) fun send(frame: ByteArray) }
    interface Listener {
        fun onReady(ready: Boolean)
        fun onStatus(status: DjiProtocol.CameraStatus)
        fun onLog(message: String)
        fun onCommandResult(requestId: String, desired: Boolean, outcome: String, message: String)
        fun onDisconnectRequired(reason: String) {}
    }
    companion object {
        const val STATUS_FRESH_MS=3000L
        const val COMMAND_TIMEOUT_MS=8000L
        const val STOP_TIMEOUT_MS=20000L
        const val LATE_CONFIRMATION_MS=30000L
        const val HANDSHAKE_TIMEOUT_MS=35000L
        private const val TARGET_SAMPLE_GAP_MS=250L
        private const val STATUS_RECOVERY_INTERVAL_MS=2000L
    }
    init { require(identity6.size==6) { "Session dependencies/identity missing" } }
    private val identity=identity6.clone()
    private val decoder=DjiProtocol.StreamDecoder()
    private var phase="idle"
    private var sequence=0
    private var handshakeSequence=0
    private var handshakeDeadline=0L
    private var statusGeneration=0L
    private var revision=0L
    private var status: DjiProtocol.CameraStatus?=null
    private var pending: Pending?=null
    private var recovery: Pending?=null
    private var captureAction: CaptureAction?=null
    private var recoveryDeadline=0L
    private var lastStatusRecoveryAt=0L
    private var staleLogged=false
    private var lastKnownMode=-1
    private class CaptureAction(val id: String, val photo: Boolean, now: Long) {
        val deadline=now+if(photo)30000 else 8000
        var sequence=0
        var switching=false
        var acked=false
        var sawCapture=false
        var ackGeneration=0L
        var baselinePhotos=0L
    }
    private class Pending(val sequence: Int, id: String, val desired: Boolean, val sentAt: Long, val statusGeneration: Long, coversStart: Boolean) {
        val deadline=sentAt+if(desired)COMMAND_TIMEOUT_MS else STOP_TIMEOUT_MS
        val minimumSettleMs=if(desired)0L else if(coversStart)1000L else 500L
        val requestIds=arrayListOf(id)
        var ackCode=-1
        var matchingSamples=0
        var firstMatchingAt=-1L
        var lastMatchingAt=-1L
        fun clearEvidence() { matchingSamples=0; firstMatchingAt=-1; lastMatchingAt=-1 }
    }
    private fun nextSequence(): Int { sequence=(sequence+1) and 0xffff; if(sequence==0)sequence=1; return sequence }
    fun getPhase()=phase
    fun isReady()=phase=="ready"
    fun getStatus()=status
    fun getStatusAgeMs()=status?.let { maxOf(0, clock.nowMs()-it.receivedAtMs) } ?: Long.MAX_VALUE
    fun getRevision()=revision
    fun isBusy()=pending!=null || captureAction!=null
    fun getPendingDesired(): Boolean?=pending?.desired ?: recovery?.desired
    fun getCommandPhase(): String {
        captureAction?.let { return if(it.switching)"switching" else "capturing" }
        pending?.let { return if(it.desired)"starting" else "stopping" }
        return if(recovery!=null)"recovering" else "idle"
    }
    fun canStartRecording(): Boolean {
        val current=status ?: return false
        return isReady() && pending==null && recovery==null && captureAction==null && getStatusAgeMs()<=STATUS_FRESH_MS && !current.isModeTransition() && current.temperatureState<2 && (current.isExplicitlyStopped() || (current.mode==1 && current.cameraState==2))
    }
    fun canStopRecording(): Boolean {
        if(!isReady() || lastKnownMode!=1)return false
        captureAction?.let { return !it.photo }
        pending?.let { return it.desired }
        if(recovery!=null)return true
        return getStatusAgeMs()<=STATUS_FRESH_MS && status?.isRecording()==true
    }
    fun canControlRecording()=canStartRecording() || canStopRecording()
    fun startHandshake(firstPairing: Boolean, verificationCode: Int) {
        if(phase=="handshaking" || isReady()){listener.onLog("连接流程已在进行，未重复请求");return}
        phase="handshaking"; status=null; pending=null; recovery=null; captureAction=null; lastKnownMode=-1
        decoder.reset(); staleLogged=false; revision++; listener.onReady(false)
        handshakeSequence=nextSequence(); handshakeDeadline=clock.nowMs()+HANDSHAKE_TIMEOUT_MS
        try {
            transport.send(DjiProtocol.connectionRequest(handshakeSequence,controllerId,identity,firstPairing,verificationCode))
            if(phase!="handshaking")return
            listener.onLog("已请求连接，请在相机上核对并允许；验证码 "+String.format(Locale.ROOT,"%04d",verificationCode))
        }catch(error: Exception){failHandshake("连接请求发送失败："+safeMessage(error))}
    }
    fun onBytes(bytes: ByteArray) {
        if(phase!="handshaking" && !isReady())return
        try{for(frame in decoder.feed(bytes))handle(frame)}catch(error: IllegalArgumentException){listener.onLog("已忽略无效通知："+safeMessage(error))}
    }
    private fun handle(frame: DjiProtocol.Frame) {
        if(isReady() && frame.`is`(0,0x19) && !frame.isResponse() && frame.payload.size>=33 && DjiProtocol.u8(frame.payload[26])==2){
            if(DjiProtocol.u32(frame.payload,0)!=DjiProtocol.ACTION_4_DEVICE_ID || DjiProtocol.u16(frame.payload,27)!=0){failHandshake("相机撤销授权或返回了不同设备标识");return}
            try{transport.send(DjiProtocol.connectionResponse(frame.sequence,controllerId))}catch(error: Exception){failHandshake("授权应答发送失败："+safeMessage(error))}
            return
        }
        if(frame.`is`(0,0x19) && phase=="handshaking"){
            if(frame.isResponse()){
                if(frame.sequence!=handshakeSequence)return
                if(frame.payload.size<5){failHandshake("相机连接应答长度不足");return}
                val result=DjiProtocol.u8(frame.payload[4]); if(result!=0){failHandshake("相机连接请求返回错误 $result");return}
                listener.onLog("相机已受理连接请求，等待相机授权结果");return
            }
            if(frame.payload.size<33 || DjiProtocol.u8(frame.payload[26])!=2){failHandshake("相机授权数据格式不符合预期");return}
            if(DjiProtocol.u16(frame.payload,27)!=0){failHandshake("相机拒绝连接，不会自动重复配对");return}
            val cameraId=DjiProtocol.u32(frame.payload,0)
            if(cameraId!=DjiProtocol.ACTION_4_DEVICE_ID){failHandshake("检测到的相机设备标识不是 Action 4：0x"+cameraId.toString(16));return}
            try{
                transport.send(DjiProtocol.connectionResponse(frame.sequence,controllerId));if(phase!="handshaking")return
                phase="ready";transport.send(DjiProtocol.subscribeStatus(nextSequence()));if(!isReady())return
                revision++;listener.onReady(true);listener.onLog("协议已连接，等待相机状态")
            }catch(error: Exception){failHandshake("连接确认发送失败："+safeMessage(error))}
            return
        }
        if(!isReady())return
        if(frame.`is`(0x1d,2) && !frame.isResponse()){
            if(frame.payload.size<7){listener.onLog("已忽略过短相机状态");return}
            val current=DjiProtocol.cameraStatus(frame,clock.nowMs());status=current
            statusGeneration++;revision++;lastKnownMode=current.mode;staleLogged=false;listener.onStatus(current)
            if(captureAction!=null)observeCapture()
            val operation=pending
            if(operation!=null)observeTarget(operation,false)
            else recovery?.let { if(clock.nowMs()>=recoveryDeadline){recovery=null;revision++}else observeTarget(it,true) }
            return
        }
        val action=captureAction
        if(action!=null && frame.isResponse() && frame.sequence==action.sequence && frame.`is`(0x1d,if(action.switching)4 else 3)){
            if(frame.payload.isEmpty())return
            if(DjiProtocol.u8(frame.payload[0])!=0){finishCapture("failed","相机拒绝切换或拍照，返回码 "+DjiProtocol.u8(frame.payload[0]));return}
            action.acked=true;action.ackGeneration=statusGeneration;revision++;return
        }
        if(frame.`is`(0x1d,3) && frame.isResponse()){
            val operation=pending?.takeIf { frame.sequence==it.sequence } ?: recovery?.takeIf { frame.sequence==it.sequence } ?: return
            if(frame.payload.isEmpty()){listener.onLog("录像命令应答长度不足，继续等待状态");return}
            operation.ackCode=DjiProtocol.u8(frame.payload[0]);revision++
            listener.onLog(if(operation.ackCode==0){if(operation.desired)"开始指令已受理，等待实际录像状态" else "停止指令已受理，等待相机结束录像及状态确认"}else "相机返回码 ${operation.ackCode}，继续核对实际录像状态")
        }
    }
    private fun observeTarget(operation: Pending, late: Boolean) {
        val current=status ?: return
        if(statusGeneration<=operation.statusGeneration)return
        val matches=if(operation.desired)current.isRecording() else current.isExplicitlyStopped()
        if(!matches){operation.clearEvidence();return}
        val now=clock.nowMs()
        if(operation.firstMatchingAt<0){operation.firstMatchingAt=now;operation.lastMatchingAt=now;operation.matchingSamples=1}
        else if(now-operation.lastMatchingAt>=TARGET_SAMPLE_GAP_MS){operation.lastMatchingAt=now;operation.matchingSamples++}
        if(operation.matchingSamples<2 || now-operation.firstMatchingAt<TARGET_SAMPLE_GAP_MS || now-operation.sentAt<operation.minimumSettleMs)return
        if(late)recovery=null else pending=null
        revision++;result(operation,"confirmed",(if(late)"相机迟到回报：" else "")+(if(operation.desired)"已确认开始录像" else "已确认停止录像"))
    }
    fun canTakePhoto(): Boolean {
        val current=status ?: return false
        return isReady() && pending==null && recovery==null && captureAction==null && getStatusAgeMs()<=STATUS_FRESH_MS && current.mode==5 && !current.isModeTransition() && (current.cameraState==1 || current.cameraState==2) && current.temperatureState<2
    }
    fun takePhoto(id: String): Boolean {
        require(id.isNotEmpty()){ "requestId required" }
        if(!canTakePhoto()){listener.onCommandResult(id,true,"rejected","相机拍照未就绪");return false}
        return beginCapture(id,true,status?.cameraState==2)
    }
    private fun beginCapture(id: String, photo: Boolean, switching: Boolean): Boolean {
        val action=CaptureAction(id,photo,clock.nowMs());captureAction=action;action.switching=switching;revision++
        return sendCapture(action)
    }
    private fun sendCapture(action: CaptureAction): Boolean {
        action.sequence=nextSequence();action.acked=false;action.ackGeneration=statusGeneration;action.baselinePhotos=status?.remainingPhotos ?: -1
        try{transport.send(if(action.switching)DjiProtocol.switchMode(action.sequence,if(action.photo)5 else 1)else DjiProtocol.recording(action.sequence,true))}
        catch(error: Exception){if(captureAction===action)finishCapture("unknown","拍摄命令发送未确认，不会重发："+safeMessage(error));return false}
        return captureAction===action && isReady()
    }
    private fun observeCapture() {
        val action=captureAction ?: return; val current=status ?: return
        if(clock.nowMs()>=action.deadline){finishCapture("unknown","切换或拍照结果未确认，不会重发");return}
        if(current.mode!=(if(action.photo)5 else 1)){finishCapture("unknown","相机模式已改变，已取消待执行拍摄");return}
        if(current.temperatureState>=2){finishCapture("rejected","相机过热，已取消待执行拍摄");return}
        if(action.switching){
            if(!action.acked || statusGeneration<=action.ackGeneration || current.cameraState!=1 || current.isModeTransition())return
            if(!action.photo){captureAction=null;revision++;setRecording(true,action.id)}
            else{action.switching=false;revision++;sendCapture(action)}
        }else{
            if(current.cameraState==3)action.sawCapture=true
            val countChanged=action.baselinePhotos>0 && current.remainingPhotos>=0 && current.remainingPhotos<action.baselinePhotos
            if(action.acked && statusGeneration>action.ackGeneration && current.cameraState==1 && (action.sawCapture || countChanged))finishCapture("confirmed","相机状态已确认拍照完成")
        }
    }
    private fun finishCapture(outcome: String, message: String) {
        val action=captureAction;captureAction=null;revision++
        if(action!=null)listener.onCommandResult(action.id,true,outcome,message)
    }
    fun setRecording(desired: Boolean, requestId: String): Boolean {
        require(requestId.isNotEmpty()){ "requestId required" }
        captureAction?.let {
            if(!desired && !it.photo){
                finishCapture("unknown","已取消待执行的开始录像，不会自动重发")
                if(status?.isRecording()!=true){listener.onCommandResult(requestId,false,"unknown","开始准备已取消，请以相机当前状态为准");return true}
            }else{listener.onCommandResult(requestId,desired,"rejected","上一拍摄请求仍在确认");return false}
        }
        pending?.takeIf { it.desired==desired }?.let {
            if(requestId !in it.requestIds)it.requestIds.add(requestId)
            listener.onLog(if(desired)"开始请求仍在确认，未重复发送" else "停止请求仍在确认，未重复发送");return true
        }
        val current=status
        if(pending==null && recovery==null && isReady() && current!=null && getStatusAgeMs()<=STATUS_FRESH_MS && current.hasKnownRecordingState() && current.isRecording()==desired){
            listener.onCommandResult(requestId,desired,"confirmed",if(desired)"相机最新状态已在录像" else "相机最新状态已停止录像");return true
        }
        if(if(desired)!canStartRecording() else !canStopRecording()){
            listener.onCommandResult(requestId,desired,"rejected",if(desired)"相机未就绪、状态过期或另一请求尚待确认" else "当前没有可停止的已知录像或未决开始请求");return false
        }
        if(desired && current?.cameraState==2)return beginCapture(requestId,false,true)
        val coversStart=!desired && (pending?.desired==true || recovery?.desired==true)
        val supersededWhilePending=pending!=null;val superseded=pending ?: recovery;recovery=null
        val seq=nextSequence();val operation=Pending(seq,requestId,desired,clock.nowMs(),statusGeneration,coversStart)
        pending=operation;revision++;lastStatusRecoveryAt=clock.nowMs()
        if(supersededWhilePending && superseded?.desired==true)result(superseded,"unknown","开始请求已被停止请求覆盖，不会自动重发开始")
        if(!isReady() || pending!==operation)return false
        try{transport.send(DjiProtocol.recording(seq,desired))}catch(error: Exception){
            if(pending===operation){pending=null;revision++;result(operation,"failed","命令未能发送："+safeMessage(error))};return false
        }
        if(!isReady())return false
        listener.onLog(if(desired)"已发送开始录像，等待相机确认" else "已发送停止录像，正在等待相机停止及状态确认");return true
    }
    fun tick(nowMs: Long) {
        captureAction?.let { if(nowMs>=it.deadline)finishCapture("unknown","切换或拍照结果未确认，不会重发") }
        if(phase=="handshaking" && nowMs>=handshakeDeadline){failHandshake("等待相机授权超时，请在相机上确认后重新连接");return}
        pending?.let { timedOut -> if(nowMs>=timedOut.deadline){
            pending=null;recovery=timedOut;timedOut.clearEvidence();recoveryDeadline=nowMs+LATE_CONFIRMATION_MS;revision++
            val current=status
            val knownRejected=timedOut.ackCode>0 && current!=null && getStatusAgeMs()<=STATUS_FRESH_MS && current.hasKnownRecordingState() && current.isRecording()!=timedOut.desired
            result(timedOut,if(knownRejected)"failed" else "unknown",if(knownRejected)"相机返回错误码 ${timedOut.ackCode}，目标状态未达成；将继续同步" else if(timedOut.desired)"开始结果尚未确认，将继续同步相机状态；不会自动重发" else "停止结果仍未确认，将继续同步；请查看相机实际状态")
        } }
        if(recovery!=null && nowMs>=recoveryDeadline){recovery=null;revision++;listener.onLog("迟到确认窗口已结束，请以相机最新状态为准")}
        if(isReady() && (pending!=null || recovery!=null || captureAction!=null) && getStatusAgeMs()>STATUS_RECOVERY_INTERVAL_MS && nowMs-lastStatusRecoveryAt>=STATUS_RECOVERY_INTERVAL_MS){
            lastStatusRecoveryAt=nowMs
            try{transport.send(DjiProtocol.subscribeStatus(nextSequence()))}catch(error: Exception){listener.onLog("状态同步请求未能发送："+safeMessage(error))}
        }
        if(isReady() && status!=null && getStatusAgeMs()>STATUS_FRESH_MS && !staleLogged){
            staleLogged=true;listener.onLog(if(pending?.desired==false)"停止请求已发送，正在恢复相机状态回报" else "相机状态已过期，等待新状态；未确认的开始仍可请求停止")
        }
    }
    fun disconnect() {
        phase="disconnected";if(captureAction!=null)finishCapture("unknown","连接已断开，未确认拍摄不会重发")
        val interrupted=pending;pending=null;recovery=null;status=null;lastKnownMode=-1;decoder.reset();revision++
        if(interrupted!=null)result(interrupted,"unknown","连接已断开，录像结果未确认；不会重发旧指令")
        listener.onReady(false)
    }
    private fun failHandshake(reason: String) {
        phase="failed";if(captureAction!=null)finishCapture("unknown","授权已失效，未确认拍摄不会重发")
        status=null;lastKnownMode=-1;decoder.reset();revision++;listener.onReady(false);listener.onLog(reason);listener.onDisconnectRequired(reason)
    }
    private fun result(operation: Pending, outcome: String, message: String) { for(id in operation.requestIds.toList())listener.onCommandResult(id,operation.desired,outcome,message) }
    private fun safeMessage(error: Exception)=error.message ?: error.javaClass.simpleName
}
