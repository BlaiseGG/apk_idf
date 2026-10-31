package com.blaisegg.idfwidget.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blaisegg.idfwidget.data.DirectionChoice
import com.blaisegg.idfwidget.data.DirectionGroup
import com.blaisegg.idfwidget.data.IdMapper
import com.blaisegg.idfwidget.data.LineItem
import com.blaisegg.idfwidget.data.ModeCategory
import com.blaisegg.idfwidget.data.Passage
import com.blaisegg.idfwidget.data.StopItem
import com.blaisegg.idfwidget.data.Trajet
import com.blaisegg.idfwidget.widget.PassagesWidgetReceiver
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: MainViewModel, widgetMode: Boolean, onExit: () -> Unit, onToggleLive: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    BackHandler { if (!vm.back()) onExit() }

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let { snackbar.showSnackbar(it); vm.snackbarShown() }
    }

    val title = when (state.step) {
        Step.API_KEY -> "Clé API PRIM"
        Step.SEARCH -> "Choisir un arrêt"
        Step.LINES -> state.stop?.name ?: "Ligne"
        Step.DIRECTIONS -> "Direction"
        Step.SUMMARY -> if (widgetMode) "Configurer ce widget" else "Mon trajet"
        Step.LOADING -> ""
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    if (state.step in listOf(Step.LINES, Step.DIRECTIONS) ||
                        (state.step == Step.SEARCH && state.trajet != null)
                    ) {
                        IconButton(onClick = { vm.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = IdfmNavy, titleContentColor = Color.White, navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when (state.step) {
                Step.LOADING -> Centered { CircularProgressIndicator() }
                Step.API_KEY -> ApiKeyStep(state.apiKey, onSave = vm::saveApiKey)
                Step.SEARCH -> SearchStep(state, vm)
                Step.LINES -> ListStep(state.lines) { lines -> LinesStep(lines, state.hiddenModes, vm) }
                Step.DIRECTIONS -> ListStep(state.directions) { d -> DirectionsList(d.choices, vm::selectDirection) }
                Step.SUMMARY -> SummaryStep(state, widgetMode, vm, onToggleLive)
            }
        }
    }
}

// ------------------------------------------------------------------ Clé API

@Composable
private fun ApiKeyStep(current: String, onSave: (String) -> Unit) {
    var key by rememberSaveable { mutableStateOf(current) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "L'application interroge les API d'Île-de-France Mobilités via la plateforme PRIM. " +
                "Créez un compte gratuit, abonnez-vous aux API « Prochains passages (requête unitaire) » et " +
                "« Navitia », puis copiez votre jeton ici.",
        )
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://prim.iledefrance-mobilites.fr/")))
        }) { Text("Ouvrir prim.iledefrance-mobilites.fr") }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Clé API (apikey)") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { visible = !visible }) { Text(if (visible) "Masquer" else "Afficher") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSave(key) }, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Enregistrer")
        }
    }
}

// ------------------------------------------------------------------ Recherche

@Composable
private fun SearchStep(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> if (granted.values.any { it }) vm.locate() else vm.onLocationDenied() }

    val requestLocate = {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) vm.locate() else permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
    // Ouvert depuis le bouton « Localiser » du widget : on localise tout de suite.
    LaunchedEffect(state.autoLocate) {
        if (state.autoLocate) {
            vm.autoLocateConsumed()
            requestLocate()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { requestLocate() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Localiser les arrêts proches")
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                label = { Text("Ou rechercher une gare / station") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ModeFilterRow(SEARCH_MODES, state.hiddenModes, vm::toggleMode)
        HorizontalDivider()
        ListStep(state.stops, idleText = "Localisez-vous ou tapez au moins 2 lettres.") { stops ->
            // Un arrêt reste visible s'il dessert au moins un mode non masqué (ou si ses modes sont inconnus).
            val visible = stops.filter { it.modes.isEmpty() || (it.modes - state.hiddenModes).isNotEmpty() }
            when {
                stops.isEmpty() -> Centered { Text("Aucun arrêt trouvé.") }
                visible.isEmpty() -> Centered { Text("Aucun arrêt avec les modes sélectionnés.", textAlign = TextAlign.Center) }
                else -> StopsList(visible, state.hiddenModes, vm::selectStop)
            }
        }
    }
}

@Composable
private fun StopsList(stops: List<StopItem>, hidden: Set<ModeCategory>, onClick: (StopItem) -> Unit) {
    LazyColumn {
        items(stops, key = { it.id }) { stop ->
            val details = listOfNotNull(
                (stop.modes - hidden).joinToString(" · ") { it.label }.ifBlank { null },
                stop.distanceMeters?.let { "à $it m" },
            ).joinToString("   ")
            ListItem(
                headlineContent = { Text(stop.name) },
                supportingContent = { if (details.isNotEmpty()) Text(details) },
                leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                modifier = Modifier.clickable { onClick(stop) },
            )
            HorizontalDivider()
        }
    }
}

// ------------------------------------------------------------------ Lignes

@Composable
private fun LinesStep(lines: List<LineItem>, hidden: Set<ModeCategory>, vm: MainViewModel) {
    val present = lines.map { it.category }.distinct().sorted()
    val grouped = lines.filter { it.category !in hidden }.groupBy { it.category }.toSortedMap()
    Column(Modifier.fillMaxSize()) {
        if (present.size > 1) {
            ModeFilterRow(present, hidden, vm::toggleMode)
            HorizontalDivider()
        }
        if (grouped.isEmpty()) {
            Centered { Text("Toutes les lignes de cet arrêt sont masquées par les filtres.", textAlign = TextAlign.Center) }
        } else {
            LazyColumn {
                grouped.forEach { (category, group) ->
                    item(key = "header-$category") { SectionHeader(category.label) }
                    items(group, key = { it.id }) { line ->
                        ListItem(
                            headlineContent = { Text(line.name) },
                            supportingContent = { Text(line.mode) },
                            leadingContent = { LineBadge(line.code, line.color, line.textColor) },
                            modifier = Modifier.clickable { vm.selectLine(line) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private val SEARCH_MODES = listOf(ModeCategory.RER, ModeCategory.METRO, ModeCategory.TRAIN, ModeCategory.TRAM, ModeCategory.BUS)

/** Puces cochables : un mode décoché est masqué (choix mémorisé). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeFilterRow(modes: List<ModeCategory>, hidden: Set<ModeCategory>, onToggle: (ModeCategory) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        modes.forEach { mode ->
            val selected = mode !in hidden
            FilterChip(
                selected = selected,
                onClick = { onToggle(mode) },
                label = { Text(mode.label) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
fun LineBadge(code: String, color: String, textColor: String) {
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 36.dp)
            .background(hexColor(color, IdfmNavy), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            code,
            color = hexColor(textColor, Color.White),
            fontWeight = FontWeight.Bold,
            fontSize = if (code.length > 3) 12.sp else 16.sp,
            maxLines = 1,
        )
    }
}

private fun hexColor(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor("#$hex")) }.getOrDefault(fallback)

// ------------------------------------------------------------------ Directions

@Composable
private fun DirectionsList(choices: List<DirectionChoice>, onClick: (DirectionChoice?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val paris = choices.filter { it.group == DirectionGroup.PARIS }
    val stations = choices.filter { it.group != DirectionGroup.PARIS }
    val q = IdMapper.normalize(query)
    val shown = if (q.isEmpty()) stations else stations.filter { IdMapper.normalize(it.label).contains(q) }

    LazyColumn {
        // 1. « Direction Paris » en premier, bien visible.
        if (q.isEmpty()) {
            items(paris) { choice ->
                ListItem(
                    headlineContent = { Text("Direction Paris", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                    supportingContent = { Text("Tous les trains vers Paris (" + choice.targets.joinToString(", ") + ")") },
                    leadingContent = { Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { onClick(choice) },
                )
                HorizontalDivider()
            }
        }
        // 2. Recherche de la gare où l'on va.
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Où allez-vous ? (ex. Grigny)") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (stations.isNotEmpty()) {
            item { SectionHeader(if (q.isEmpty()) "Toutes les gares desservies" else "${shown.size} gare(s) trouvée(s)") }
        }
        items(shown, key = { "st-" + it.label }) { choice ->
            ListItem(
                headlineContent = { Text(choice.label) },
                supportingContent = {
                    val termini = choice.targets.filterNot { IdMapper.normalize(it) == IdMapper.normalize(choice.label) }
                    if (termini.isNotEmpty()) Text("Trains vers " + termini.joinToString(", "))
                },
                modifier = Modifier.clickable { onClick(choice) },
            )
            HorizontalDivider()
        }
        if (q.isNotEmpty() && shown.isEmpty()) {
            item { Text("Aucune gare de cette ligne ne correspond.", modifier = Modifier.padding(16.dp)) }
        }
        if (choices.isEmpty()) {
            item {
                Text(
                    "Aucune direction trouvée pour l'instant (service terminé ?). Choisissez « Toutes directions ».",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            ListItem(
                headlineContent = { Text("Toutes directions") },
                supportingContent = { Text("Afficher tous les passages de la ligne") },
                modifier = Modifier.clickable { onClick(null) },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

// ------------------------------------------------------------------ Récapitulatif

@Composable
private fun SummaryStep(state: UiState, widgetMode: Boolean, vm: MainViewModel, onToggleLive: (() -> Unit)?) {
    val trajet = state.trajet ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoardPreview(trajet, state.preview, onRefresh = vm::loadPreview)

        if (!state.saved) {
            Button(onClick = vm::validate, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (widgetMode) "Valider pour ce widget" else "Valider et associer au widget")
            }
        } else {
            Text(
                if (widgetMode) "Ce trajet est associé à ce widget." else "Ce trajet est affiché par vos widgets.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (onToggleLive != null && state.saved) {
            Button(onClick = onToggleLive, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.liveActive) "Arrêter le suivi en direct" else "Suivre en direct en haut de l'écran")
            }
            Text(
                "Affiche l'heure du prochain passage dans la barre d'état (Android 16+), " +
                    "sinon dans une notification. Mise à jour chaque minute, arrêt automatique après 1 h.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = vm::startSearch, modifier = Modifier.fillMaxWidth()) {
            Text("Choisir un autre trajet")
        }
        if (!widgetMode) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val ok = GlanceAppWidgetManager(context)
                            .requestPinGlanceAppWidget(PassagesWidgetReceiver::class.java)
                        if (!ok) {
                            android.widget.Toast.makeText(
                                context,
                                "Ajoutez le widget depuis l'écran d'accueil (appui long › Widgets).",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Ajouter le widget à l'écran d'accueil") }
        }
        TextButton(onClick = vm::editApiKey) { Text("Modifier la clé API") }
    }
}

/** Aperçu dans l'app, avec le même rendu que le widget. */
@Composable
private fun BoardPreview(trajet: Trajet, preview: Load<List<Passage>>, onRefresh: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = IdfmNavy),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onRefresh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(trajet.lineCode, trajet.lineColor, trajet.lineTextColor)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(trajet.stopName, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("→ ${trajet.direction ?: "Toutes directions"}", color = Color(0xFFB8C4D6), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            val (text, color) = when (preview) {
                Load.Idle, Load.Loading -> "Chargement…" to Color.White
                is Load.Err -> preview.message to Color(0xFFFF8A80)
                is Load.Ok -> {
                    val now = System.currentTimeMillis()
                    val next = preview.data.take(2)
                    if (next.isEmpty()) "Aucun passage prévu" to Color.White
                    else next.joinToString(" - ") {
                        val m = ((it.epochMillis - now) / 60_000).coerceAtLeast(0)
                        if (m == 0L) "À quai" else String.format(Locale.FRANCE, "%02d min", m)
                    } to IdfmYellow
                }
            }
            Text(
                text,
                color = color,
                fontSize = if (color == IdfmYellow) 30.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Toucher pour rafraîchir",
                color = Color(0xFFB8C4D6),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ Utilitaires

@Composable
private fun <T> ListStep(load: Load<T>, idleText: String = "", content: @Composable (T) -> Unit) {
    when (load) {
        Load.Idle -> Centered { Text(idleText, textAlign = TextAlign.Center) }
        Load.Loading -> Centered { CircularProgressIndicator() }
        is Load.Err -> Centered { Text(load.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        is Load.Ok -> content(load.data)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(PaddingValues(24.dp)), contentAlignment = Alignment.Center) { content() }
}
