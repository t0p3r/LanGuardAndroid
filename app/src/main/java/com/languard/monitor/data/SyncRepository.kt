package com.languard.monitor.data

import com.languard.monitor.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class SyncRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var isDisposed = false
    private var onCriticalAlertCallback: ((SecurityAlert) -> Unit)? = null

    private val _snapshot = MutableStateFlow<NetworkSnapshot?>(null)
    val snapshot: StateFlow<NetworkSnapshot?> = _snapshot.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun connect(onCriticalAlert: (SecurityAlert) -> Unit) {
        isDisposed = false
        onCriticalAlertCallback = onCriticalAlert
        fetchFallbackSnapshot()
        establishWebSocket()
    }

    private fun fetchFallbackSnapshot() {
        scope.launch {
            try {
                val req = Request.Builder()
                    .url("${BuildConfig.API_BASE_URL}/api/state")
                    .header("X-LanGuard-Token", BuildConfig.SYNC_TOKEN)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return@use
                        val parsed = json.decodeFromString(NetworkSnapshot.serializer(), body)
                        _snapshot.value = parsed
                    }
                }
            } catch (_: Exception) {
                // Ignore HTTP fallback error; WS will retry
            }
        }
    }

    private fun establishWebSocket() {
        if (isDisposed) return
        socket?.close(1000, "Reconnecting")
        socket = null

        val request = Request.Builder()
            .url(BuildConfig.WS_STREAM_URL)
            .header("X-LanGuard-Token", BuildConfig.SYNC_TOKEN)
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connected.value = true
                reconnectJob?.cancel()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = json.parseToJsonElement(text).jsonObject
                when (root["type"]?.jsonPrimitive?.content) {
                    "init" -> {
                        val payload = root["payload"] ?: return
                        _snapshot.value = json.decodeFromJsonElement(NetworkSnapshot.serializer(), payload)
                    }
                    "telemetry:tick" -> {
                        val payloadObj = root["payload"]?.jsonObject ?: return
                        val devicesEl = payloadObj["devices"] ?: return
                        val deltas = json.decodeFromJsonElement(ListSerializer(DeviceDelta.serializer()), devicesEl)
                        val deltaMap = deltas.associateBy { it.id }
                        _snapshot.value = _snapshot.value?.let { cur ->
                            cur.copy(
                                devices = cur.devices.map { dev ->
                                    val d = deltaMap[dev.id]
                                    if (d != null) {
                                        dev.copy(
                                            downloadMbps = d.downloadMbps,
                                            uploadMbps = d.uploadMbps,
                                            totalDownloadGb24h = d.totalDownloadGb24h,
                                            totalUploadGb24h = d.totalUploadGb24h,
                                            anomalyScore = d.anomalyScore,
                                            status = d.status
                                        )
                                    } else dev
                                }
                            )
                        }
                    }
                    "device:updated" -> {
                        val updated = json.decodeFromJsonElement(NetworkDevice.serializer(), root["payload"] ?: return)
                        _snapshot.value = _snapshot.value?.let { cur ->
                            cur.copy(devices = cur.devices.map { if (it.id == updated.id) updated else it })
                        }
                    }
                    "device:added" -> {
                        val added = json.decodeFromJsonElement(NetworkDevice.serializer(), root["payload"] ?: return)
                        _snapshot.value = _snapshot.value?.let { cur ->
                            if (cur.devices.any { it.id == added.id }) cur else cur.copy(devices = listOf(added) + cur.devices)
                        }
                    }
                    "alert:created" -> {
                        val alert = json.decodeFromJsonElement(SecurityAlert.serializer(), root["payload"] ?: return)
                        _snapshot.value = _snapshot.value?.let { cur ->
                            if (cur.alerts.any { it.id == alert.id }) cur else cur.copy(alerts = listOf(alert) + cur.alerts)
                        }
                        if (alert.severity == "critical") onCriticalAlertCallback?.invoke(alert)
                    }
                    "rule:updated" -> {
                        val updated = json.decodeFromJsonElement(AutoBlockRule.serializer(), root["payload"] ?: return)
                        _snapshot.value = _snapshot.value?.let { cur ->
                            cur.copy(rules = cur.rules.map { if (it.id == updated.id) updated else it })
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connected.value = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connected.value = false
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isDisposed) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            if (!isDisposed && !_connected.value) {
                establishWebSocket()
            }
        }
    }

    suspend fun sendDeviceAction(deviceId: String, action: String) = withContext(Dispatchers.IO) {
        val body = """{"action":"$action"}""".toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/api/devices/$deviceId/action")
            .header("X-LanGuard-Token", BuildConfig.SYNC_TOKEN)
            .post(body)
            .build()
        client.newCall(req).execute().close()
    }

    suspend fun toggleRule(ruleId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val body = """{"enabled":$enabled}""".toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/api/rules/$ruleId")
            .header("X-LanGuard-Token", BuildConfig.SYNC_TOKEN)
            .patch(body)
            .build()
        client.newCall(req).execute().close()
    }

    fun disconnect() {
        isDisposed = true
        reconnectJob?.cancel()
        socket?.close(1000, "App closed")
        socket = null
    }
}
