package cz.riegel.arrivals.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import cz.riegel.arrivals.R
import cz.riegel.arrivals.data.SettingsRepository
import cz.riegel.arrivals.data.StopRegistry
import cz.riegel.arrivals.widget.ArrivalsUpdateWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var stopRegistry: StopRegistry

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

    private val permissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            refreshLocationStatus()
        } else {
            textLocationStatus.text = "⚠ Přístup k poloze byl odepřen. Bez oprávnění nelze automaticky určit nejbližší zastávku."
            btnGrantPermission.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsRepository = SettingsRepository(this)
        stopRegistry = StopRegistry(this)

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
            refreshLocationStatus()
        }

        btnGrantPermission.setOnClickListener {
            requestLocationPermissions()
        }

        btnSave.setOnClickListener {
            saveSettings()
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
            refreshLocationStatus()
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

    private fun refreshLocationStatus() {
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
                val fusedClient = LocationServices.getFusedLocationProviderClient(this@SettingsActivity)
                val cts = CancellationTokenSource()

                // Actively query high accuracy GPS
                val location = withTimeoutOrNull(8_000) {
                    fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
                } ?: fusedClient.lastLocation.await()

                if (location != null) {
                    val lat = location.latitude
                    val lon = location.longitude
                    val accuracy = if (location.hasAccuracy()) "±${location.accuracy.toInt()} m" else "neznámá"

                    if (!StopRegistry.isWithinPragueCoverage(lat, lon)) {
                        textLocationStatus.text = "⚠ Poloha: %.4f, %.4f (Přesnost: %s)\nPoloha je mimo oblast Prahy PID. Pokud používáte emulátor, nastavte GPS na Prahu nebo zvolte simulovanou zastávku níže."
                            .format(lat, lon, accuracy)
                    } else {
                        val closest = stopRegistry.findClosestStop(lat, lon)
                        if (closest != null) {
                            textLocationStatus.text = "✓ GPS poloha: %.4f, %.4f (Přesnost: %s)\nNejbližší zastávka: %s (%d m od vás)"
                                .format(lat, lon, accuracy, closest.first.n, closest.second)
                        } else {
                            textLocationStatus.text = "✓ GPS poloha: %.4f, %.4f (Přesnost: %s)\nV okolí nebyla nalezena zastávka."
                                .format(lat, lon, accuracy)
                        }
                    }
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
}
