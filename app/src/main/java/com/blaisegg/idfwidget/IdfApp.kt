package com.blaisegg.idfwidget

import android.app.Application
import com.blaisegg.idfwidget.data.PrimRepository
import com.blaisegg.idfwidget.data.SettingsStore

/** Conteneur de dépendances minimal (pas besoin de Hilt pour un projet de cette taille). */
class IdfApp : Application() {
    val settings by lazy { SettingsStore(this) }
    val repository by lazy { PrimRepository(index = { com.blaisegg.idfwidget.data.StationIndex.get(this) }) }
}

val android.content.Context.app: IdfApp get() = applicationContext as IdfApp
