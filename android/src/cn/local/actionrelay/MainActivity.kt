package cn.local.actionrelay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.util.Date
import java.util.Locale

/** UI observes the hub only while visible; it owns no background polling or radio work. */
class MainActivity: ComponentActivity(),RelayHub.UiListener {
    companion object {const val START_RELAY_FROM_TILE="cn.local.actionrelay.START_RELAY_FROM_TILE"}
    private lateinit var hub: RelayHub
    private var pendingTileStart=false
    private var resumed=false
    private var pendingGpsReconfigure=false
    private var update by mutableIntStateOf(0)
    private val nearbyPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        if(hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT))startRelay()
        else toast("需要附近设备权限，可在系统设置中修改")
    }
    private val gpsPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        if(hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && hub.enabled){hub.setGpsEnabled(true);reconfigureGps()}
        else{hub.setGpsEnabled(false);toast("GPS需要精确位置权限")};onChanged()
    }
    private val enableBluetooth=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){
        if(it.resultCode==RESULT_OK && !hub.enabled)requestStart()
    }
    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState);hub=(application as RelayApp).hub;consumeStartupIntent(intent);enableEdgeToEdge()
        setContent {MiuixTheme(controller=remember{ThemeController(ColorSchemeMode.System)}){RelayUi()}}
    }
    override fun onResume(){
        super.onResume();resumed=true;hub.addListener(this);hub.onEnvironmentChanged()
        if(pendingGpsReconfigure)reconfigureGps();onChanged()
        if(pendingTileStart){pendingTileStart=false;if(!hub.enabled)requestStart()}
    }
    override fun onPause(){resumed=false;hub.removeListener(this);super.onPause()}
    override fun onNewIntent(intent: Intent){super.onNewIntent(intent);setIntent(intent);consumeStartupIntent(intent);if(resumed && pendingTileStart){pendingTileStart=false;if(!hub.enabled)requestStart()}}
    private fun consumeStartupIntent(intent: Intent?){if(intent?.action==START_RELAY_FROM_TILE){pendingTileStart=true;intent.action=Intent.ACTION_MAIN}}
    override fun onChanged(){update++}
    private fun hasPermission(permission: String)=checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
    private fun requestStart(){
        val needed=mutableListOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
        if(Build.VERSION.SDK_INT>=33)needed+=Manifest.permission.POST_NOTIFICATIONS
        val missing=needed.filterNot(::hasPermission)
        if(missing.isNotEmpty())nearbyPermission.launch(missing.toTypedArray())else startRelay()
    }
    private fun startRelay(){
        if(!hub.ble.bluetoothEnabled()){enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));toast("打开蓝牙后继续开启中转");return}
        startForegroundService(Intent(this,RelayService::class.java).setAction(RelayService.START))
    }
    private fun requestGps(value: Boolean){
        if(value==hub.gpsEnabled)return
        if(!value){hub.setGpsEnabled(false);reconfigureGps();return}
        if(!hub.enabled){toast("先开启手机中转，再打开GPS");requestStart();return}
        if(!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)){gpsPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION));return}
        hub.setGpsEnabled(true);reconfigureGps()
    }
    private fun reconfigureGps(){
        if(!resumed){pendingGpsReconfigure=true;return};pendingGpsReconfigure=false;if(!hub.enabled)return
        try{startForegroundService(Intent(this,RelayService::class.java).setAction(RelayService.RECONFIGURE))}
        catch(error: RuntimeException){toast("GPS暂未启动，请回到应用后重试");hub.setGpsStartError("无法启动定位服务："+error.javaClass.simpleName);hub.log("界面启动GPS服务失败："+error.javaClass.simpleName)}
    }
    private fun confirm(title: String, message: String, action: String, callback: () -> Unit){AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(action){_,_->callback()}.setNegativeButton("取消",null).show()}
    private fun closeRelay()=confirm("关闭手机中转？","会断开遥控连接。相机若正在录像，会继续录像。","关闭中转"){startService(Intent(this,RelayService::class.java).setAction(RelayService.STOP))}
    private fun scanCamera(){
        if(!hub.enabled){toast("先开启手机中转");requestStart();return}
        if(!hub.ble.bluetoothEnabled()){enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));return};hub.scan()
    }
    private fun openAppSettings(){startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}
    private fun openCompanionSettings(){
        for(pkg in listOf("com.mi.health","com.xiaomi.wearable")){
            try{packageManager.getPackageInfo(pkg,0)}catch(_: Exception){continue}
            try{startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$pkg")));return}catch(_: Exception){}
        };toast("未找到小米运动健康")
    }
    private fun showKeepConnectedHelp(){AlertDialog.Builder(this).setTitle("保持手表连接")
        .setMessage("在系统设置中允许本应用和小米运动健康后台运行，将省电策略设为无限制，并允许自启动。可在多任务界面锁定小米运动健康。\n\n首次同意通信授权后，本应用会自动检测手表连接。")
        .setPositiveButton("打开小米运动健康设置"){_,_->openCompanionSettings()}.setNegativeButton("知道了",null).show()}
    private fun toast(text: String){Toast.makeText(this,text,Toast.LENGTH_LONG).show()}

    @Composable private fun RelayUi(){
        // The single observer notification captures one consistent view; no UI timer survives pause.
        val snapshot=remember(update){hub.snapshot()}
        var page by rememberSaveable{mutableStateOf("home")}
        BackHandler(page!="home"){page="home"}
        val colors=MiuixTheme.colorScheme
        Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                if(page!="home")Button(onClick={page="home"},modifier=Modifier.size(56.dp)){Glyph("back",colors.onSurface)}
                Column(Modifier.weight(1f)){
                    Text(when(page){"camera"->"相机";"gps"->"手机 GPS";"tools"->"工具";"logs"->"连接记录";else->"Action"},fontSize=34.sp,fontWeight=FontWeight.Bold)
                    Text(if(page=="home")"相机遥控"else "Action 中转",fontSize=14.sp,color=colors.onBackgroundVariant)
                }
                if(page=="home")Button(onClick={if(hub.enabled)closeRelay()else requestStart()},modifier=Modifier.size(68.dp).semantics{contentDescription=if(hub.enabled)"关闭手机中转"else "开启手机中转"}){Glyph("power",if(hub.enabled)colors.primary else colors.onSurface)}
            }
            when(page){
                "home" -> {
                    val ready=hub.phase=="ready"
                    val watch=snapshot.optBoolean("watchConnected")
                    val battery=snapshot.optInt("battery",-1)
                    val success=Color(0xff24a879);val muted=colors.onSurfaceVariantSummary
                    Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(22.dp)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("ACTION 4",fontSize=16.sp,fontWeight=FontWeight.Medium);Text(if(hub.enabled)"中转开启"else "中转关闭",color=if(hub.enabled)colors.primary else muted,fontSize=13.sp)}
                        Spacer(Modifier.height(10.dp))
                        Text(when(hub.phase){"ready"->"已连接";"authorizing"->String.format(Locale.ROOT,"确认配对 · %04d",hub.verificationCode);"connecting"->"连接中";else->"等待连接"},fontSize=25.sp,fontWeight=FontWeight.SemiBold)
                        Spacer(Modifier.height(24.dp))
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                            Indicator("watch",if(watch)"已连接"else "未连接",if(watch)success else muted){hub.bridge.openAuthorization(this@MainActivity)}
                            Indicator("camera",if(ready)"已连接"else "未连接",if(ready)success else muted){page="camera"}
                            Indicator("battery",if(battery>=0)"$battery%"else "—",if(battery in 0..20)colors.error else muted)
                            Indicator("gps",gpsTitle(),if(hub.gpsState=="ready")success else if(hub.gpsEnabled)colors.primary else muted){page="gps"}
                        }
                    }
                    val commandPhase=snapshot.optString("recordPhase")
                    val recording=snapshot.opt("recording")==true
                    Text(when{commandPhase=="stopping"->"正在确认停止";commandPhase=="starting"->"正在确认开始";recording->"相机正在录像";snapshot.optBoolean("canStart")->"相机已待机";!hub.enabled->"开启中转后连接相机";else->"等待相机状态"},modifier=Modifier.align(Alignment.CenterHorizontally),color=if(recording)colors.error else colors.onBackgroundVariant,fontSize=15.sp)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        ToolButton("camera","相机",Modifier.weight(1f)){page="camera"}
                        ToolButton("gps","GPS",Modifier.weight(1f)){page="gps"}
                        ToolButton("more","工具",Modifier.weight(1f)){page="tools"}
                    }
                    Text("S4 · 手机中转",modifier=Modifier.align(Alignment.CenterHorizontally),color=colors.onBackgroundVariant,fontSize=12.sp)
                }
                "camera" -> {
                    Text(snapshot.optString("message"),color=colors.onBackgroundVariant)
                    ActionRow("search","搜索相机",::scanCamera)
                    if(hub.rememberedCameraAddress.isNotEmpty()){
                        ActionRow("refresh","重连 · "+hub.rememberedCameraName.ifEmpty{"上次的相机"}){hub.requestReconnect();if(!hub.enabled)requestStart()}
                        if(hub.rememberedCameraLastSuccess>0){
                            val date=Date(hub.rememberedCameraLastSuccess)
                            Text("上次连接 · "+android.text.format.DateFormat.getDateFormat(this@MainActivity).format(date)+" "+android.text.format.DateFormat.getTimeFormat(this@MainActivity).format(date),color=colors.onBackgroundVariant,fontSize=13.sp)
                        }
                        ActionRow("close","忘记相机"){confirm("忘记这台相机？","下次需重新搜索并在相机确认配对。","忘记",hub::forgetCamera)}
                    }
                    hub.candidates.values.toList().forEach{candidate -> key(candidate.address){ActionRow("camera",candidate.name){hub.connect(candidate.address)}}}
                    Text("首次连接，请在相机确认并核对验证码。",fontSize=13.sp,color=colors.onBackgroundVariant)
                }
                "gps" -> {
                    ToggleRow("将定位发送到相机",hub.gpsEnabled,::requestGps)
                    Text(hub.gpsMessage,color=colors.onBackgroundVariant)
                    if(hub.gpsAccuracy>=0)Text(String.format(Locale.ROOT,"精度 ±%.0f m",hub.gpsAccuracy))
                    if(hub.gpsSpeed>=0)Text(String.format(Locale.ROOT,"速度 %.1f km/h",hub.gpsSpeed*3.6f))
                    ActionRow("settings","位置权限设置",::openAppSettings)
                    Text("请在户外定位。GPS就绪表示手机定位可发送；视频仪表盘以相机实际素材为准。",fontSize=13.sp,color=colors.onBackgroundVariant)
                }
                "tools" -> {
                    ActionRow("watch","手表通信授权"){hub.bridge.openAuthorization(this@MainActivity)}
                    ActionRow("info","保持手表连接",::showKeepConnectedHelp)
                    ActionRow("tile","添加中转磁贴"){RecordingTileService.requestAdd(this@MainActivity)}
                    ToggleRow("自动重连",hub.autoReconnectEnabled,hub::setAutoReconnect,"仅重连相机，不自动录像")
                    ActionRow("settings","系统设置",::openAppSettings)
                    ActionRow("more","连接记录"){page="logs"}
                    ActionRow("refresh","重新配对相机"){confirm("重新配对相机？","清除本应用保存的相机授权并断开连接。","重新配对",hub::forgetCamera)}
                    Text("Action 中转 · ${BuildConfig.VERSION_NAME}",fontSize=12.sp,color=colors.onBackgroundVariant)
                }
                "logs" -> SelectionContainer{Text(hub.logs.joinToString("\n\n"),fontSize=12.sp,fontFamily=FontFamily.Monospace)}
            }
        }
    }
    private fun gpsTitle(): String=when{
        !hub.gpsEnabled->"关闭";!hub.enabled->"暂停";hub.gpsState=="waiting_camera"->"等待相机";hub.gpsState=="ready"->"已定位"
        hub.gpsState=="permission" || hub.gpsState=="denied"->"未授权";hub.gpsState=="provider_off"->"请打开定位";hub.gpsState=="stale"->"已过期";hub.gpsState=="unavailable"->"启动失败";else->"定位中"
    }
    @Composable private fun Indicator(icon: String, label: String, color: Color, onClick: (() -> Unit)?=null){
        Column(Modifier.widthIn(min=56.dp).then(if(onClick!=null)Modifier.clickable(onClick=onClick)else Modifier).padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
            Glyph(icon,color);Text(label,color=color,fontSize=12.sp)
        }
    }
    @Composable private fun ToolButton(icon: String, label: String, modifier: Modifier, action: () -> Unit){
        Button(onClick=action,modifier=modifier.heightIn(min=104.dp)){
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Glyph(icon,MiuixTheme.colorScheme.onSurface);Text(label,fontSize=14.sp)}
        }
    }
    @Composable private fun ActionRow(icon: String, title: String, action: () -> Unit){
        Button(onClick=action,modifier=Modifier.fillMaxWidth().heightIn(min=68.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
                Glyph(icon,MiuixTheme.colorScheme.primary);Text(title,Modifier.weight(1f),fontSize=17.sp);Text("›",fontSize=24.sp,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
    @Composable private fun ToggleRow(title: String, checked: Boolean, action: (Boolean)->Unit, subtitle: String?=null){
        Card(Modifier.fillMaxWidth(),insideMargin=PaddingValues(18.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
                Column(Modifier.weight(1f)){Text(title,fontSize=17.sp);if(subtitle!=null)Text(subtitle,fontSize=12.sp,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)}
                Switch(checked=checked,onCheckedChange=action,modifier=Modifier.semantics{contentDescription=title})
            }
        }
    }
    @Composable private fun Glyph(kind: String, color: Color){
        Canvas(Modifier.size(28.dp)){
            scale(size.width/24f,size.height/24f,pivot=Offset.Zero){
                val stroke=Stroke(1.8f,cap=StrokeCap.Round)
                fun line(x1: Float,y1: Float,x2: Float,y2: Float)=drawLine(color,Offset(x1,y1),Offset(x2,y2),1.8f,StrokeCap.Round)
                when(kind){
                    "power"->{drawArc(color,-50f,280f,false,Offset(4f,4f),Size(16f,16f),style=stroke);line(12f,2f,12f,11f)}
                    "watch"->{drawRoundRect(color,Offset(6f,5f),Size(12f,14f),CornerRadius(3f),style=stroke);line(9f,2f,15f,2f);line(9f,22f,15f,22f);line(11f,8f,11f,12f);line(11f,12f,14f,14f)}
                    "camera"->{drawRoundRect(color,Offset(2.5f,5f),Size(19f,15f),CornerRadius(3f),style=stroke);drawCircle(color,4f,Offset(14.5f,12.5f),style=stroke);line(5.5f,8f,8.5f,8f)}
                    "gps"->{drawCircle(color,7f,Offset(12f,12f),style=stroke);drawCircle(color,2.5f,Offset(12f,12f),style=stroke);line(12f,2f,12f,5f);line(12f,19f,12f,22f);line(2f,12f,5f,12f);line(19f,12f,22f,12f)}
                    "battery"->{drawRoundRect(color,Offset(2f,6f),Size(18f,12f),CornerRadius(2f),style=stroke);line(22f,10f,22f,14f);for(x in 5..17 step 3)line(x.toFloat(),9f,x.toFloat(),15f)}
                    "search"->{drawCircle(color,6f,Offset(10f,10f),style=stroke);line(14.5f,14.5f,21f,21f)}
                    "refresh"->{drawArc(color,30f,285f,false,Offset(4f,4f),Size(16f,16f),style=stroke);line(20f,3f,20f,9f);line(20f,9f,14f,9f)}
                    "close"->{line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
                    "back"->{line(15f,5f,8f,12f);line(8f,12f,15f,19f)}
                    "tile"->{for(x in 3..14 step 11)for(y in 3..14 step 11)drawRoundRect(color,Offset(x.toFloat(),y.toFloat()),Size(7f,7f),CornerRadius(1.5f),style=stroke)}
                    "settings","info"->{drawCircle(color,8f,Offset(12f,12f),style=stroke);drawCircle(color,2.5f,Offset(12f,12f),style=stroke)}
                    else->{for(x in 5..19 step 7)drawCircle(color,1.5f,Offset(x.toFloat(),12f))}
                }
            }
        }
    }
}
