package com.blaisegg.wifihome

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button

/** Full-screen alert shown on arrival. Closes itself once Wi-Fi is on. */
class AlertActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alert)
        findViewById<Button>(R.id.btnWifi).setOnClickListener {
            startActivity(Notifier.wifiIntent())
        }
        findViewById<Button>(R.id.btnLater).setOnClickListener {
            Notifier.cancelArrived(this)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifi.isWifiEnabled) {
            Notifier.cancelArrived(this)
            finish()
        }
    }
}
