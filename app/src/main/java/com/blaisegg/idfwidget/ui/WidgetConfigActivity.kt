package com.blaisegg.idfwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Configuration d'un widget précis : lancée par le launcher à l'ajout du widget,
 * ou par l'icône ⚙ du widget (reconfiguration).
 */
class WidgetConfigActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Si l'utilisateur quitte sans valider, le launcher n'ajoute pas le widget.
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_CANCELED, result)

        vm.start(appWidgetId)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.finished) {
                if (state.finished) {
                    setResult(RESULT_OK, result)
                    finish()
                }
            }
            IdfTheme { AppScreen(vm, widgetMode = true, onExit = ::finish) }
        }
    }

    companion object {
        const val EXTRA_RECONFIGURE = "reconfigure"
    }
}
