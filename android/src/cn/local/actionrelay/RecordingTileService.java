package cn.local.actionrelay;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** A conservative tile: it only issues an explicit start/stop when camera state is known. */
public final class RecordingTileService extends TileService {
    public static void requestAdd(Activity activity) {
        Intent intent = new Intent("android.settings.QUICK_SETTINGS_SETTINGS");
        try { activity.startActivity(intent); }
        catch (RuntimeException ignored) { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    @Override public void onStartListening() { super.onStartListening(); update(); }
    @Override public void onClick() {
        RelayHub hub = ((RelayApp)getApplication()).hub;
        if (!hub.enabled) {
            Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            else startActivityAndCollapse(intent);
            return;
        }
        if (hub.snapshot().optBoolean("canStop")) hub.phoneCommand(false);
        else if (hub.snapshot().optBoolean("canStart")) hub.phoneCommand(true);
        else {
            Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            else startActivityAndCollapse(intent);
        }
        update();
    }
    private void update() {
        if (getQsTile() == null) return;
        RelayHub hub = ((RelayApp)getApplication()).hub;
        org.json.JSONObject state = hub.snapshot();
        boolean recording = state.optBoolean("recording", false);
        boolean ready = state.optBoolean("canStart") || state.optBoolean("canStop");
        getQsTile().setLabel(recording ? "停止录像" : "开始录像");
        getQsTile().setState(ready ? Tile.STATE_ACTIVE : Tile.STATE_UNAVAILABLE);
        getQsTile().updateTile();
    }
}
