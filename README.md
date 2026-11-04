# Wi-Fi Maison

Petite app Android qui te prévient quand tu arrives chez toi pour passer de la 5G au Wi-Fi.

## Comment ça marche

- Tu enregistres la position de ta maison (une fois, depuis chez toi).
- L'app crée une zone (géofence) autour, gérée par Android : très peu de batterie, l'app n'a pas besoin de rester ouverte.
- Quand tu entres dans la zone **et que ton Wi-Fi est éteint**, tu reçois une notification :
  - un appui ouvre le panneau « Internet » (Wi-Fi + données mobiles) ;
  - le bouton « Activer le Wi-Fi » ouvre directement le panneau Wi-Fi.
- Option : une notification quand tu pars, pour penser à réactiver la 5G.
- Sur Android 9 ou plus ancien, le Wi-Fi est activé automatiquement.

> Pourquoi pas de bascule automatique ? Depuis Android 10, Google interdit aux apps
> (hors apps système / root) d'activer le Wi-Fi ou de couper les données mobiles.
> La notification qui ouvre le bon panneau est le plus proche possible.
> Une fois le Wi-Fi activé, Android l'utilise en priorité à la place de la 5G.

## Installer

1. Télécharge `WifiMaison.apk` depuis la release **apk-latest** du dépôt
   (onglet *Releases* sur GitHub), elle est recompilée à chaque push.
2. Ouvre le fichier sur ton téléphone et autorise l'installation d'apps inconnues.

## Configurer (dans l'app)

1. **Autoriser localisation + notifications.**
2. **Localisation « Toujours »** : dans l'écran qui s'ouvre, Autorisations → Position → *Toujours autoriser*.
3. **Chez toi**, appuie sur *Je suis chez moi : enregistrer cette position*.

Conseil : rayon de 100 à 200 m. Désactive l'optimisation de batterie pour l'app si ton téléphone
(Xiaomi, Huawei, Samsung…) est agressif sur les apps en arrière-plan.

## Compiler soi-même

```
gradle assembleDebug
```
(JDK 17, Android SDK 35.) L'APK est dans `app/build/outputs/apk/debug/`.
