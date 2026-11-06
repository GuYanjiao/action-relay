package cn.local.actionrelay

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Preserve the pinned component identity; the tile only enables the relay. */
class RecordingTileService: TileService(),RelayHub.UiListener {
    private lateinit var hub: RelayHub
    private var listening=false
    companion object {
        fun requestAdd(activity: Activity){
            if(Build.VERSION.SDK_INT>=33){
                val manager=activity.getSystemService(StatusBarManager::class.java)
                if(manager!=null)try{
                    manager.requestAddTileService(ComponentName(activity,RecordingTileService::class.java),"Action 中转",Icon.createWithResource(activity,R.drawable.ic_relay_tile),activity.mainExecutor){result ->
                        if(result==StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED)Toast.makeText(activity,"也可在控制栏编辑中添加 Action 中转",Toast.LENGTH_LONG).show()
                    };return
                }catch(_: RuntimeException){}
            }
            try{activity.startActivity(Intent("android.settings.QUICK_SETTINGS_SETTINGS"))}catch(_: RuntimeException){activity.startActivity(Intent(Settings.ACTION_SETTINGS))}
        }
    }
    override fun onStartListening(){super.onStartListening();hub=(application as RelayApp).hub;listening=true;hub.addListener(this);update()}
    override fun onStopListening(){listening=false;if(::hub.isInitialized)hub.removeListener(this);super.onStopListening()}
    override fun onDestroy(){if(::hub.isInitialized)hub.removeListener(this);super.onDestroy()}
    override fun onChanged(){if(listening)update()}
    override fun onClick(){hub=(application as RelayApp).hub;if(hub.enabled){update();return};if(isLocked)unlockAndRun(::enableRelay)else enableRelay()}
    private fun enableRelay(){
        if(hub.enabled){update();return}
        var permissions=checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
        if(Build.VERSION.SDK_INT>=33)permissions=permissions && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
        if(!permissions || hub.gpsEnabled || !hub.ble.bluetoothEnabled()){openForStartup();return}
        try{startForegroundService(Intent(this,RelayService::class.java).setAction(RelayService.START))}
        catch(error: RuntimeException){hub.log("快捷中转启动需返回应用："+error.javaClass.simpleName);openForStartup()};update()
    }
    private fun openForStartup(){
        val intent=Intent(this,MainActivity::class.java).setAction(MainActivity.START_RELAY_FROM_TILE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,7,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))else startActivityAndCollapse(intent)
    }
    private fun update(){
        val tile=qsTile ?: return;if(!::hub.isInitialized)return
        tile.label="Action 中转";tile.subtitle=if(hub.enabled)"已开启"else "点击开启";tile.icon=Icon.createWithResource(this,R.drawable.ic_relay_tile)
        tile.state=if(hub.enabled)Tile.STATE_ACTIVE else Tile.STATE_INACTIVE;tile.updateTile()
    }
}
