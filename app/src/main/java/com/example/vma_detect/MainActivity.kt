package com.example.vma_detect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
import java.io.File
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
    private lateinit var etThreshold: EditText

    private val KEY_THRESHOLD = "search_threshold"
    private val detectedPanneaux = mutableSetOf<String>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Permission GPS refusée", Toast.LENGTH_LONG).show()
        }
    }

    private val getCsvFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val tempData = mutableListOf<PanneauData>()
                contentResolver.openInputStream(it)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            val tokens = line.split(",")
                            if (tokens.size >= 3) {
                                // Panneau : Concaténation de tous les champs sauf les deux derniers
                                val panneauName = tokens.dropLast(2).joinToString(",").replace("\"", "").trim()

                                // Vérification des exclusions (M4 et M3a)
                                val excludedPatterns = listOf("M4b", "M4c", "M4f", "M4g", "M4k", "M4l", "M4m", "M4x", "M3a")
                                val isExcluded = excludedPatterns.any { panneauName.contains(it, ignoreCase = true) }

                                if (!isExcluded) {
                                    // Latitude : Avant-dernier champ
                                    val laStr = tokens[tokens.size - 2].replace("\"", "").trim().replace(",", ".")

                                    // Longitude : Dernier champ
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

                // Traitement en arrière-plan pour éviter les blocages et les fichiers tronqués
                Thread {
                    try {
                        // Tri : Latitude croissante
                        val sortedData = tempData.sortedWith(compareBy({ it.latitude }, { it.longitude }))

                        // Mise à jour des listes globales (sur le thread principal)
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
                Toast.makeText(this, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
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
        etThreshold = findViewById(R.id.et_threshold)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        loadSavedThreshold()
        clearLogFile()

        findViewById<Button>(R.id.btn_load_csv).setOnClickListener {
            getCsvFile.launch("text/comma-separated-values")
        }

        checkLocationPermissions()
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

        // Vitesse en km/h
        val speedKmH = (location.speed * 3.6).toInt()
        tvSpeed.text = "Vitesse : $speedKmH km/h"

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
            
            logDetection(panneau[nearestIndex], pLat, pLon)
        } else {
            tvNearestPanneau.text = "Aucun panneau à moins de ${threshold.toInt()}m"
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
        saveThreshold()
    }

    private fun loadSavedThreshold() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        val savedThreshold = sharedPref.getFloat(KEY_THRESHOLD, 5f)
        if (savedThreshold == savedThreshold.toInt().toFloat()) {
            etThreshold.setText(savedThreshold.toInt().toString())
        } else {
            etThreshold.setText(savedThreshold.toString())
        }
    }

    private fun saveThreshold() {
        val threshold = etThreshold.text.toString().toFloatOrNull() ?: 5f
        val sharedPref = getPreferences(Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putFloat(KEY_THRESHOLD, threshold)
            apply()
        }
    }

    private fun clearLogFile() {
        try {
            val directory = getExternalFilesDir(null)
            val file = File(directory, "valeurs_distinctes.txt")
            if (file.exists()) {
                file.delete()
            }
            detectedPanneaux.clear()
        } catch (e: Exception) {
            // Échec silencieux au démarrage
        }
    }

    private fun logDetection(nom: String, la: Float, lo: Float) {
        val decoded = decodePanneau(nom)
        val line = "$nom, $decoded, $la, $lo"
        
        // Si le panneau n'a jamais été détecté (nom + coordonnées uniques)
        if (detectedPanneaux.add(line)) {
            try {
                val directory = getExternalFilesDir(null)
                val file = File(directory, "valeurs_distinctes.txt")
                
                // On ajoute la ligne à la fin du fichier
                file.appendText(line + "\n")
            } catch (e: Exception) {
                // Échec silencieux de l'écriture
            }
        }
    }
}
