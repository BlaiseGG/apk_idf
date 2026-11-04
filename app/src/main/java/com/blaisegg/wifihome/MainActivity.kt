package com.blaisegg.wifihome

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var radius: EditText
    private lateinit var notifyExit: CheckBox
    private lateinit var address: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Notifier.createChannel(this)

        status = findViewById(R.id.status)
        radius = findViewById(R.id.radius)
        notifyExit = findViewById(R.id.notifyExit)
        address = findViewById(R.id.address)
        address.setText(prefs.address)

        radius.setText(prefs.radius.toInt().toString())
        notifyExit.isChecked = prefs.notifyOnExit
        notifyExit.setOnCheckedChangeListener { _, checked -> prefs.notifyOnExit = checked }

        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestForegroundPermissions() }
        findViewById<Button>(R.id.btnBackground).setOnClickListener { requestBackgroundPermission() }
        findViewById<Button>(R.id.btnSetHome).setOnClickListener { saveCurrentLocationAsHome() }
        findViewById<Button>(R.id.btnSetAddress).setOnClickListener { saveAddressAsHome() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                toast("Autorise d'abord les notifications (bouton 1)")
            } else {
                Notifier.notifyArrived(this)
            }
        }
        findViewById<Button>(R.id.btnDisable).setOnClickListener {
            GeofenceHelper.unregister(this)
            prefs.enabled = false
            refreshStatus()
            toast("Surveillance désactivée")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val bg = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        val overlay = Settings.canDrawOverlays(this)
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        val home = if (prefs.enabled) {
            val where = prefs.address.ifBlank {
                "%.5f, %.5f".format(prefs.latitude, prefs.longitude)
            }
            "✅ Maison : $where (${prefs.radius.toInt()} m)"
        } else {
            "❌ Maison pas encore enregistrée"
        }
        status.text = """
            ${mark(fine)} Localisation
            ${mark(bg)} Localisation « Toujours »
            ${mark(notif)} Notifications
            ${mark(overlay)} Alerte plein écran
            $home
        """.trimIndent()
    }

    private fun requestForegroundPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions(perms.toTypedArray(), REQ_FOREGROUND)
    }

    private fun requestBackgroundPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast("Pas nécessaire sur cette version d'Android")
            return
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("Fais d'abord l'étape 1")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Localisation « Toujours »")
            .setMessage(
                "Pour détecter ton arrivée même quand l'app est fermée, choisis " +
                    "« Toujours autoriser » dans l'écran suivant."
            )
            .setPositiveButton("OK") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ only allows this from the settings page.
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                } else {
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND
                    )
                }
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun saveCurrentLocationAsHome() {
        if (!GeofenceHelper.hasLocationPermissions(this)) {
            toast("Fais d'abord les étapes 1 et 2")
            return
        }
        val r = readRadius() ?: return
        toast("Recherche de ta position…")
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { location ->
                if (location == null) {
                    toast("Position introuvable, active le GPS et réessaie")
                    return@addOnSuccessListener
                }
                prefs.address = ""
                saveHome(location.latitude, location.longitude, r)
            }
            .addOnFailureListener { e -> toast("Erreur de localisation : ${e.message}") }
    }

    /** Looks up the typed address and uses it as home. */
    @Suppress("DEPRECATION")
    private fun saveAddressAsHome() {
        if (!GeofenceHelper.hasLocationPermissions(this)) {
            toast("Fais d'abord les étapes 1 et 2")
            return
        }
        val r = readRadius() ?: return
        val text = address.text.toString().trim()
        if (text.isEmpty()) {
            toast("Écris ton adresse d'abord")
            return
        }
        if (!Geocoder.isPresent()) {
            toast("Recherche d'adresse indisponible sur ce téléphone")
            return
        }
        toast("Recherche de l'adresse…")
        Thread {
            val result = try {
                Geocoder(this, Locale.FRANCE).getFromLocationName(text, 1)?.firstOrNull()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (result == null) {
                    toast("Adresse introuvable (vérifie ta connexion et l'orthographe)")
                } else {
                    prefs.address = text
                    saveHome(result.latitude, result.longitude, r)
                }
            }
        }.start()
    }

    private fun readRadius(): Float? {
        val r = radius.text.toString().toFloatOrNull()
        if (r == null || r < 50f) {
            toast("Rayon minimum : 50 m (100–200 m conseillé)")
            return null
        }
        return r
    }

    private fun saveHome(lat: Double, lng: Double, r: Float) {
        prefs.latitude = lat
        prefs.longitude = lng
        prefs.radius = r
        val task = GeofenceHelper.register(this)
        if (task == null) {
            toast("Permissions manquantes")
            return
        }
        task.addOnSuccessListener {
            prefs.enabled = true
            refreshStatus()
            toast("Maison enregistrée 👍")
        }.addOnFailureListener { e ->
            toast("Erreur : ${e.message}")
        }
    }

    private fun requestOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("Alerte plein écran")
            .setMessage(
                "Pour que l'alerte s'affiche en grand dès ton arrivée, active " +
                    "« Afficher par-dessus les autres applis » pour Wi-Fi Maison."
            )
            .setPositiveButton("OK") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_FOREGROUND = 1
        private const val REQ_BACKGROUND = 2
    }
}
