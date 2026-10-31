package com.blaisegg.idfwidget.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.blaisegg.idfwidget.live.LiveTracker
import com.blaisegg.idfwidget.widget.PassagesWidget
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var pendingLiveWidgetId: Int? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startLive(pendingLiveWidgetId)
            else Toast.makeText(this, "Autorisez les notifications pour le suivi en direct.", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.start(appWidgetId = null)
        handleIntent(intent)
        setContent {
            IdfTheme {
                AppScreen(
                    vm,
                    widgetMode = false,
                    onExit = ::finish,
                    onToggleLive = { if (LiveTracker.isActive(this)) stopLive() else requestLive(null) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshLiveState()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_LIVE, false) == true) {
            intent.removeExtra(EXTRA_START_LIVE)
            requestLive(intent.getIntExtra(EXTRA_WIDGET_ID, -1).takeIf { it >= 0 })
        }
    }

    private fun requestLive(widgetId: Int?) {
        if (LiveTracker.hasPermission(this)) {
            startLive(widgetId)
        } else if (Build.VERSION.SDK_INT >= 33) {
            pendingLiveWidgetId = widgetId
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startLive(widgetId: Int?) {
        LiveTracker.start(this, widgetId)
        Toast.makeText(this, "Suivi en direct démarré (1 h max)", Toast.LENGTH_SHORT).show()
        afterLiveChange()
    }

    private fun stopLive() {
        LiveTracker.stop(this)
        afterLiveChange()
    }

    private fun afterLiveChange() {
        vm.refreshLiveState()
        lifecycleScope.launch { PassagesWidget().updateAll(this@MainActivity) }
    }

    companion object {
        const val EXTRA_START_LIVE = "start_live"
        const val EXTRA_WIDGET_ID = "live_widget_id"

        fun startLiveIntent(context: Context, appWidgetId: Int): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_START_LIVE, true)
                .putExtra(EXTRA_WIDGET_ID, appWidgetId)
                .setData(Uri.parse("idfwidget://live/$appWidgetId"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
