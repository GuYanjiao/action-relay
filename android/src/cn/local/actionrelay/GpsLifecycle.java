package cn.local.actionrelay;

/** Coordinates only the GPS subscription; callers must establish the foreground service first. */
public final class GpsLifecycle {
    public interface Driver { boolean start(); void stop(); }
    private final Driver driver;
    private boolean active;
    public GpsLifecycle(Driver driver) { this.driver=driver; }
    public void reconfigure(boolean relayEnabled,boolean requested) {
        if(!relayEnabled||!requested) { stop(); return; }
        if(!active) active=driver.start();
    }
    public void stop() { driver.stop(); active=false; }
}
