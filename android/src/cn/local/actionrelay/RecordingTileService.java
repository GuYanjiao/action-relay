package cn.local.actionrelay;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Relay startup only. Keep the legacy component name so already pinned tiles update in place. */
public final class RecordingTileService extends TileService implements RelayHub.UiListener {
    private RelayHub hub;
    private boolean listening;
    public static void requestAdd(Activity activity) {
        if(Build.VERSION.SDK_INT>=33){
            StatusBarManager manager=activity.getSystemService(StatusBarManager.class);
            if(manager!=null)try{
                manager.requestAddTileService(new ComponentName(activity,RecordingTileService.class),
                    "Action 中转",Icon.createWithResource(activity,R.drawable.ic_relay_tile),activity.getMainExecutor(),
                    result->{if(result==StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED)
                        Toast.makeText(activity,"也可在控制栏编辑中添加 Action 中转",Toast.LENGTH_LONG).show();});
                return;
            }catch(RuntimeException ignored){}
        }
        try { activity.startActivity(new Intent("android.settings.QUICK_SETTINGS_SETTINGS")); }
        catch (RuntimeException ignored) { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }
    @Override public void onStartListening(){
        super.onStartListening();hub=((RelayApp)getApplication()).hub;listening=true;hub.addListener(this);update();
    }
    @Override public void onStopListening(){listening=false;if(hub!=null)hub.removeListener(this);super.onStopListening();}
    @Override public void onDestroy(){if(hub!=null)hub.removeListener(this);super.onDestroy();}
    @Override public void onChanged(){if(listening)update();}
    @Override public void onClick(){
        hub=((RelayApp)getApplication()).hub;
        if(hub.enabled){update();return;}
        if(isLocked())unlockAndRun(this::enableRelay);else enableRelay();
    }
    private void enableRelay(){
        if(hub.enabled){update();return;}
        boolean permissions=checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
            &&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;
        if(Build.VERSION.SDK_INT>=33)permissions=permissions&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        // Location while-in-use startup and missing permissions need a visible Activity.
        if(!permissions||hub.gpsEnabled||!hub.ble.bluetoothEnabled()){openForStartup();return;}
        try{startForegroundService(new Intent(this,RelayService.class).setAction(RelayService.START));}
        catch(RuntimeException error){hub.log("快捷中转启动需返回应用："+error.getClass().getSimpleName());openForStartup();}
        update();
    }
    private void openForStartup(){
        Intent intent=new Intent(this,MainActivity.class).setAction(MainActivity.START_RELAY_FROM_TILE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,7,intent,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        else startActivityAndCollapse(intent);
    }
    private void update(){
        Tile tile=getQsTile();if(tile==null||hub==null)return;
        tile.setLabel("Action 中转");tile.setSubtitle(hub.enabled?"已开启":"点击开启");
        tile.setIcon(Icon.createWithResource(this,R.drawable.ic_relay_tile));
        tile.setState(hub.enabled?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);tile.updateTile();
    }
}
