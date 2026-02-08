package com.llamafarm.atmosphere.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    private const val TAG = "NetworkUtils"
    
    /**
     * Get the local IP address of this device.
     * Returns the WiFi IP if available, otherwise the first available IPv4 address.
     */
    fun getLocalIpAddress(context: Context): String? {
        return try {
            // Try WiFi first
            getWifiIpAddress(context)?.let { return it }
            
            // Fall back to any available network interface
            NetworkInterface.getNetworkInterfaces().toList().forEach { netInterface ->
                netInterface.inetAddresses.toList().forEach { address ->
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        Log.d(TAG, "Found IP: $ip on ${netInterface.name}")
                        return ip
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
            null
        }
    }
    
    /**
     * Get WiFi IP address specifically.
     */
    @Suppress("DEPRECATION")
    private fun getWifiIpAddress(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.ipAddress?.let { ipInt ->
                // Convert int to IP string (little-endian)
                String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get WiFi IP", e)
            null
        }
    }
    
    /**
     * Check if the device is connected to WiFi.
     */
    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    
    /**
     * Build a WebSocket endpoint URL for this device.
     * @param port The port the mesh server is listening on (default 11451)
     */
    fun buildLocalEndpoint(context: Context, port: Int = 11451): String? {
        val ip = getLocalIpAddress(context) ?: return null
        return "ws://$ip:$port/api/ws"
    }
}
