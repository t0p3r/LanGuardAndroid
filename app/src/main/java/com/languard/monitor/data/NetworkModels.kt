package com.languard.monitor.data

import kotlinx.serialization.Serializable

@Serializable
data class NetworkDevice(
    val id: String,
    val name: String,
    val hostname: String,
    val ip: String,
    val mac: String,
    val vendor: String,
    val category: String,
    val status: String,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val totalDownloadGb24h: Double,
    val totalUploadGb24h: Double,
    val anomalyScore: Int = 0
)

@Serializable
data class SecurityAlert(
    val id: String,
    val timestamp: String,
    val severity: String,
    val type: String,
    val title: String,
    val description: String,
    val deviceId: String? = null,
    val deviceName: String,
    val mac: String,
    val ip: String,
    val acknowledged: Boolean,
    val actionTaken: String? = null
)

@Serializable
data class AutoBlockRule(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val action: String,
    val triggeredCount: Int = 0
)

@Serializable
data class DeviceDelta(
    val id: String,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val totalDownloadGb24h: Double,
    val totalUploadGb24h: Double,
    val anomalyScore: Int = 0,
    val status: String
)

@Serializable
data class NetworkSnapshot(
    val networkName: String,
    val gatewayIp: String,
    val subnetCidr: String,
    val devices: List<NetworkDevice>,
    val alerts: List<SecurityAlert>,
    val rules: List<AutoBlockRule> = emptyList()
)
