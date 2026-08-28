package com.example.vma_detect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.BufferedReader
import java.io.InputStreamReader

data class PanneauData(val nom: String, val latitude: Float, val longitude: Float)

class MainActivity : AppCompatActivity(), LocationListener {

    private val panneau = mutableListOf<String>()
    private val lat = mutableListOf<Float>()
    private val long = mutableListOf<Float>()

    private lateinit var locationManager: LocationManager
    private lateinit var tvLocation: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvNearestPanneau: TextView
    private lateinit var tvSpeedLimit: TextView
    private lateinit var etThreshold: EditText

    private val KEY_THRESHOLD = "search_threshold"
    private val KEY_LAST_LIMIT = "last_limit"
    private val KEY_LAST_LAT = "last_lat"
    private val KEY_LAST_LON = "last_lon"
    private val KEY_LAST_CSV_URI = "last_csv_uri"
    private val detectedPanneaux = mutableSetOf<String>()
    
    private var currentLimit: Int = 50
    private var limitRestored = false
    private var savedLat: Float = 0f
    private var savedLon: Float = 0f
    private val limitHistory = mutableListOf<Int>()

    private val powerConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            updateKeepScreenOn(isCharging)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Permission GPS refusée", Toast.LENGTH_LONG).show()
        }
    }

    private val getCsvFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                // Demander la permission persistante pour pouvoir recharger le fichier au redémarrage
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                saveCsvUri(it.toString())
                loadCsvFromUri(it)
            } catch (e: Exception) {
                Toast.makeText(this, "Erreur permission : ${e.message}", Toast.LENGTH_LONG).show()
                loadCsvFromUri(it) // On tente quand même le chargement direct
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvLocation = findViewById(R.id.tv_location)
        tvSpeed = findViewById(R.id.tv_speed)
        tvNearestPanneau = findViewById(R.id.tv_nearest_panneau)
        tvSpeedLimit = findViewById(R.id.tv_speed_limit)
        etThreshold = findViewById(R.id.et_threshold)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        loadSavedThreshold()
        updateLimitDisplay()

        findViewById<Button>(R.id.btn_load_csv).setOnClickListener {
            getCsvFile.launch(arrayOf("*/*"))
        }

        checkLocationPermissions()
        
        // Tentative de rechargement du dernier fichier CSV
        reloadLastCsv()
    }

    private fun checkLocationPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        try {
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isGpsEnabled && !isNetworkEnabled) {
                tvLocation.text = "GPS désactivé dans les réglages"
                return
            }

            // Utiliser le GPS pour la précision
            if (isGpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    this
                )
            }

            // Utiliser le réseau en complément (plus rapide à l'intérieur)
            if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    this
                )
            }
            
            tvLocation.text = "Recherche du signal (GPS/Réseau)..."
        } catch (e: SecurityException) {
            tvLocation.text = "Erreur de permissions GPS"
        }
    }

    override fun onLocationChanged(location: Location) {
        val latPos = String.format("%.6f", location.latitude)
        val lonPos = String.format("%.6f", location.longitude)
        tvLocation.text = "Position : Lat $latPos, Lon $lonPos"

        // Restauration de la limite si non fait
        if (!limitRestored) {
            val threshold = etThreshold.text.toString().toFloatOrNull() ?: 5f
            val lastLoc = Location("").apply {
                latitude = savedLat.toDouble()
                longitude = savedLon.toDouble()
            }
            val distance = location.distanceTo(lastLoc)
            
            if (distance <= threshold && savedLat != 0f) {
                // On garde currentLimit chargé depuis SharedPreferences
                Toast.makeText(this, "Limite restaurée : $currentLimit km/h", Toast.LENGTH_SHORT).show()
            } else {
                currentLimit = 50
                updateLimitDisplay()
            }
            limitRestored = true
        }

        // Vitesse en km/h
        val speedKmH = (location.speed * 3.6).toInt()
        tvSpeed.text = "Vitesse : $speedKmH km/h"
        
        // Signalement si dépassement
        if (speedKmH > currentLimit) {
            tvSpeed.setTextColor(android.graphics.Color.RED)
        } else {
            tvSpeed.setTextColor(android.graphics.Color.BLACK)
        }

        updateNearestPanneau(location)
    }

    private fun updateNearestPanneau(currentLocation: Location) {
        if (panneau.isEmpty()) return

        var minDistance = Float.MAX_VALUE
        var nearestIndex = -1

        for (i in 0 until panneau.size) {
            val panneauLocation = Location("").apply {
                latitude = lat[i].toDouble()
                longitude = long[i].toDouble()
            }
            
            val distance = currentLocation.distanceTo(panneauLocation)
            if (distance < minDistance) {
                minDistance = distance
                nearestIndex = i
            }
        }

        // Récupération du seuil paramétré (x mètres)
        val threshold = etThreshold.text.toString().toFloatOrNull() ?: 5f

        if (nearestIndex != -1 && minDistance <= threshold) {
            val distText = if (minDistance < 1000) {
                "${minDistance.toInt()} m"
            } else {
                String.format("%.1f km", minDistance / 1000)
            }
            val pLat = lat[nearestIndex]
            val pLon = long[nearestIndex]
            val decodedName = decodePanneau(panneau[nearestIndex])
            tvNearestPanneau.text = "Panneau le plus proche :\n$decodedName ($distText)\nPos: $pLat, $pLon"
            
            // Si le panneau est détecté pour la première fois, on traite le changement de limite
            if (!isAlreadyDetected(panneau[nearestIndex], pLat, pLon)) {
                processPanneauForLimit(panneau[nearestIndex])
                markAsDetected(panneau[nearestIndex], pLat, pLon)
            }
        } else {
            tvNearestPanneau.text = "Aucun panneau à moins de ${threshold.toInt()}m"
        }
    }

    override fun onStart() {
        super.onStart()
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(powerConnectionReceiver, intentFilter)
        
        // Vérification initiale de l'état de charge
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        updateKeepScreenOn(isCharging)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(powerConnectionReceiver)
    }

    private fun updateKeepScreenOn(isCharging: Boolean) {
        if (isCharging) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun decodePanneau(code: String): String {
        val components = code.split(",").map { it.trim().uppercase() }
        if (components.isEmpty()) return code

        // On cherche le code "principal" (celui qui n'est pas M1 ou M2)
        // S'il n'y a que des M1/M2, on prend le premier par défaut.
        val mainCode = components.firstOrNull { !it.startsWith("M1") && !it.startsWith("M2") }
            ?: components[0]

        val speedAfterArrow = if (mainCode.contains("=>")) {
            mainCode.substringAfter("=>").filter { it.isDigit() }
        } else {
            null
        }

        var transcription = when {
            mainCode.startsWith("B14") -> if (!speedAfterArrow.isNullOrEmpty()) "Limitation de vitesse à $speedAfterArrow km/h" else "Limitation de vitesse"
            mainCode.startsWith("EB10") -> "Entrée d'agglomération (50 km/h par défaut)"
            mainCode.startsWith("EB20") -> "Sortie d'agglomération"
            mainCode.startsWith("C207") -> "Début de section d'autoroute (130 km/h)"
            mainCode.startsWith("C208") -> "Fin de section d'autoroute"
            mainCode.startsWith("C20A") -> "Début de route à accès réglementé (110 km/h)"
            mainCode.startsWith("C20B") -> "Fin de route à accès réglementé"
            mainCode.startsWith("B31") -> "Fin de toutes les interdictions précédemment signalées"
            mainCode.startsWith("B33") -> "Fin de limitation de vitesse"
            mainCode.startsWith("M9Z") || mainCode.contains("RAPPEL") -> "Rappel"
            else -> mainCode
        }

        // Ajout des modificateurs M1/M2 depuis les autres membres
        components.forEach { part ->
            if (part == mainCode) return@forEach
            val value = if (part.contains("=>")) part.substringAfter("=>").filter { it.isDigit() } else ""
            
            if (value.isNotEmpty()) {
                when {
                    part.startsWith("M1") -> transcription += " dans $value mètres"
                    part.startsWith("M2") -> transcription += " pendant $value mètres"
                }
            }
        }

        return transcription
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        Toast.makeText(this, "Veuillez activer le GPS", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        saveAppState()
    }

    private fun loadSavedThreshold() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val savedThreshold = sharedPref.getFloat(KEY_THRESHOLD, 5f)
        if (savedThreshold == savedThreshold.toInt().toFloat()) {
            etThreshold.setText(savedThreshold.toInt().toString())
        } else {
            etThreshold.setText(savedThreshold.toString())
        }
        
        // Charger les infos de limite
        currentLimit = sharedPref.getInt(KEY_LAST_LIMIT, 50)
        savedLat = sharedPref.getFloat(KEY_LAST_LAT, 0f)
        savedLon = sharedPref.getFloat(KEY_LAST_LON, 0f)
    }

    private fun reloadLastCsv() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val uriString = sharedPref.getString(KEY_LAST_CSV_URI, null)
        if (uriString != null) {
            try {
                val uri = Uri.parse(uriString)
                loadCsvFromUri(uri)
            } catch (e: Exception) {
                // Le fichier n'est peut-être plus accessible
            }
        }
    }

    private fun saveCsvUri(uriString: String) {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString(KEY_LAST_CSV_URI, uriString)
            apply()
        }
    }

    private fun loadCsvFromUri(uri: Uri) {
        try {
            val tempData = mutableListOf<PanneauData>()
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        val tokens = line.split(",")
                        if (tokens.size >= 3) {
                            val panneauName = tokens.dropLast(2).joinToString(",").replace("\"", "").trim()
                            val excludedPatterns = listOf("M4b", "M4c", "M4f", "M4g", "M4k", "M4l", "M4m", "M4x", "M3a")
                            val isExcluded = excludedPatterns.any { panneauName.contains(it, ignoreCase = true) }

                            if (!isExcluded) {
                                val laStr = tokens[tokens.size - 2].replace("\"", "").trim().replace(",", ".")
                                val loStr = tokens.last().replace("\"", "").trim().replace(",", ".")
                                val la = laStr.toFloatOrNull()
                                val lo = loStr.toFloatOrNull()
                                if (la != null && lo != null) {
                                    tempData.add(PanneauData(panneauName, la, lo))
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }

            Thread {
                try {
                    val sortedData = tempData.sortedWith(compareBy({ it.latitude }, { it.longitude }))
                    runOnUiThread {
                        panneau.clear()
                        lat.clear()
                        long.clear()
                        for (item in sortedData) {
                            panneau.add(item.nom)
                            lat.add(item.latitude)
                            long.add(item.longitude)
                        }
                        Toast.makeText(this, "Chargé : ${tempData.size} lignes", Toast.LENGTH_SHORT).show()
                        tvNearestPanneau.text = "Fichier chargé. En attente de position..."
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Erreur traitement : ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        } catch (e: Exception) {
            // Fichier probablement inaccessible ou supprimé
            if (!limitRestored) { // Si c'est au démarrage
                 Toast.makeText(this, "Dernier fichier CSV introuvable", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveAppState() {
        val threshold = etThreshold.text.toString().toFloatOrNull() ?: 5f
        
        // On récupère la dernière position connue pour la sauvegarde
        var lastLat = savedLat
        var lastLon = savedLon
        
        try {
            val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastKnown?.let {
                lastLat = it.latitude.toFloat()
                lastLon = it.longitude.toFloat()
            }
        } catch (e: SecurityException) {}

        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putFloat(KEY_THRESHOLD, threshold)
            putInt(KEY_LAST_LIMIT, currentLimit)
            putFloat(KEY_LAST_LAT, lastLat)
            putFloat(KEY_LAST_LON, lastLon)
            apply()
        }
    }

    private fun markAsDetected(nom: String, la: Float, lo: Float) {
        val decoded = decodePanneau(nom)
        val line = "$nom, $decoded, $la, $lo"
        detectedPanneaux.add(line)
    }

    private fun isAlreadyDetected(nom: String, la: Float, lo: Float): Boolean {
        val decoded = decodePanneau(nom)
        val line = "$nom, $decoded, $la, $lo"
        return detectedPanneaux.contains(line)
    }

    private fun processPanneauForLimit(code: String) {
        val components = code.split(",").map { it.trim().uppercase() }
        val mainCode = components.firstOrNull { !it.startsWith("M1") && !it.startsWith("M2") } ?: components[0]
        
        val speed = if (mainCode.contains("=>")) mainCode.substringAfter("=>").filter { it.isDigit() }.toIntOrNull() else null

        when {
            mainCode.startsWith("B14") && speed != null -> {
                limitHistory.add(currentLimit)
                currentLimit = speed
            }
            mainCode.startsWith("EB10") -> {
                limitHistory.add(currentLimit)
                currentLimit = 50
            }
            mainCode.startsWith("C207") -> {
                limitHistory.add(currentLimit)
                currentLimit = 130
            }
            mainCode.startsWith("C20A") -> {
                limitHistory.add(currentLimit)
                currentLimit = 110
            }
            mainCode.startsWith("B31") || mainCode.startsWith("B33") || 
            mainCode.startsWith("EB20") || mainCode.startsWith("C208") || 
            mainCode.startsWith("C20B") -> {
                if (limitHistory.isNotEmpty()) {
                    currentLimit = limitHistory.removeAt(limitHistory.size - 1)
                } else {
                    currentLimit = 80 // Valeur par défaut si historique vide
                }
            }
        }
        updateLimitDisplay()
    }

    private fun updateLimitDisplay() {
        tvSpeedLimit.text = "Limite : $currentLimit km/h"
    }
}
