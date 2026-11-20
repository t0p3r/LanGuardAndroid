package com.languard.monitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.languard.monitor.data.AutoBlockRule
import com.languard.monitor.data.NetworkDevice
import com.languard.monitor.data.SecurityAlert
import com.languard.monitor.data.SyncRepository
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val repository = SyncRepository()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createAlertChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        repository.connect { alert -> showSecurityNotification(alert) }

        setContent {
            MaterialTheme {
                LanGuardMobileScreen(repository = repository)
            }
        }
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "languard_security",
                "Unknown Connection Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showSecurityNotification(alert: SecurityAlert) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(this, "languard_security")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(alert.title)
            .setContentText("${alert.mac} (${alert.ip}) — ${alert.actionTaken ?: "Action required"}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(alert.id.hashCode(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.disconnect()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanGuardMobileScreen(repository: SyncRepository) {
    val snapshot by repository.snapshot.collectAsStateWithLifecycle()
    val isConnected by repository.connected.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_languard_logo),
                            contentDescription = "LanGuard Logo",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("LanGuard Mobile", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (isConnected) "Synced live · ${snapshot?.subnetCidr ?: "192.168.1.0/24"}" else "Reconnecting...",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isConnected) Color(0xFF16A34A) else Color(0xFFD97706)
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("LAN", fontFamily = FontFamily.Monospace) },
                    label = { Text("Devices") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("SEC", fontFamily = FontFamily.Monospace) },
                    label = { Text("Alerts") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("AUTO", fontFamily = FontFamily.Monospace) },
                    label = { Text("Rules") }
                )
            }
        }
    ) { padding ->
        val data = snapshot
        if (data == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val totalDown = data.devices.sumOf { it.downloadMbps }
        val totalUp = data.devices.sumOf { it.uploadMbps }

        val uniqueDevices = remember(data.devices) { data.devices.distinctBy { "${it.id}_${it.ip}" } }
        val uniqueAlerts = remember(data.alerts) { data.alerts.distinctBy { "${it.id}_${it.timestamp}" } }
        val uniqueRules = remember(data.rules) { data.rules.distinctBy { "${it.id}_${it.name}" } }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Live Throughput", style = MaterialTheme.typography.labelMedium, color = Color(0xFF64748B))
                            Text(
                                String.format("%.1f Mbps down · %.1f up", totalDown, totalUp),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Endpoints", style = MaterialTheme.typography.labelMedium, color = Color(0xFF64748B))
                            Text(
                                "${uniqueDevices.count { it.status != "blocked" }} active / ${uniqueDevices.size}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            when (selectedTab) {
                0 -> {
                    items(uniqueDevices, key = { "${it.id}_${it.ip}" }) { device ->
                        DeviceRemoteRow(
                            device = device,
                            onToggleBlock = {
                                scope.launch {
                                    val nextAction = if (device.status == "blocked") "unblock" else "block"
                                    repository.sendDeviceAction(device.id, nextAction)
                                }
                            }
                        )
                    }
                }
                1 -> {
                    items(uniqueAlerts, key = { "${it.id}_${it.timestamp}" }) { alert ->
                        AlertRemoteRow(alert = alert)
                    }
                }
                2 -> {
                    items(uniqueRules, key = { "${it.id}_${it.name}" }) { rule ->
                        RuleRemoteRow(
                            rule = rule,
                            onToggle = { enabled ->
                                scope.launch { repository.toggleRule(rule.id, enabled) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRemoteRow(device: NetworkDevice, onToggleBlock: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "${device.ip} · ${device.mac}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF475569)
                )
                Text(
                    String.format("Down %.1fM · Up %.1fM · %s", device.downloadMbps, device.uploadMbps, device.status),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onToggleBlock,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (device.status == "blocked") Color(0xFF0F172A) else Color(0xFFDC2626)
                )
            ) {
                Text(if (device.status == "blocked") "Unblock" else "Block")
            }
        }
    }
}

@Composable
private fun AlertRemoteRow(alert: SecurityAlert) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                "${alert.severity.uppercase()} · ${alert.ip}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = if (alert.severity == "critical") Color(0xFFDC2626) else Color(0xFFD97706)
            )
            Spacer(Modifier.height(4.dp))
            Text(alert.title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(alert.description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569))
        }
    }
}

@Composable
private fun RuleRemoteRow(rule: AutoBlockRule, onToggle: (Boolean) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "Action: ${rule.action} · Triggered ${rule.triggeredCount}x",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF475569)
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
    }
}
