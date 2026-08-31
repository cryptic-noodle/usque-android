package com.crypticnoodle.usquevpn

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.*
import usqueandroid.LogListener
import usqueandroid.Usqueandroid

class MainActivity : Activity() {

    companion object {
        private const val VPN_REQUEST_CODE = 1001
        private const val PREFS_NAME = "UsqueVpnPrefs"
        private const val KEY_SNI = "sni"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_DNS = "dns"
        private const val KEY_HTTP2 = "http2"
        private const val KEY_KEEPALIVE = "keepalive"
        private const val KEY_MTU = "mtu"
        private const val KEY_ALWAYS_RECONNECT = "always_reconnect"
        private const val KEY_LOG_LEVEL = "log_level"

        val LOG_LEVEL_NAMES = arrayOf("DEBUG", "INFO", "WARN", "ERROR")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var ipInfoText: TextView
    private lateinit var settingsButton: Button
    private lateinit var logsButton: Button
    private lateinit var modeText: TextView
    private lateinit var sniText: TextView
    private lateinit var endpointText: TextView
    private lateinit var dnsText: TextView
    private lateinit var tuningText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        statusText = findViewById(R.id.status_text)
        connectButton = findViewById(R.id.connect_button)
        ipInfoText = findViewById(R.id.ip_info_text)
        settingsButton = findViewById(R.id.settings_button)
        logsButton = findViewById(R.id.logs_button)
        modeText = findViewById(R.id.mode_text)
        sniText = findViewById(R.id.sni_text)
        endpointText = findViewById(R.id.endpoint_text)
        dnsText = findViewById(R.id.dns_text)
        tuningText = findViewById(R.id.tuning_text)

        // Load saved settings into Go library
        loadSavedSettings()

        connectButton.setOnClickListener {
            if (UsqueVpnService.isRunning) {
                stopVpn()
            } else {
                startVpn()
            }
        }

        settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        logsButton.setOnClickListener {
            showLogsDialog()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun loadSavedSettings() {
        val savedSni = prefs.getString(KEY_SNI, "api.cloudflare.com") ?: "api.cloudflare.com"
        Usqueandroid.setSNI(savedSni)

        val savedEndpoint = prefs.getString(KEY_ENDPOINT, "") ?: ""
        Usqueandroid.setEndpoint(savedEndpoint)

        val savedDns = prefs.getString(KEY_DNS, "1.1.1.1, 1.0.0.1") ?: "1.1.1.1, 1.0.0.1"
        Usqueandroid.setDNS(savedDns)

        val savedHttp2 = prefs.getBoolean(KEY_HTTP2, false)
        Usqueandroid.setHTTP2(savedHttp2)

        val savedKeepalive = prefs.getInt(KEY_KEEPALIVE, 30)
        Usqueandroid.setKeepalivePeriod(savedKeepalive.toLong())

        val savedMtu = prefs.getInt(KEY_MTU, 1280)
        Usqueandroid.setMTU(savedMtu.toLong())

        val savedAlwaysReconnect = prefs.getBoolean(KEY_ALWAYS_RECONNECT, true)
        Usqueandroid.setAlwaysReconnect(savedAlwaysReconnect)

        val savedLogLevel = prefs.getInt(KEY_LOG_LEVEL, 1) // 1 = INFO
        Usqueandroid.setLogLevel(savedLogLevel.toLong())
    }

    private fun saveSettings(
        sni: String,
        endpoint: String,
        dns: String,
        http2: Boolean,
        keepalive: Int,
        mtu: Int,
        alwaysReconnect: Boolean,
        logLevel: Int
    ) {
        prefs.edit()
            .putString(KEY_SNI, sni)
            .putString(KEY_ENDPOINT, endpoint)
            .putString(KEY_DNS, dns)
            .putBoolean(KEY_HTTP2, http2)
            .putInt(KEY_KEEPALIVE, keepalive)
            .putInt(KEY_MTU, mtu)
            .putBoolean(KEY_ALWAYS_RECONNECT, alwaysReconnect)
            .putInt(KEY_LOG_LEVEL, logLevel)
            .apply()

        Usqueandroid.setSNI(sni)
        Usqueandroid.setEndpoint(endpoint)
        Usqueandroid.setDNS(dns)
        Usqueandroid.setHTTP2(http2)
        Usqueandroid.setKeepalivePeriod(keepalive.toLong())
        Usqueandroid.setMTU(mtu.toLong())
        Usqueandroid.setAlwaysReconnect(alwaysReconnect)
        Usqueandroid.setLogLevel(logLevel.toLong())
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)

        val http2CheckBox = dialogView.findViewById<CheckBox>(R.id.http2_checkbox)
        val sniInput = dialogView.findViewById<EditText>(R.id.sni_input)
        val endpointInput = dialogView.findViewById<EditText>(R.id.endpoint_input)
        val dnsInput = dialogView.findViewById<EditText>(R.id.dns_input)
        val keepaliveInput = dialogView.findViewById<EditText>(R.id.keepalive_input)
        val mtuInput = dialogView.findViewById<EditText>(R.id.mtu_input)
        val alwaysReconnectCheckBox = dialogView.findViewById<CheckBox>(R.id.always_reconnect_checkbox)
        val logLevelSpinner = dialogView.findViewById<Spinner>(R.id.loglevel_spinner)

        // Setup Log Level Spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, LOG_LEVEL_NAMES)
        logLevelSpinner.adapter = adapter

        // Populate current values
        http2CheckBox.isChecked = prefs.getBoolean(KEY_HTTP2, Usqueandroid.getHTTP2())
        sniInput.setText(prefs.getString(KEY_SNI, Usqueandroid.getSNI()))

        val currentEndpoint = prefs.getString(KEY_ENDPOINT, "") ?: ""
        endpointInput.setText(currentEndpoint)

        val currentDns = prefs.getString(KEY_DNS, Usqueandroid.getDNS()) ?: "1.1.1.1, 1.0.0.1"
        dnsInput.setText(currentDns)

        keepaliveInput.setText(prefs.getInt(KEY_KEEPALIVE, Usqueandroid.getKeepalivePeriod().toInt()).toString())
        mtuInput.setText(prefs.getInt(KEY_MTU, Usqueandroid.getMTU().toInt()).toString())
        alwaysReconnectCheckBox.isChecked = prefs.getBoolean(KEY_ALWAYS_RECONNECT, Usqueandroid.getAlwaysReconnect())

        val currentLogLevel = prefs.getInt(KEY_LOG_LEVEL, Usqueandroid.getLogLevel().toInt()).coerceIn(0, 3)
        logLevelSpinner.setSelection(currentLogLevel)

        AlertDialog.Builder(this)
            .setTitle("Connection & Engine Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val sni = sniInput.text.toString().trim().ifEmpty { "api.cloudflare.com" }
                val endpoint = endpointInput.text.toString().trim()
                val dns = dnsInput.text.toString().trim().ifEmpty { "1.1.1.1, 1.0.0.1" }
                val http2 = http2CheckBox.isChecked
                val keepalive = keepaliveInput.text.toString().toIntOrNull() ?: 30
                val mtu = mtuInput.text.toString().toIntOrNull() ?: 1280
                val alwaysReconnect = alwaysReconnectCheckBox.isChecked
                val logLevel = logLevelSpinner.selectedItemPosition

                saveSettings(sni, endpoint, dns, http2, keepalive, mtu, alwaysReconnect, logLevel)
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ ->
                saveSettings("api.cloudflare.com", "", "1.1.1.1, 1.0.0.1", false, 30, 1280, true, 1)
                Usqueandroid.resetConnectionOptions()
                Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
                updateUI()
            }
            .show()
    }

    private fun showLogsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_logs, null)
        val logsTextView = dialogView.findViewById<TextView>(R.id.logs_text_view)
        val logsScrollView = dialogView.findViewById<ScrollView>(R.id.logs_scroll_view)
        val btnCopy = dialogView.findViewById<Button>(R.id.btn_copy_logs)
        val btnClear = dialogView.findViewById<Button>(R.id.btn_clear_logs)

        fun refreshLogs() {
            val logs = Usqueandroid.getLogs()
            logsTextView.text = if (logs.isNotEmpty()) logs else "No logs yet..."
            logsScrollView.post {
                logsScrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }

        refreshLogs()

        // Attach live log listener
        val logListener = object : LogListener {
            override fun onLogMessage(message: String?) {
                mainHandler.post {
                    refreshLogs()
                }
            }
        }
        Usqueandroid.setLogListener(logListener)

        btnCopy.setOnClickListener {
            val logs = Usqueandroid.getLogs()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Usque Logs", logs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnClear.setOnClickListener {
            Usqueandroid.clearLogs()
            refreshLogs()
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Close") { d, _ ->
                d.dismiss()
            }
            .create()

        dialog.setOnDismissListener {
            Usqueandroid.setLogListener(null)
        }

        dialog.show()
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            onVpnPermissionGranted()
        }
    }

    private fun stopVpn() {
        UsqueVpnService.stop()

        val intent = Intent(this, UsqueVpnService::class.java)
        intent.action = UsqueVpnService.ACTION_DISCONNECT
        startService(intent)

        connectButton.postDelayed({
            updateUI()
        }, 1000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                onVpnPermissionGranted()
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onVpnPermissionGranted() {
        val intent = Intent(this, UsqueVpnService::class.java)
        startService(intent)

        connectButton.postDelayed({
            updateUI()
        }, 1500)
    }

    private fun updateUI() {
        val configPath = "${filesDir.absolutePath}/config.json"

        if (UsqueVpnService.isRunning) {
            statusText.text = "Connected"
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            connectButton.text = "Disconnect"
            settingsButton.isEnabled = false
        } else {
            statusText.text = "Disconnected"
            statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            connectButton.text = "Connect"
            settingsButton.isEnabled = true
        }

        if (Usqueandroid.isRegistered(configPath)) {
            val ipv4 = Usqueandroid.getAssignedIPv4(configPath)
            val ipv6 = Usqueandroid.getAssignedIPv6(configPath)
            ipInfoText.text = "IPv4: $ipv4\nIPv6: $ipv6"
        } else {
            ipInfoText.text = "Not registered"
        }

        // Protocol Mode
        val isHttp2 = prefs.getBoolean(KEY_HTTP2, Usqueandroid.getHTTP2())
        modeText.text = if (isHttp2) "Mode: HTTP/2 (TCP)" else "Mode: HTTP/3 (QUIC)"

        // SNI
        val currentSni = prefs.getString(KEY_SNI, Usqueandroid.getSNI()) ?: "api.cloudflare.com"
        sniText.text = "SNI: $currentSni"

        // Endpoint
        val currentEndpoint = prefs.getString(KEY_ENDPOINT, "") ?: ""
        val displayEndpoint = if (currentEndpoint.isNotEmpty()) {
            currentEndpoint
        } else {
            Usqueandroid.getDefaultEndpoint(configPath)
        }
        endpointText.text = "Endpoint: $displayEndpoint"

        // DNS
        val currentDns = prefs.getString(KEY_DNS, Usqueandroid.getDNS()) ?: "1.1.1.1, 1.0.0.1"
        dnsText.text = "DNS: $currentDns"

        // Tuning parameters
        val keepalive = prefs.getInt(KEY_KEEPALIVE, Usqueandroid.getKeepalivePeriod().toInt())
        val mtu = prefs.getInt(KEY_MTU, Usqueandroid.getMTU().toInt())
        val alwaysRec = prefs.getBoolean(KEY_ALWAYS_RECONNECT, Usqueandroid.getAlwaysReconnect())
        tuningText.text = "Keepalive: ${keepalive}s | MTU: $mtu | AutoRec: $alwaysRec"
    }
}
