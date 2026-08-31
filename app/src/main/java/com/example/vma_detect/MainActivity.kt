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
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale

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
    private lateinit var tvCsvFilename: TextView
    private lateinit var etThreshold: EditText
    private lateinit var btnToggleLog: Button
    private lateinit var btnZone30: Button
    private lateinit var btnFinZone30: Button
    private lateinit var btnTestSimu: Button

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

    private var isCharging = false
    private var isLoggingEnabled = false
    private var lastLoggedLocation: Location? = null
    private var lastValidLocation: Location? = null
    private var lastValidSimuLocation: Location? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    private var lastAlertTime: Long = 0
    private var defaultSpeedColor: Int = android.graphics.Color.BLACK

    private var lastAggloType: String? = null
    private var lastAggloLocation: Location? = null
    private val AGGLO_FILTER_DISTANCE = 100f // mètres

    private val keepAliveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (isCharging) {
                updateKeepScreenOn(true)
            }
            keepAliveHandler.postDelayed(this, 30000) // Toutes les 30 secondes
        }
    }

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

    private val getSimuFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runSimulation(it)
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
        tvCsvFilename = findViewById(R.id.tv_csv_filename)
        etThreshold = findViewById(R.id.et_threshold)
        btnToggleLog = findViewById(R.id.btn_toggle_log)
        btnZone30 = findViewById(R.id.btn_zone30)
        btnFinZone30 = findViewById(R.id.btn_fin_zone30)
        btnTestSimu = findViewById(R.id.btn_test_simu)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        defaultSpeedColor = tvSpeed.currentTextColor

        loadSavedThreshold()
        updateLimitDisplay()
        tvSpeed.text = "Vitesse : 0 km/h"

        findViewById<Button>(R.id.btn_load_csv).setOnClickListener {
            getCsvFile.launch(arrayOf("*/*"))
        }

        btnToggleLog.setOnClickListener {
            toggleGpsLogging()
        }

        btnZone30.setOnClickListener {
            logZoneEvent("Zone 30")
        }

        btnFinZone30.setOnClickListener {
            logZoneEvent("Fin de zone 30")
        }

        btnTestSimu.setOnClickListener {
            getSimuFile.launch(arrayOf("text/plain", "*/*"))
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
        processNewLocation(location, false)
    }

    private fun processNewLocation(location: Location, isSimulation: Boolean) {
        // Filtrage des points aberrants (activé en réel ET en simulation)
        if (location.accuracy > 60) {
            if (isLoggingEnabled) logDebug("Point rejeté (précision: ${location.accuracy}m)")
            return 
        }
        
        val last = if (isSimulation) lastValidSimuLocation else lastValidLocation
        if (last != null) {
            val distance = location.distanceTo(last)
            val timeDeltaSec = (location.time - last.time) / 1000.0
            
            if (timeDeltaSec > 0) {
                val calculatedSpeedKmh = (distance / timeDeltaSec) * 3.6
                // Si la vitesse entre deux points > 250 km/h, c'est probablement un saut GPS aberrant
                if (calculatedSpeedKmh > 250) {
                    if (isLoggingEnabled || isSimulation) {
                        val source = if (isSimulation) "SIMU" else "GPS"
                        logDebug("Point rejeté [$source] (saut aberrant: ${calculatedSpeedKmh.toInt()} km/h)")
                    }
                    return
                }
            }
        }
        
        if (isSimulation) {
            lastValidSimuLocation = location
        } else {
            lastValidLocation = location
        }

        // Forçage périodique du maintien de l'écran si en charge
        if (isCharging) {
            runOnUiThread {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.decorView.keepScreenOn = true
            }
        }

        val latPos = String.format(Locale.US, "%.6f", location.latitude)
        val lonPos = String.format(Locale.US, "%.6f", location.longitude)
        
        runOnUiThread {
            tvLocation.text = "Position : Lat $latPos, Lon $lonPos"
            // Vitesse en km/h
            val speedKmH = (location.speed * 3.6).toInt()
            tvSpeed.text = "Vitesse : $speedKmH km/h"

            // Signalement si dépassement
            if (speedKmH > currentLimit) {
                tvSpeed.setTextColor(android.graphics.Color.RED)
                if (!isSimulation) playAlertSound()
            } else {
                tvSpeed.setTextColor(defaultSpeedColor)
            }
        }

        // Restauration de la limite si non fait
        if (!limitRestored) {
            val threshold = etThreshold.text.toString().toFloatOrNull() ?: 5f
            val lastLoc = Location("").apply {
                latitude = savedLat.toDouble()
                longitude = savedLon.toDouble()
            }
            val distance = location.distanceTo(lastLoc)
            
            if (distance <= threshold && savedLat != 0f) {
                if (!isSimulation) Toast.makeText(this, "Limite restaurée : $currentLimit km/h", Toast.LENGTH_SHORT).show()
            } else {
                currentLimit = 50
                updateLimitDisplay()
            }
            limitRestored = true
        }

        // Log GPS si activé
        if (isLoggingEnabled && !isSimulation) {
            logGpsPosition(location)
        }

        updateNearestPanneau(location, isSimulation)
    }

    private fun runSimulation(uri: Uri) {
        Toast.makeText(this, "Démarrage de la simulation...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                // On réinitialise les détections pour la simu
                detectedPanneaux.clear()
                lastValidSimuLocation = null
                lastAggloType = null
                lastAggloLocation = null
                val resFile = File(getExternalFilesDir(null), "res.txt")
                resFile.writeText("--- Début Simulation ---\n")

                contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            val tokens = line.split(",")
                            if (tokens.size >= 4) {
                                try {
                                    val time = tokens[0].trim().toLong()
                                    val la = tokens[1].trim().toDouble()
                                    val lo = tokens[2].trim().toDouble()
                                    val speedKmh = tokens[3].trim().toDouble()

                                    val mockLoc = Location("simu").apply {
                                        latitude = la
                                        longitude = lo
                                        speed = (speedKmh / 3.6).toFloat()
                                        this.time = time
                                    }
                                    processNewLocation(mockLoc, true)
                                } catch (e: Exception) {}
                            }
                            line = reader.readLine()
                        }
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "Simulation terminée. Résultats dans res.txt", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Erreur simu : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun playAlertSound() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAlertTime > 2000) { // Alerte toutes les 2 secondes max
            try {
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
            } catch (e: Exception) {
                // Recréer le générateur si nécessaire
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
            }
            lastAlertTime = currentTime
        }
    }

    private fun logGpsPosition(location: Location) {
        val lastLoc = lastLoggedLocation
        // On ne logue que si on a bougé de plus de 10m
        if (lastLoc == null || location.distanceTo(lastLoc) >= 10f) {
            try {
                val directory = getExternalFilesDir(null)
                val file = File(directory, "log_gps.txt")
                
                // Utilisation du temps réel du point GPS
                val timestamp = location.time
                val speedKmh = location.speed * 3.6
                
                val line = "$timestamp, ${location.latitude}, ${location.longitude}, $speedKmh\n"
                file.appendText(line)
                lastLoggedLocation = location
            } catch (e: Exception) {
                // Échec silencieux
            }
        }
    }

    private fun toggleGpsLogging() {
        isLoggingEnabled = !isLoggingEnabled
        if (isLoggingEnabled) {
            btnToggleLog.text = "Désactiver Log GPS"
            Toast.makeText(this, "Logging GPS activé", Toast.LENGTH_SHORT).show()
            logDebug("--- Démarrage session log ---")
        } else {
            btnToggleLog.text = "Activer Log GPS"
            Toast.makeText(this, "Logging GPS désactivé", Toast.LENGTH_SHORT).show()
            lastLoggedLocation = null
        }
    }

    private fun logDebug(message: String) {
        try {
            val directory = getExternalFilesDir(null)
            val file = File(directory, "log_gps.txt")
            val timestamp = System.currentTimeMillis()
            file.appendText("$timestamp, DEBUG, $message\n")
        } catch (e: Exception) {}
    }

    private fun logZoneEvent(label: String) {
        val location = lastValidLocation
        if (location != null) {
            try {
                val directory = getExternalFilesDir(null)
                val file = File(directory, "points_interet.txt")
                val timestamp = System.currentTimeMillis()
                val line = "$timestamp, ${location.latitude}, ${location.longitude}, $label\n"
                file.appendText(line)
                Toast.makeText(this, "$label enregistré", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Erreur écriture log", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Position GPS non disponible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateNearestPanneau(currentLocation: Location, isSimulation: Boolean = false) {
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
            
            runOnUiThread {
                tvNearestPanneau.text = "Panneau le plus proche :\n$decodedName ($distText)\nPos: $pLat, $pLon"
            }
            
            // Si le panneau est détecté pour la première fois, on traite le changement de limite
            if (!isAlreadyDetected(panneau[nearestIndex], pLat, pLon)) {
                if (isSimulation) {
                    logSimulationDetection(panneau[nearestIndex], currentLocation, pLat, pLon)
                }
                val panneauLoc = Location("").apply {
                    latitude = pLat.toDouble()
                    longitude = pLon.toDouble()
                }
                processPanneauForLimit(panneau[nearestIndex], panneauLoc)
                markAsDetected(panneau[nearestIndex], pLat, pLon)
            }
        } else {
            runOnUiThread {
                tvNearestPanneau.text = "Aucun panneau à moins de ${threshold.toInt()}m"
            }
        }
    }

    private fun logSimulationDetection(panneauCode: String, location: Location, pLat: Float, pLon: Float) {
        try {
            val resFile = File(getExternalFilesDir(null), "res.txt")
            val decoded = decodePanneau(panneauCode)
            val line = "Détection: $decoded (Code: $panneauCode) à la position GPS [${location.latitude}, ${location.longitude}]. Position Panneau: [$pLat, $pLon]\n"
            resFile.appendText(line)
        } catch (e: Exception) {}
    }

    override fun onStart() {
        super.onStart()
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val batteryStatus = registerReceiver(powerConnectionReceiver, intentFilter)
        
        // Si batteryStatus est nul (cas rare), on tente de récupérer le dernier état connu
        val lastStatus = batteryStatus ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        checkBatteryStatus(lastStatus)
    }
    
    override fun onResume() {
        super.onResume()
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        checkBatteryStatus(batteryStatus)
        keepAliveHandler.post(keepAliveRunnable)
    }

    override fun onPause() {
        super.onPause()
        saveAppState()
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(powerConnectionReceiver)
        } catch (e: Exception) {}
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun checkBatteryStatus(intent: Intent?) {
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        
        val wasCharging = isCharging
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS

        if (isCharging && !wasCharging) {
            Toast.makeText(this, "Charge détectée : Maintien écran activé", Toast.LENGTH_SHORT).show()
        }
        updateKeepScreenOn(isCharging)
    }

    private fun updateKeepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.decorView.keepScreenOn = true
            
            // Forçage de la luminosité pour éviter la mise en veille
            val params = window.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            window.attributes = params

            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "VMADetect:WakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.decorView.keepScreenOn = false
            
            val params = window.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params

            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
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
            val fileName = getFileNameFromUri(uri)
            tvCsvFilename.text = "Fichier : $fileName"
            
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

    private fun processPanneauForLimit(code: String, panneauLoc: Location) {
        val components = code.split(",").map { it.trim().uppercase() }
        val mainCode = components.firstOrNull { !it.startsWith("M1") && !it.startsWith("M2") } ?: components[0]
        
        // Filtrage des panneaux d'agglomération (Entrée EB10 / Sortie EB20)
        if (mainCode.startsWith("EB10") || mainCode.startsWith("EB20")) {
            // Analyse de la zone (100m autour du panneau actuel)
            val zoneAggloIndices = mutableListOf<Int>()
            for (i in panneau.indices) {
                val pCode = panneau[i].split(",").map { it.trim().uppercase() }
                    .firstOrNull { !it.startsWith("M1") && !it.startsWith("M2") } ?: panneau[i]
                
                if (pCode.startsWith("EB10") || pCode.startsWith("EB20")) {
                    val pLoc = Location("").apply {
                        latitude = lat[i].toDouble()
                        longitude = long[i].toDouble()
                    }
                    if (panneauLoc.distanceTo(pLoc) <= AGGLO_FILTER_DISTANCE) {
                        zoneAggloIndices.add(i)
                    }
                }
            }

            // Logique de filtrage selon le nombre de panneaux dans la zone de 100m
            when {
                zoneAggloIndices.size == 2 -> {
                    val hasEB10 = zoneAggloIndices.any { idx ->
                        val pCode = panneau[idx].split(",").map { it.trim().uppercase() }
                            .firstOrNull { !it.startsWith("M1") && !it.startsWith("M2") } ?: panneau[idx]
                        pCode.startsWith("EB10")
                    }
                    val hasEB20 = zoneAggloIndices.any { idx ->
                        val pCode = panneau[idx].split(",").map { it.trim().uppercase() }
                            .firstOrNull { !it.startsWith("M1") && !it.startsWith("M2") } ?: panneau[idx]
                        pCode.startsWith("EB20")
                    }
                    
                    if (hasEB10 && hasEB20) {
                        if (currentLimit == 50 && mainCode.startsWith("EB10")) {
                            if (isLoggingEnabled) logDebug("Entrée $mainCode ignorée (Paire EB10/EB20, limite à 50)")
                            return
                        } else if (currentLimit > 50 && mainCode.startsWith("EB20")) {
                            if (isLoggingEnabled) logDebug("Sortie $mainCode ignorée (Paire EB10/EB20, limite > 50)")
                            return
                        }
                    }
                }
                zoneAggloIndices.size > 2 -> {
                    // Si c'est une sortie (EB20), on vérifie s'il y a une entrée (EB10) dans la même zone
                    if (mainCode.startsWith("EB20")) {
                        val hasEntranceInZone = zoneAggloIndices.any { idx ->
                            val pCode = panneau[idx].split(",").map { it.trim().uppercase() }
                                .firstOrNull { !it.startsWith("M1") && !it.startsWith("M2") } ?: panneau[idx]
                            pCode.startsWith("EB10")
                        }
                        
                        if (hasEntranceInZone) {
                            if (isLoggingEnabled) logDebug("Sortie $mainCode ignorée : zone dense (>2 panneaux) avec entrée EB10 présente.")
                            return
                        }
                    }
                }
            }
            
            // Mise à jour de l'état du dernier panneau d'agglo vu
            lastAggloType = if (mainCode.startsWith("EB10")) "EB10" else "EB20"
            lastAggloLocation = panneauLoc
        }

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

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "inconnu"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {}
        return name
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator.release()
    }
}
