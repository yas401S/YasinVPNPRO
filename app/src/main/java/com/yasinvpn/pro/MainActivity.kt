package com.yasinvpn.pro

import android.net.VpnService
import android.os.Bundle
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

private val Navy = Color(0xFF071426)
private val Gold = Color(0xFFE0B85C)

class MainActivity : ComponentActivity() {
    private lateinit var backend: Backend
    private val tunnel = AppTunnel()
    private var config: Config? = null
    private val connected = mutableStateOf(false)
    private val hasConfig = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backend = GoBackend(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Navy, surface = Color(0xFF10243D))) {
                Surface(Modifier.fillMaxSize(), color = Navy) {
                    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("YASIN VPN PRO", style = MaterialTheme.typography.headlineMedium, color = Gold)
                        Text(if (connected.value) "متصل" else "آماده اتصال", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Text(if (hasConfig.value) "فایل WireGuard وارد شده" else "ابتدا فایل کانفیگ WireGuard را اضافه کن", color = Color.LightGray)
                        Button(onClick = { picker.launch(arrayOf("text/*", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                            Text("افزودن کانفیگ")
                        }
                        Button(onClick = { if (connected.value) disconnect() else connect() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                            Text(if (connected.value) "قطع اتصال" else "اتصال امن")
                        }
                        Text("بدون دسترسی به مخاطبین، پیامک، گالری یا موقعیت مکانی", color = Color.LightGray)
                    }
                }
            }
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            contentResolver.openInputStream(uri)?.use { config = Config.parse(it); hasConfig.value = true }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "کانفیگ معتبر نیست: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun connect() {
        val cfg = config ?: run {
            android.widget.Toast.makeText(this, "ابتدا کانفیگ WireGuard را اضافه کن", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val permission = VpnService.prepare(this)
        if (permission != null) permissionLauncher.launch(permission) else connectWith(cfg)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) config?.let { cfg -> connectWith(cfg) }
    }

    private fun connectWith(cfg: Config) {
        Thread {
            try {
                backend.setState(tunnel, Tunnel.State.UP, cfg)
                runOnUiThread { connected.value = true }
            } catch (e: Exception) {
                runOnUiThread { android.widget.Toast.makeText(this, "اتصال ناموفق: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun disconnect() {
        Thread {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                runOnUiThread { connected.value = false }
            } catch (e: Exception) {
                runOnUiThread { android.widget.Toast.makeText(this, "قطع اتصال ناموفق: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}

private class AppTunnel : Tunnel {
    override fun getName() = "YasinVPNPRO"
    override fun onStateChange(newState: Tunnel.State) {}
}
