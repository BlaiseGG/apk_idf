package com.blaisegg.idfwidget.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.start(appWidgetId = null)
        setContent {
            IdfTheme { AppScreen(vm, widgetMode = false, onExit = ::finish) }
        }
    }
}
