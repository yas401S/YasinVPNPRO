package com.yasinvpn.pro

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.util.concurrent.Executors

private val Navy = Color(0xFF071426)
private val Gold = Color(0xFFE0B85C)

class MainActivity : ComponentActivity() {
    private lateinit var backend: Backend
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connected = mutableStateOf(false)
    private val hasConfig = mutableStateOf(false)
    private val busy = mutableStateOf(false)
    private val status = mutableStateOf("آماده اتصال")
    private val traffic = mutableStateOf("هنوز آماری دریافت نشده")
    private var config: Config? = null
    private val preferences by lazy { getSharedPreferences("yasin_vpn", MODE_PRIVATE) }

    private val statsMonitor: Runnable = object : Runnable {
        override fun run() {
            if (!connected.value || isFinishing || isDestroyed) return
            worker.execute {
                try {
                    val stats = backend.getStatistics(tunnel)
                    val handshakes = stats.peers().mapNotNull { key ->
                        stats.peer(key)?.latestHandshakeEpochMillis()
                    }.filter { it > 0L }
                    val rx = stats.totalRx()
                    val tx = stats.totalTx()
                    runOnUiThread {
                        if (!connected.value) return@runOnUiThread
                        traffic.value = "دریافت: ${formatBytes(rx)}  |  ارسال: ${formatBytes(tx)}"
                        status.value = when {
                            handshakes.isEmpty() ->
                                "تونل روشن است، اما هنوز پاسخی از سرور نگرفته‌ایم"
                            rx == 0L ->
                                "پاسخ سرور دریافت شده؛ هنوز داده‌ای وارد تونل نشده"
                            else ->
                                "ارتباط WireGuard با سرور برقرار است"
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (connected.value) status.value = "خطا در بررسی ارتباط سرور"
                    }
                }
            }
            mainHandler.postDelayed(this, 3000L)
        }
    }

    private val tunnel: Tunnel = AppTunnel { state ->
        runOnUiThread {
            connected.value = state == Tunnel.State.UP
            busy.value = false
            if (connected.value) {
                status.value = "تونل فعال شد؛ در حال بررسی پاسخ سرور..."
                traffic.value = "در حال خواندن آمار ارتباط..."
                mainHandler.removeCallbacks(statsMonitor)
                mainHandler.post(statsMonitor)
            } else {
                mainHandler.removeCallbacks(statsMonitor)
                status.value = "اتصال برقرار نیست"
                traffic.value = "تونل قطع است"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(this)
        restoreConfig()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Navy, surface = Color(0xFF10243D))) {
                Surface(Modifier.fillMaxSize(), color = Navy) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Spacer(Modifier.height(24.dp))
                        Text("YASIN VPN PRO", style = MaterialTheme.typography.headlineMedium, color = Gold)
                        Text(status.value, style = MaterialTheme.typography.titleLarge, color = if (connected.value) Gold else Color.White)
                        Text(traffic.value, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (hasConfig.value) "کانفیگ WireGuard ذخیره شده" else "برای شروع، فایل کانفیگ WireGuard را وارد کن",
                            color = Color.LightGray
                        )
                        Button(
                            onClick = { picker.launch(arrayOf("text/*", "application/octet-stream")) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy.value
                        ) { Text("افزودن / تغییر کانفیگ") }
                        Button(
                            onClick = { if (connected.value) disconnect() else connect() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            enabled = !busy.value && (connected.value || hasConfig.value)
                        ) {
                            Text(
                                when {
                                    busy.value -> "لطفاً صبر کن..."
                                    connected.value -> "قطع اتصال"
                                    else -> "اتصال امن"
                                }
                            )
                        }
                        if (busy.value) CircularProgressIndicator(color = Gold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "برنامه به مخاطبین، پیامک، عکس‌ها، فایل‌های شخصی یا موقعیت مکانی دسترسی نمی‌خواهد.",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("علامت VPN به‌تنهایی به معنی دسترسی اینترنت نیست؛ وضعیت پاسخ سرور جداگانه بررسی می‌شود.",
                            color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val parsed = contentResolver.openInputStream(uri)?.use { Config.parse(it) }
                ?: throw IllegalArgumentException("خواندن فایل ممکن نشد")
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                preferences.edit().putString("config_uri", uri.toString()).apply()
            } catch (_: SecurityException) {
                // Keep the parsed config in memory if persistent permission is unavailable.
            }
            config = parsed
            hasConfig.value = true
            status.value = "کانفیگ آماده استفاده است"
            toast("کانفیگ با موفقیت وارد شد")
        } catch (e: Exception) {
            config = null
            hasConfig.value = false
            preferences.edit().remove("config_uri").apply()
            toast("فایل کانفیگ معتبر نیست یا قابل خواندن نیست")
        }
    }

    private fun restoreConfig() {
        val saved = preferences.getString("config_uri", null) ?: return
        try {
            val uri = android.net.Uri.parse(saved)
            config = contentResolver.openInputStream(uri)?.use { Config.parse(it) }
            hasConfig.value = config != null
            if (hasConfig.value) status.value = "کانفیگ ذخیره‌شده بازیابی شد"
        } catch (_: Exception) {
            config = null
            hasConfig.value = false
            preferences.edit().remove("config_uri").apply()
            status.value = "لطفاً کانفیگ را دوباره وارد کن"
        }
    }

    private fun connect() {
        val cfg = config ?: run {
            toast("ابتدا فایل کانفیگ WireGuard را اضافه کن")
            return
        }
        busy.value = true
        status.value = "در حال آماده‌سازی اتصال..."
        val permission = VpnService.prepare(this)
        if (permission != null) permissionLauncher.launch(permission) else connectWith(cfg)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            config?.let { connectWith(it) } ?: run {
                busy.value = false
                status.value = "کانفیگ در دسترس نیست"
            }
        } else {
            busy.value = false
            status.value = "مجوز VPN داده نشد"
        }
    }

    private fun connectWith(cfg: Config) {
        worker.execute {
            try {
                backend.setState(tunnel, Tunnel.State.UP, cfg)
                runOnUiThread {
                    if (!connected.value) {
                        busy.value = false
                        status.value = "درخواست اتصال ارسال شد؛ در انتظار وضعیت تونل..."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy.value = false
                    connected.value = false
                    status.value = "اتصال ناموفق بود"
                    toast("اتصال برقرار نشد: ${e.localizedMessage ?: "خطای نامشخص"}")
                }
            }
        }
    }

    private fun disconnect() {
        busy.value = true
        status.value = "در حال قطع اتصال..."
        mainHandler.removeCallbacks(statsMonitor)
        worker.execute {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                runOnUiThread {
                    connected.value = false
                    busy.value = false
                    status.value = "اتصال قطع شد"
                    traffic.value = "تونل قطع است"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy.value = false
                    status.value = "قطع اتصال ناموفق بود"
                    toast("قطع اتصال ناموفق بود: ${e.localizedMessage ?: "خطای نامشخص"}")
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        return String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(statsMonitor)
        worker.shutdown()
        super.onDestroy()
    }
}

private class AppTunnel(
    private val stateListener: (Tunnel.State) -> Unit
) : Tunnel {
    override fun getName() = "YasinVPNPRO"
    override fun onStateChange(newState: Tunnel.State) = stateListener(newState)
}
