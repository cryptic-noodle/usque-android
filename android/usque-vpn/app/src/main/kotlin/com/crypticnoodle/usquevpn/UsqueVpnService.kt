package com.crypticnoodle.usquevpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import usqueandroid.PacketFlow
import usqueandroid.Usqueandroid
import usqueandroid.VpnStateCallback
import java.io.FileOutputStream

/**
 * UsqueVpnService provides a system-level VPN using Cloudflare WARP/MASQUE protocol.
 * 
 * The service works by:
 * 1. Creating a TUN interface that captures all device traffic
 * 2. Passing the TUN file descriptor to the Go library
 * 3. Go library handles all traffic forwarding directly via OS kernel fd for optimal battery/speed
 */
class UsqueVpnService : VpnService() {

    interface ConnectionStateListener {
        fun onStateChanged(connected: Boolean)
    }

    companion object {
        private const val TAG = "UsqueVpnService"
        const val ACTION_DISCONNECT = "com.crypticnoodle.usquevpn.DISCONNECT"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "usque_vpn_status_channel"
        
        var isRunning = false
            private set
            
        // Reference to the running service instance for direct stop
        private var instance: UsqueVpnService? = null
        private var stateListener: ConnectionStateListener? = null
        
        fun setStateListener(listener: ConnectionStateListener?) {
            stateListener = listener
        }

        private fun notifyState(connected: Boolean) {
            stateListener?.onStateChanged(connected)
        }

        fun stop() {
            Log.i(TAG, "Static stop() called")
            instance?.disconnect()
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Usque VPN Status"
            val descriptionText = "Shows active VPN connection status and controls"
            val importance = NotificationManager.IMPORTANCE_LOW // Low importance = No intrusive audio alerts, battery efficient
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String = "Connected & Encrypted via Cloudflare WARP"): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, UsqueVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val pendingDisconnect = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Usque VPN")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(pendingOpenApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Disconnect",
                    pendingDisconnect
                ).build()
            )
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Check if this is a disconnect intent
        if (intent?.action == ACTION_DISCONNECT) {
            Log.i(TAG, "Received disconnect intent")
            disconnect()
            return START_NOT_STICKY
        }
        
        Log.i(TAG, "VPN Service starting...")
        
        if (isRunning) {
            Log.w(TAG, "VPN already running")
            return START_STICKY
        }

        // Immediately promote to foreground service to satisfy Android 8.0+ ANR timeout requirements
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to Cloudflare WARP..."))

        Thread {
            setupAndStartTunnel()
        }.start()

        return START_STICKY
    }

    private fun setupAndStartTunnel() {
        val configPath = "${filesDir.absolutePath}/config.json"

        // Check registration
        if (!Usqueandroid.isRegistered(configPath)) {
            Log.i(TAG, "Not registered, registering now...")
            val error = Usqueandroid.register(configPath, android.os.Build.MODEL)
            if (error.isNotEmpty()) {
                Log.e(TAG, "Registration failed: $error")
                disconnect()
                return
            }
            Log.i(TAG, "Registration successful")
        }

        // Get assigned IP addresses
        val vpnIpv4 = Usqueandroid.getAssignedIPv4(configPath)
        val vpnIpv6 = Usqueandroid.getAssignedIPv6(configPath)

        Log.i(TAG, "Assigned IPs: v4=$vpnIpv4, v6=$vpnIpv6")

        if (vpnIpv4.isEmpty()) {
            Log.e(TAG, "No IPv4 address assigned")
            disconnect()
            return
        }

        // Create VPN interface
        try {
            val mtu = Usqueandroid.getMTU()
            val effectiveMtu = if (mtu > 0) mtu.toInt() else 1280

            val builder = Builder()
                .setSession("Usque WARP VPN")
                .setMtu(effectiveMtu)
                
            // Add IPv4 address and route
            builder.addAddress(vpnIpv4, 32)
            builder.addRoute("0.0.0.0", 0)
            
            // Add IPv6 address and route if available
            if (vpnIpv6.isNotEmpty()) {
                try {
                    builder.addAddress(vpnIpv6, 128)
                    builder.addRoute("::", 0)  // Route all IPv6 traffic through VPN
                    Log.i(TAG, "IPv6 configured: $vpnIpv6")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add IPv6, continuing with IPv4 only: ${e.message}")
                }
            }

            // Add DNS servers (custom or default 1.1.1.1, 1.0.0.1)
            val customDnsStr = Usqueandroid.getDNS().trim()
            if (customDnsStr.isNotEmpty()) {
                val dnsList = customDnsStr.split(",", " ", ";").map { it.trim() }.filter { it.isNotEmpty() }
                for (dns in dnsList) {
                    try {
                        builder.addDnsServer(dns)
                        Log.i(TAG, "Configured custom DNS server: $dns")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add DNS server '$dns': ${e.message}")
                    }
                }
            } else {
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("1.0.0.1")
                builder.addDnsServer("2606:4700:4700::1111")
                builder.addDnsServer("2606:4700:4700::1001")
            }

            // Exclude the Cloudflare endpoint from VPN routing
            // This is critical: the QUIC / HTTP/2 connection to Cloudflare must NOT go through the VPN
            builder.addDisallowedApplication(packageName)

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                disconnect()
                return
            }

            val fd = vpnInterface!!.fd
            outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)

            Log.i(TAG, "VPN interface established with fd=$fd, MTU=$effectiveMtu")

            isRunning = true

            // Update foreground notification status
            updateNotification("Connected & Encrypted via Cloudflare WARP")

            // Create packet flow for writing packets back to TUN
            val packetFlow = object : PacketFlow {
                override fun writePacket(data: ByteArray?) {
                    if (data != null && data.isNotEmpty()) {
                        try {
                            outputStream?.write(data)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to write packet to TUN", e)
                        }
                    }
                }
            }

            // Create state callback
            val callback = object : VpnStateCallback {
                override fun onConnected() {
                    Log.i(TAG, "MASQUE tunnel connected to Cloudflare!")
                    updateNotification("Connected & Encrypted via Cloudflare WARP")
                    notifyState(true)
                }

                override fun onDisconnected(reason: String?) {
                    Log.w(TAG, "MASQUE tunnel disconnected: $reason")
                    notifyState(false)
                    // If the tunnel stopped and is not running, cleanup
                    if (!Usqueandroid.isRunning()) {
                        disconnect()
                    }
                }

                override fun onError(message: String?) {
                    Log.e(TAG, "MASQUE tunnel error: $message")
                }
            }

            // Start the Go tunnel with our TUN file descriptor
            val tunnelError = Usqueandroid.startTunnel(configPath, fd.toLong(), effectiveMtu.toLong(), packetFlow, callback)
            if (tunnelError.isNotEmpty()) {
                Log.e(TAG, "Failed to start tunnel: $tunnelError")
                disconnect()
                return
            }

            Log.i(TAG, "VPN Service started successfully!")
            notifyState(true)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VPN interface", e)
            disconnect()
        }
    }

    /**
     * Disconnect the VPN - can be called from anywhere
     */
    fun disconnect() {
        Log.i(TAG, "disconnect() called")
        
        val wasRunning = isRunning
        isRunning = false
        if (wasRunning) {
            notifyState(false)
        }

        // Stop foreground notification
        stopForeground(true)

        // Stop the Go tunnel first
        try {
            Log.i(TAG, "Stopping Go tunnel...")
            Usqueandroid.stopTunnel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Go tunnel", e)
        }

        // Close output stream
        try {
            Log.i(TAG, "Closing output stream...")
            outputStream?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing output stream", e)
        }
        outputStream = null

        // Close VPN interface
        try {
            Log.i(TAG, "Closing VPN interface...")
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        // Stop the service
        Log.i(TAG, "Stopping service...")
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy() called")
        
        // Make sure everything is cleaned up
        if (isRunning) {
            disconnect()
        }
        
        instance = null
        super.onDestroy()
        Log.i(TAG, "VPN Service destroyed")
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by user")
        disconnect()
    }
}

