package cz.riegel.arrivals.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cz.riegel.arrivals.R
import cz.riegel.arrivals.data.DeparturesRepository
import cz.riegel.arrivals.data.LocationResolver
import cz.riegel.arrivals.data.GolemioApiClient
import cz.riegel.arrivals.data.SettingsRepository
import cz.riegel.arrivals.domain.model.Coordinates
import cz.riegel.arrivals.widget.ArrivalsUpdateWorker
import kotlinx.coroutines.launch
import java.time.Instant

class SettingsActivity : AppCompatActivity() {

    internal var locationResolver: LocationResolver? = null
    private fun getLocationResolver(): LocationResolver =
        locationResolver ?: LocationResolver.create(this).also { locationResolver = it }

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var golemioClient: GolemioApiClient

    private lateinit var editApiKey: EditText
    private lateinit var editRadius: EditText
    private lateinit var editInterval: EditText
    private lateinit var btnTestConnection: Button
    private lateinit var progressTestConnection: ProgressBar
    private lateinit var textTestResult: TextView

    private lateinit var textLocationStatus: TextView
    private lateinit var btnRefreshLocation: Button
    private lateinit var progressLocation: ProgressBar
    private lateinit var btnGrantPermission: Button

    private lateinit var spinnerSimulatedHubs: Spinner

    private lateinit var btnSave: Button
    private lateinit var progressSave: ProgressBar

    private lateinit var layoutBackgroundLocationEducation: View
    private lateinit var btnOpenLocationSettings: Button

    private val permissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            btnGrantPermission.visibility = View.GONE
            loadInitialLocationOrRefresh()
        } else {
            textLocationStatus.text = "⚠ Přístup k poloze byl odepřen. Bez oprávnění nelze automaticky určit nejbližší zastávku."
            btnGrantPermission.visibility = View.VISIBLE
        }
        updateBackgroundLocationPrompt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsRepository = SettingsRepository(this)
        golemioClient = GolemioApiClient.create(this, apiKeyProvider = { settingsRepository.getApiKey() })

        editApiKey = findViewById(R.id.editApiKey)
        editRadius = findViewById(R.id.editRadius)
        editInterval = findViewById(R.id.editInterval)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        progressTestConnection = findViewById(R.id.progressTestConnection)
        textTestResult = findViewById(R.id.textTestResult)

        textLocationStatus = findViewById(R.id.textLocationStatus)
        btnRefreshLocation = findViewById(R.id.btnRefreshLocation)
        progressLocation = findViewById(R.id.progressLocation)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)

        spinnerSimulatedHubs = findViewById(R.id.spinnerSimulatedHubs)
        btnSave = findViewById(R.id.btnSave)
        progressSave = findViewById(R.id.progressSave)
        layoutBackgroundLocationEducation = findViewById(R.id.layoutBackgroundLocationEducation)
        btnOpenLocationSettings = findViewById(R.id.btnOpenLocationSettings)

        // Setup simulation hub dropdown
        val hubNames = SettingsRepository.SIMULATION_HUBS.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, hubNames)
        spinnerSimulatedHubs.adapter = adapter
        spinnerSimulatedHubs.setSelection(settingsRepository.getSimulatedHubIndex())

        // Load existing settings
        settingsRepository.getApiKey()?.let { editApiKey.setText(it) }
        editRadius.setText(settingsRepository.getSearchRadius().toString())
        editInterval.setText(settingsRepository.getRefreshIntervalMinutes().toString())

        btnTestConnection.setOnClickListener {
            testApiConnection()
        }

        btnRefreshLocation.setOnClickListener {
            refreshLocationStatus(isManual = true)
        }

        btnGrantPermission.setOnClickListener {
            requestLocationPermissions()
        }

        btnSave.setOnClickListener {
            saveSettings()
        }

        layoutBackgroundLocationEducation.setOnClickListener {
            openApplicationDetailsSettings()
        }

        btnOpenLocationSettings.setOnClickListener {
            openApplicationDetailsSettings()
        }

        // Check permissions or refresh location on startup
        checkPermissionsAndLoadLocation()
    }

    private fun checkPermissionsAndLoadLocation() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            btnGrantPermission.visibility = View.VISIBLE
            textLocationStatus.text = "⚠ Oprávnění k poloze není uděleno. Klepněte na tlačítko níže pro povolení."
            requestLocationPermissions()
        } else {
            btnGrantPermission.visibility = View.GONE
            loadInitialLocationOrRefresh()
        }
        updateBackgroundLocationPrompt()
    }

    private fun loadInitialLocationOrRefresh() {
        // Immediately display last known Location Fix (cached coordinates) so users don't see an empty spinner
        val cachedCoords = DeparturesRepository.create(this).getLastCoordinates()
        if (cachedCoords != null) {
            updateLocationDisplay(cachedCoords, "poslední známá")
            progressLocation.visibility = View.GONE
            btnRefreshLocation.isEnabled = true
            return
        }

        lifecycleScope.launch {
            val lastLocation = try {
                getLocationResolver().getLastKnownLocation()
            } catch (_: Exception) {
                null
            }

            if (lastLocation != null) {
                val coords = Coordinates(lat = lastLocation.latitude, lon = lastLocation.longitude)
                val accuracy = if (lastLocation.hasAccuracy()) "±${lastLocation.accuracy.toInt()} m" else "poslední známá"
                DeparturesRepository.create(this@SettingsActivity).saveLocation(coords, Instant.now())
                updateLocationDisplay(coords, accuracy)
                progressLocation.visibility = View.GONE
                btnRefreshLocation.isEnabled = true
            } else {
                refreshLocationStatus(isManual = false)
            }
        }
    }

    private fun requestLocationPermissions() {
        permissionRequestLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun refreshLocationStatus(isManual: Boolean = true) {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            textLocationStatus.text = "⚠ Oprávnění k poloze chybí. Povolte přístup k poloze pro zjištění zastávek."
            btnGrantPermission.visibility = View.VISIBLE
            return
        }

        btnGrantPermission.visibility = View.GONE
        progressLocation.visibility = View.VISIBLE
        btnRefreshLocation.isEnabled = false
        textLocationStatus.text = "Zjišťování aktuální přesné GPS polohy…"

        lifecycleScope.launch {
            try {
                val highAccuracyTimeout = if (isManual) {
                    LocationResolver.MANUAL_REFRESH_HIGH_ACCURACY_TIMEOUT_MS
                } else {
                    LocationResolver.DEFAULT_HIGH_ACCURACY_TIMEOUT_MS
                }

                val location = getLocationResolver().resolveLocation(
                    highAccuracyTimeoutMs = highAccuracyTimeout,
                    balancedTimeoutMs = LocationResolver.DEFAULT_BALANCED_TIMEOUT_MS
                )

                if (location != null) {
                    val lat = location.latitude
                    val lon = location.longitude
                    val accuracy = if (location.hasAccuracy()) "±${location.accuracy.toInt()} m" else "neznámá"
                    val coords = Coordinates(lat = lat, lon = lon)

                    DeparturesRepository.create(this@SettingsActivity).saveLocation(
                        coords,
                        Instant.now()
                    )
                    if (settingsRepository.isConfigured()) {
                        ArrivalsUpdateWorker.enqueueOneTimeWork(this@SettingsActivity)
                    }

                    updateLocationDisplay(coords, accuracy)
                } else {
                    textLocationStatus.text = "⚠ GPS polohu se nepodařilo zaměřit. Zkontrolujte, zda je zapnutá GPS."
                }
            } catch (e: Exception) {
                textLocationStatus.text = "Chyba při čtení polohy: ${e.message}"
            } finally {
                progressLocation.visibility = View.GONE
                btnRefreshLocation.isEnabled = true
            }
        }
    }

    private fun updateLocationDisplay(coords: Coordinates, accuracyStr: String) {
        if (!GolemioApiClient.isWithinPragueCoverage(coords)) {
            textLocationStatus.text = "⚠ Poloha: %.4f, %.4f (Přesnost: %s)\nPoloha je mimo oblast Prahy PID. Pokud používáte emulátor, nastavte GPS na Prahu nebo zvolte simulovanou zastávku níže."
                .format(coords.lat, coords.lon, accuracyStr)
        } else {
            val closest = golemioClient.findClosestStopDistance(coords)
            if (closest != null) {
                textLocationStatus.text = "✓ GPS poloha: %.4f, %.4f (Přesnost: %s)\nNejbližší zastávka: %s (%d m od vás)"
                    .format(coords.lat, coords.lon, accuracyStr, closest.first, closest.second)
            } else {
                textLocationStatus.text = "✓ GPS poloha: %.4f, %.4f (Přesnost: %s)\nV okolí nebyla nalezena zastávka."
                    .format(coords.lat, coords.lon, accuracyStr)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLocationFromLocationManager(): Location? {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            providers.mapNotNull { provider ->
                try {
                    if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null
                } catch (_: Exception) {
                    null
                }
            }.maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        }
    }

    private fun testApiConnection() {
        val key = editApiKey.text.toString().trim()
        if (key.isBlank()) {
            showTestResult(success = false, message = "Zadejte prosím API token.")
            return
        }

        textTestResult.visibility = View.VISIBLE
        textTestResult.setTextColor(0xFF81B0FF.toInt())
        textTestResult.text = getString(R.string.testing_connection)
        btnTestConnection.isEnabled = false
        progressTestConnection.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = settingsRepository.validateApiKey(key)
            progressTestConnection.visibility = View.GONE
            btnTestConnection.isEnabled = true
            if (result.isSuccess) {
                showTestResult(success = true, message = "✓ Připojení k API Golemio je úspěšné! Token je platný.")
            } else {
                val err = result.exceptionOrNull()?.message ?: "Připojení se nezdařilo"
                showTestResult(success = false, message = "✗ $err")
            }
        }
    }

    private fun showTestResult(success: Boolean, message: String) {
        textTestResult.visibility = View.VISIBLE
        textTestResult.setTextColor(if (success) 0xFF81C784.toInt() else 0xFFFF8A80.toInt())
        textTestResult.text = message
    }

    private fun saveSettings() {
        val key = editApiKey.text.toString().trim()
        val radius = editRadius.text.toString().toIntOrNull() ?: SettingsRepository.DEFAULT_SEARCH_RADIUS
        val interval = editInterval.text.toString().toLongOrNull() ?: SettingsRepository.DEFAULT_REFRESH_INTERVAL
        val hubIndex = spinnerSimulatedHubs.selectedItemPosition

        btnSave.isEnabled = false
        progressSave.visibility = View.VISIBLE

        settingsRepository.saveApiKey(key)
        settingsRepository.saveSearchRadius(radius)
        settingsRepository.saveRefreshIntervalMinutes(interval)
        settingsRepository.saveSimulatedHubIndex(hubIndex)

        Toast.makeText(this, "Nastavení uloženo", Toast.LENGTH_SHORT).show()

        // Reschedule periodic work and trigger an immediate update
        ArrivalsUpdateWorker.schedulePeriodicWork(this, interval)
        ArrivalsUpdateWorker.enqueueOneTimeWork(this)

        finish()
    }

    override fun onResume() {
        super.onResume()
        updateBackgroundLocationPrompt()
    }

    private fun isBackgroundLocationGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateBackgroundLocationPrompt() {
        val granted = isBackgroundLocationGranted()
        layoutBackgroundLocationEducation.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun openApplicationDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
}
