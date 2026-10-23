package cz.riegel.arrivals.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cz.riegel.arrivals.R
import cz.riegel.arrivals.data.SettingsRepository
import cz.riegel.arrivals.widget.ArrivalsUpdateWorker
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var editApiKey: EditText
    private lateinit var editRadius: EditText
    private lateinit var editInterval: EditText
    private lateinit var btnTestConnection: Button
    private lateinit var btnSave: Button
    private lateinit var textTestResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsRepository = SettingsRepository(this)

        editApiKey = findViewById(R.id.editApiKey)
        editRadius = findViewById(R.id.editRadius)
        editInterval = findViewById(R.id.editInterval)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnSave = findViewById(R.id.btnSave)
        textTestResult = findViewById(R.id.textTestResult)

        // Load existing settings
        settingsRepository.getApiKey()?.let { editApiKey.setText(it) }
        editRadius.setText(settingsRepository.getSearchRadius().toString())
        editInterval.setText(settingsRepository.getRefreshIntervalMinutes().toString())

        btnTestConnection.setOnClickListener {
            testApiConnection()
        }

        btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun testApiConnection() {
        val key = editApiKey.text.toString().trim()
        if (key.isBlank()) {
            showTestResult(success = false, message = "Please enter an API token first.")
            return
        }

        textTestResult.visibility = View.VISIBLE
        textTestResult.setTextColor(0xFF81B0FF.toInt())
        textTestResult.text = "Testing connection to Golemio…"
        btnTestConnection.isEnabled = false

        lifecycleScope.launch {
            val result = settingsRepository.validateApiKey(key)
            btnTestConnection.isEnabled = true
            if (result.isSuccess) {
                showTestResult(success = true, message = "✓ Connection successful! Valid token.")
            } else {
                val err = result.exceptionOrNull()?.message ?: "Connection failed"
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

        settingsRepository.saveApiKey(key)
        settingsRepository.saveSearchRadius(radius)
        settingsRepository.saveRefreshIntervalMinutes(interval)

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()

        // Reschedule periodic work with updated interval and enqueue one-time work
        ArrivalsUpdateWorker.schedulePeriodicWork(this, interval)
        ArrivalsUpdateWorker.enqueueOneTimeWork(this)
        finish()
    }
}
