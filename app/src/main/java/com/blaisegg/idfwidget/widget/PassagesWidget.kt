package com.blaisegg.idfwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.blaisegg.idfwidget.R
import com.blaisegg.idfwidget.live.LiveTracker
import com.blaisegg.idfwidget.ui.MainActivity
import com.blaisegg.idfwidget.ui.WidgetConfigActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF0B1F3A)
private val NavyLight = Color(0xFF16305A)
private val Yellow = Color(0xFFFFCD00)
private val White = Color.White
private val Grey = Color(0xFFB8C4D6)
private val ErrorRed = Color(0xFFFF8A80)

/** Widget "panneau de quai" : arrêt, ligne, direction et les 2 prochains passages. */
class PassagesWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            WidgetContent(currentState(), appWidgetId)
        }
    }
}

@Composable
private fun WidgetContent(prefs: Preferences, appWidgetId: Int) {
    val context = LocalContext.current
    val size = LocalSize.current
    val compact = size.height < 100.dp
    // Assez de hauteur : rangée de gros boutons en bas. Sinon : boutons agrandis dans l'en-tête.
    val bottomBar = size.height >= 125.dp
    val showLabels = size.width >= 320.dp
    val status = prefs.status()

    val refreshAction = actionRunCallback<RefreshAction>()
    val liveActive = LiveTracker.isActive(context)
    val liveAction = if (LiveTracker.hasPermission(context)) {
        actionRunCallback<ToggleLiveAction>()
    } else {
        // La permission de notification ne peut être demandée que depuis l'app.
        actionStartActivity(MainActivity.startLiveIntent(context, appWidgetId))
    }
    val settingsAction = actionStartActivity(configIntent(context, appWidgetId))
    val locateAction = actionStartActivity(configIntent(context, appWidgetId, locate = true))

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Navy)
            .cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp)
            .clickable(refreshAction),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (status == Status.NOT_CONFIGURED) {
            NotConfigured(context, appWidgetId)
            return@Column
        }

        // ---- En-tête : pastille ligne + arrêt / direction ----
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            LineBadge(
                code = prefs[WidgetKeys.lineCode].orEmpty(),
                bg = parseColor(prefs[WidgetKeys.lineColor], NavyLight),
                fg = parseColor(prefs[WidgetKeys.lineTextColor], White),
            )
            Spacer(GlanceModifier.width(8.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = prefs[WidgetKeys.stopName].orEmpty(),
                    style = TextStyle(color = ColorProvider(White), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    text = "→ " + prefs[WidgetKeys.direction].orEmpty(),
                    style = TextStyle(color = ColorProvider(Grey), fontSize = 12.sp),
                    maxLines = 1,
                )
            }
            if (!bottomBar) {
                // Petit widget : boutons carrés de 40 dp, bien espacés.
                HeaderButton(R.drawable.ic_refresh, "Rafraîchir", White, refreshAction, loading = status == Status.LOADING)
                Spacer(GlanceModifier.width(6.dp))
                HeaderButton(R.drawable.ic_location, "Localiser", White, locateAction)
                Spacer(GlanceModifier.width(6.dp))
                HeaderButton(R.drawable.ic_live, "Suivi en direct", if (liveActive) Yellow else White, liveAction)
                Spacer(GlanceModifier.width(6.dp))
                HeaderButton(R.drawable.ic_settings, "Configurer", White, settingsAction)
            }
        }

        if (!compact) Spacer(GlanceModifier.height(4.dp))

        // ---- Zone principale : "02 min - 10 min" ----
        val (mainText, mainColor) = mainLine(prefs, status)
        Text(
            text = mainText,
            modifier = GlanceModifier.fillMaxWidth(),
            style = TextStyle(
                color = ColorProvider(mainColor),
                fontSize = if (status == Status.OK || mainColor == Yellow) (if (compact) 22.sp else 30.sp) else 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            maxLines = 2,
        )

        // ---- Pied : heure de mise à jour / état ----
        if (!compact) {
            Text(
                text = footer(prefs, status),
                modifier = GlanceModifier.fillMaxWidth(),
                style = TextStyle(color = ColorProvider(Grey), fontSize = 11.sp, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }

        // ---- Rangée de gros boutons ----
        if (bottomBar) {
            Spacer(GlanceModifier.height(6.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                BarButton(
                    R.drawable.ic_refresh, if (status == Status.LOADING) "…" else "Actualiser", White,
                    refreshAction, showLabels, loading = status == Status.LOADING,
                )
                Spacer(GlanceModifier.width(8.dp))
                BarButton(R.drawable.ic_location, "Localiser", White, locateAction, showLabels)
                Spacer(GlanceModifier.width(8.dp))
                BarButton(
                    R.drawable.ic_live, if (liveActive) "Suivi actif" else "Suivi", if (liveActive) Yellow else White,
                    liveAction, showLabels,
                )
                Spacer(GlanceModifier.width(8.dp))
                BarButton(R.drawable.ic_settings, "Réglages", White, settingsAction, showLabels)
            }
        }
    }
}

/** Bouton large (1/3 de la largeur, 40 dp de haut) pour la rangée du bas. */
@Composable
private fun RowScope.BarButton(
    icon: Int,
    label: String,
    tint: Color,
    action: Action,
    showLabel: Boolean,
    loading: Boolean = false,
) {
    Row(
        modifier = GlanceModifier
            .defaultWeight()
            .height(40.dp)
            .background(NavyLight)
            .cornerRadius(12.dp)
            .clickable(action),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = GlanceModifier.size(18.dp), color = ColorProvider(Yellow))
        } else {
            Image(
                provider = ImageProvider(icon),
                contentDescription = label,
                colorFilter = ColorFilter.tint(ColorProvider(tint)),
                modifier = GlanceModifier.size(20.dp),
            )
        }
        if (showLabel) {
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = label,
                style = TextStyle(color = ColorProvider(tint), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

/** Bouton carré de 40 dp pour l'en-tête des petits widgets. */
@Composable
private fun HeaderButton(icon: Int, description: String, tint: Color, action: Action, loading: Boolean = false) {
    Box(
        modifier = GlanceModifier.size(40.dp).background(NavyLight).cornerRadius(12.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = GlanceModifier.size(18.dp), color = ColorProvider(Yellow))
        } else {
            Image(
                provider = ImageProvider(icon),
                contentDescription = description,
                colorFilter = ColorFilter.tint(ColorProvider(tint)),
                modifier = GlanceModifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun LineBadge(code: String, bg: Color, fg: Color) {
    Box(
        modifier = GlanceModifier.size(width = 40.dp, height = 32.dp).background(bg).cornerRadius(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = code,
            style = TextStyle(color = ColorProvider(fg), fontSize = if (code.length > 3) 11.sp else 15.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

@Composable
private fun NotConfigured(context: Context, appWidgetId: Int) {
    Column(
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(configIntent(context, appWidgetId))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text(
            text = "Quai",
            style = TextStyle(color = ColorProvider(Yellow), fontSize = 16.sp, fontWeight = FontWeight.Bold),
        )
        Text(
            text = "Touchez pour choisir un arrêt",
            style = TextStyle(color = ColorProvider(White), fontSize = 13.sp),
        )
    }
}

/** Recalcule les minutes à partir des horaires absolus mémorisés (reste juste entre deux appels API). */
private fun mainLine(prefs: Preferences, status: Status): Pair<String, Color> {
    val now = System.currentTimeMillis()
    val upcoming = prefs.departures().filter { it >= now - 30_000 }.take(2)
    if (upcoming.isNotEmpty() && status != Status.ERROR) {
        return upcoming.joinToString(" - ") { formatWait(it - now) } to Yellow
    }
    return when (status) {
        Status.LOADING -> "Chargement…" to White
        Status.ERROR -> (prefs[WidgetKeys.message] ?: "Erreur réseau") to ErrorRed
        Status.EMPTY, Status.OK -> "Aucun passage prévu" to White
        Status.NOT_CONFIGURED -> "" to White
    }
}

private fun formatWait(deltaMillis: Long): String {
    val min = (deltaMillis / 60_000).coerceAtLeast(0)
    return if (min == 0L) "À quai" else String.format(Locale.FRANCE, "%02d min", min)
}

private fun footer(prefs: Preferences, status: Status): String {
    val updated = prefs[WidgetKeys.updatedAt]?.let {
        SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(it))
    }
    return when (status) {
        Status.LOADING -> "Actualisation…"
        Status.ERROR -> if (updated != null) "Échec · dernière maj $updated" else "Toucher pour réessayer"
        else -> if (updated != null) "Mis à jour à $updated" else ""
    }
}

private fun parseColor(hex: String?, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor("#$hex")) }.getOrDefault(fallback)

private fun configIntent(context: Context, appWidgetId: Int, locate: Boolean = false): Intent =
    Intent(context, WidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .putExtra(WidgetConfigActivity.EXTRA_RECONFIGURE, true)
        .putExtra(WidgetConfigActivity.EXTRA_LOCATE, locate)
        // PendingIntent distinct par widget et par bouton
        .setData(Uri.parse("idfwidget://${if (locate) "locate" else "config"}/$appWidgetId"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

/** Icône cloche du widget : démarre / arrête le suivi en direct. */
class ToggleLiveAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (LiveTracker.isActive(context)) {
            LiveTracker.stop(context)
        } else {
            LiveTracker.start(context, GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
        }
        PassagesWidget().updateAll(context)
    }
}

/** Toucher du widget : affiche immédiatement le chargement puis interroge l'API. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { it[WidgetKeys.status] = Status.LOADING.name }
        PassagesWidget().update(context, glanceId)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        RefreshScheduler.refreshNow(context, appWidgetId, followUps = RefreshScheduler.FOLLOW_UP_COUNT)
    }
}
