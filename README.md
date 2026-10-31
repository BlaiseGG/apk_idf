# Quai — prochains passages IDFM (application Android + widget)

Widget d'écran d'accueil façon « panneau de quai » qui affiche les **2 prochains passages en temps réel**
(RER, Métro, Train, Tram, Bus) d'une ligne et d'une direction choisies, via les API publiques
**PRIM d'Île-de-France Mobilités**.

```
┌───────────────────────────────────────────┐
│ [ A ]  Châtelet - Les Halles        ⟳  ⚙ │
│        → Marne-la-Vallée Chessy           │
│              02 min - 10 min              │
│   Mis à jour à 08:41 · toucher pour…      │
└───────────────────────────────────────────┘
```

## 1. Stack technique retenue

| Besoin | Choix | Pourquoi |
|---|---|---|
| Langage | **Kotlin** | Langage officiel Android, coroutines pour l'asynchrone. |
| UI de l'app | **Jetpack Compose + Material 3** | UI déclarative moderne, peu de code, thème clair/sombre. |
| Widget | **Jetpack Glance** (`glance-appwidget`) | Écrire le widget en style Compose ; Glance génère les `RemoteViews`, gère l'état par widget et les actions de clic. |
| Réseau | **Retrofit + OkHttp + kotlinx.serialization** | Standard de fait, appels `suspend`, parsing JSON tolérant (`ignoreUnknownKeys`). |
| Tâches de fond | **WorkManager** | Seule API recommandée pour du travail différé qui respecte Doze / App Standby et survit aux redémarrages. |
| Stockage | **DataStore Preferences** | Remplaçant asynchrone de SharedPreferences (clé API, trajets). |
| Localisation | **LocationManager (androidx.core)** | Pas de dépendance aux Google Play Services : fonctionne aussi sur téléphones sans GMS. |
| DI | Conteneur manuel (`IdfApp`) | Projet petit : Hilt ajouterait de la complexité sans bénéfice. |

minSdk 26 (Android 8.0), targetSdk 35.

## 2. Données PRIM utilisées

Toutes les requêtes passent l'en-tête `apikey: <votre clé>` vers `https://prim.iledefrance-mobilites.fr/marketplace/`.

| Étape | Endpoint |
|---|---|
| Arrêts proches (GPS) | `v2/navitia/coord/{lon};{lat}/places_nearby?type[]=stop_area&distance=1000` |
| Recherche par nom | `v2/navitia/places?q=…&type[]=stop_area` |
| Lignes d'un arrêt | `v2/navitia/stop_areas/{id}/lines` |
| Quais de la ligne | `v2/navitia/stop_areas/{id}/lines/{line}/stop_points` |
| Directions théoriques | `v2/navitia/stop_areas/{id}/lines/{line}/routes` |
| **Temps réel** | `stop-monitoring?MonitoringRef=STIF:StopPoint:Q:<quai>:` (SIRI-Lite) |

Les identifiants Navitia sont convertis vers SIRI (`IdMapper.kt`) :
`stop_point:IDFM:22092 → STIF:StopPoint:Q:22092:`, `line:IDFM:C01742 → STIF:Line::C01742:`.
Si les quais ne renvoient rien, l'app se replie sur la zone d'arrêt `STIF:StopArea:SP:<id>:`.
Les passages sont filtrés par ligne (`LineRef`) puis par direction (`DirectionName` / `DestinationName`).

## 3. Rafraîchissement & batterie

- **Toucher le widget** (ou l'icône ⟳) → état « Actualisation… » immédiat puis appel API.
- **Suivi rapproché** : après un toucher, 5 mises à jour à ~1 min d'intervalle, puis arrêt (et seulement écran allumé).
- **Périodique** : toutes les 15 min (minimum Android), avec réseau, ignoré écran éteint ; Android regroupe ces réveils en mode Doze.
- Les horaires sont stockés en **heure absolue** : chaque redessin recalcule les minutes, et les passages déjà partis disparaissent.
- États affichés : chargement, erreur lisible (pas de réseau, clé refusée, quota…), « Aucun passage prévu », widget non configuré.

## 4. Structure

```
app/src/main/java/com/blaisegg/idfwidget/
├── IdfApp.kt                    # Application + conteneur de dépendances
├── data/
│   ├── api/PrimApi.kt           # Interface Retrofit (Navitia + stop-monitoring)
│   ├── api/NavitiaDto.kt        # Modèles JSON référentiel
│   ├── api/SiriDto.kt           # Modèles JSON SIRI-Lite
│   ├── Network.kt               # OkHttp / Retrofit / JSON
│   ├── PrimRepository.kt        # Logique métier + messages d'erreur
│   ├── IdMapper.kt              # Conversion d'identifiants Navitia ↔ SIRI
│   ├── SettingsStore.kt         # DataStore : clé API, trajets
│   └── Models.kt
├── location/LocationHelper.kt   # Position GPS / réseau
├── ui/                          # Compose : clé API → arrêt → ligne → direction → validation
│   ├── MainActivity.kt
│   ├── WidgetConfigActivity.kt  # Configuration à l'ajout du widget / via ⚙
│   ├── MainViewModel.kt
│   └── AppScreen.kt, Theme.kt
└── widget/
    ├── PassagesWidget.kt        # UI Glance + action Rafraîchir
    ├── PassagesWidgetReceiver.kt
    ├── RefreshWorker.kt         # Appel API + mise à jour de l'état du widget
    ├── RefreshScheduler.kt      # Planification WorkManager
    └── WidgetState.kt
```

Permissions (`AndroidManifest.xml`) : `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`
(demandée seulement au clic sur « Localiser »).

## 5. Obtenir l'APK

### Option A — lien direct (le plus simple)
Chaque push publie automatiquement la dernière version :
**https://github.com/BlaiseGG/apk_idf/releases/latest/download/Quai.apk**

Ouvrir ce lien sur le téléphone, puis ouvrir `Quai.apk` et autoriser « Installer des applis inconnues ».

### Option B — Android Studio
1. Installer Android Studio (Ladybug ou plus récent), ouvrir le dossier du projet.
2. Laisser Gradle synchroniser, puis **Run ▶** sur un téléphone branché (débogage USB activé).

### Option C — ligne de commande
JDK 17 + SDK Android (`ANDROID_HOME` défini), puis :
```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 6. Premier lancement

1. Créer un compte sur [prim.iledefrance-mobilites.fr](https://prim.iledefrance-mobilites.fr/), puis **s'abonner** (gratuit) aux API :
   - « Prochains Passages (plateforme IDFM) – requête unitaire » (temps réel),
   - « Navitia » / « Calculateur IDFM » (recherche d'arrêts, lignes).
   Copier le jeton depuis « Mon compte › Mes jetons d'authentification ».
2. Ouvrir l'app, coller la clé, **Enregistrer**.
3. **Localiser les arrêts proches** (ou taper le nom d'une gare) → choisir l'arrêt → la ligne → la direction.
4. Vérifier l'aperçu, puis **Valider et associer au widget**.
5. **Ajouter le widget à l'écran d'accueil** (bouton dans l'app, ou appui long sur l'écran d'accueil › Widgets ›
   Prochains passages). Toucher le widget pour rafraîchir, ⚙ pour lui donner un trajet différent.

Astuce : si les mises à jour automatiques s'arrêtent, exclure l'app de l'optimisation de batterie
(Paramètres › Applications › Quai › Batterie › Sans restriction), certains constructeurs étant très agressifs.
