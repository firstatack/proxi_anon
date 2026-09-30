package com.proxianon.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.proxianon.app.ssh.TunnelStatus
import com.proxianon.app.vpn.TunnelVpnService
import com.proxianon.app.vpn.VpnUiState

class MainActivity : ComponentActivity() {

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN: permiso denegado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        vm = viewModel(),
                        onStartVpn = ::onStartVpnClick,
                        onStopVpn = ::onStopVpnClick,
                    )
                }
            }
        }
    }

    private fun onStartVpnClick(vm: MainViewModel) {
        if (vm.prepareVpnStart() == null) {
            Toast.makeText(this, "Completa host y usuario primero", Toast.LENGTH_SHORT).show()
            return
        }
        requestNotificationPermission()
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnConsentLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        ContextCompat.startForegroundService(this, Intent(this, TunnelVpnService::class.java))
    }

    private fun onStopVpnClick() {
        startService(
            Intent(this, TunnelVpnService::class.java)
                .setAction(TunnelVpnService.ACTION_STOP)
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

@Composable
fun HomeScreen(
    vm: MainViewModel = viewModel(),
    onStartVpn: (MainViewModel) -> Unit = {},
    onStopVpn: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val vpnState by vm.vpnState.collectAsState()
    val vpnLog by vm.vpnLog.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ProxiAnon", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Tunel SSH. Fase 3: VPN (todo el trafico) + SOCKS5 local.",
            style = MaterialTheme.typography.bodySmall
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Servidor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = state.host,
                    onValueChange = vm::onHost,
                    label = { Text("Host o IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = vm::onUsername,
                        label = { Text("Usuario") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.port,
                        onValueChange = vm::onPort,
                        label = { Text("Puerto") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp)
                    )
                }
                OutlinedTextField(
                    value = state.password,
                    onValueChange = vm::onPassword,
                    label = { Text("Contrasena") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Modo de tunel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Column(Modifier.selectableGroup()) {
                    TunnelMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = state.mode == mode,
                                    onClick = { vm.onMode(mode) },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = state.mode == mode, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(mode.label, fontWeight = FontWeight.Medium)
                                Text(mode.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (state.mode == TunnelMode.TCP_UDP) {
                    Text(
                        "TCP+UDP se activa en la Fase 4 (udpgw). Ahora mismo el tunel es TCP.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        val connected = state.status is TunnelStatus.Connected
        val connecting = state.status is TunnelStatus.Connecting

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { if (connected || connecting) vm.disconnect() else vm.connect() },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (connected || connecting) "Desconectar" else "Conectar")
            }
            OutlinedButton(
                onClick = vm::testExit,
                enabled = connected && !state.checking,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (state.checking) "Probando..." else "Probar salida")
            }
        }

        StatusLine(state.status)
        state.exitInfo?.let {
            Text("IP de salida: $it", style = MaterialTheme.typography.bodyMedium)
        }

        VpnSection(
            vpnState = vpnState,
            vpnLog = vpnLog,
            onStartVpn = { onStartVpn(vm) },
            onStopVpn = onStopVpn,
        )

        if (state.log.isNotEmpty()) {
            Text("Registro", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = state.log.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun StatusLine(status: TunnelStatus) {
    val (text, color) = when (status) {
        TunnelStatus.Disconnected -> "Desconectado" to MaterialTheme.colorScheme.outline
        TunnelStatus.Connecting -> "Conectando..." to MaterialTheme.colorScheme.primary
        is TunnelStatus.Connected -> "Conectado - SOCKS 127.0.0.1:${status.socksPort}" to Color(0xFF2E7D32)
        is TunnelStatus.Error -> "Error: ${status.message}" to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status is TunnelStatus.Connecting) {
            CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
        }
        Text(text, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun VpnSection(
    vpnState: VpnUiState,
    vpnLog: List<String>,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VPN - todo el trafico", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            val vpnActive = vpnState == VpnUiState.On
            val vpnStarting = vpnState == VpnUiState.Starting
            val statusText = when (vpnState) {
                VpnUiState.Off -> "Desconectado"
                VpnUiState.Starting -> "Conectando VPN..."
                VpnUiState.On -> "ACTIVO - todo el trafico por el tunel"
                is VpnUiState.Error -> "Error: ${vpnState.message}"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vpnStarting) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    statusText,
                    color = if (vpnActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (vpnActive || vpnStarting) {
                    OutlinedButton(onClick = onStopVpn, modifier = Modifier.weight(1f)) {
                        Text("Detener VPN")
                    }
                } else {
                    Button(onClick = onStartVpn, modifier = Modifier.weight(1f)) {
                        Text("Activar VPN")
                    }
                }
            }

            if (vpnLog.isNotEmpty()) {
                Text(
                    text = vpnLog.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    MaterialTheme {
        Surface { HomeScreenPreviewContent() }
    }
}

@Composable
private fun HomeScreenPreviewContent() {
    // Vista estatica para el preview (sin ViewModel).
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ProxiAnon", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Tunel SSH", style = MaterialTheme.typography.bodySmall)
    }
}
