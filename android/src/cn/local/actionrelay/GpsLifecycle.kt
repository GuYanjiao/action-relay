package cn.local.actionrelay

class GpsLifecycle(private val driver: Driver) {
    interface Driver { fun start(): Boolean; fun stop() }
    private var active = false
    fun reconfigure(relayEnabled: Boolean, requested: Boolean, cameraReady: Boolean) {
        if (!relayEnabled || !requested || !cameraReady) { stop(); return }
        if (!active) active = driver.start()
    }
    fun stop() { driver.stop(); active = false }
}
