package cn.local.actionrelay

import android.app.Application

class RelayApp: Application() {
    lateinit var hub: RelayHub; private set
    override fun onCreate(){super.onCreate();hub=RelayHub(this)}
}
