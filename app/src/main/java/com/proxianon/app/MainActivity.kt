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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.proxianon.app.vpn.SshProfile
import com.proxianon.app.vpn.TrafficMeter
import com.proxianon.app.vpn.TrafficStats
import com.proxianon.app.vpn.TunnelVpnService
import com.proxianon.app.vpn.VpnUiState
import java.util.Locale
import java.util.UUID

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
            val dark = isSystemInDarkTheme()
            val colors = if (dark) ProxiAnonDarkColors else ProxiAnonLightColors
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showAccounts by rememberSaveable { mutableStateOf(false) }
                    val vm: MainViewModel = viewModel()
                    if (showAccounts) {
                        ProfilesScreen(
                            vm = vm,
                            onBack = { showAccounts = false },
                        )
                    } else {
                        HomeScreen(
                            vm = vm,
                            onStartVpn = ::onStartVpnClick,
                            onStopVpn = ::onStopVpnClick,
                            onOpenAccounts = { showAccounts = true },
                        )
                    }
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

private val ProxiAnonDarkColors = darkColorScheme(
    primary = Color(0xFF59D6C6),
    onPrimary = Color(0xFF00201D),
    secondary = Color(0xFF8BE39E),
    tertiary = Color(0xFFF2C14E),
    background = Color(0xFF0E1218),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161C24),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF222B37),
    onSurfaceVariant = Color(0xFFA9B4C0),
    error = Color(0xFFFF6B6B),
)

private val ProxiAnonLightColors = lightColorScheme(
    primary = Color(0xFF00796B),
    secondary = Color(0xFF2E7D32),
    tertiary = Color(0xFF8A6D00),
    surfaceVariant = Color(0xFFE3E9F0),
    onSurfaceVariant = Color(0xFF43505E),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel = viewModel(),
    onStartVpn: (MainViewModel) -> Unit = {},
    onStopVpn: () -> Unit = {},
    onOpenAccounts: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val vpnState by vm.vpnState.collectAsState()
    val vpnLog by vm.vpnLog.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val activeId by vm.activeProfileId.collectAsState()
    val activeProfileName = profiles.firstOrNull { it.id == activeId }?.name ?: "Sin perfil"
    var profileMenuExpanded by remember { mutableStateOf(false) }
    val stats by TrafficMeter.flow.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ProxiAnon", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Tu tunel SSH privado: todo el trafico de este telefono sale por tu VPS.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ExposedDropdownMenuBox(
                        expanded = profileMenuExpanded,
                        onExpandedChange = { profileMenuExpanded = it },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = activeProfileName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Perfil") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileMenuExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = profileMenuExpanded,
                            onDismissRequest = { profileMenuExpanded = false }
                        ) {
                            profiles.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name) },
                                    onClick = {
                                        profileMenuExpanded = false
                                        vm.activateProfile(p.id)
                                    }
                                )
                            }
                        }
                    }
                    OutlinedButton(onClick = onOpenAccounts) {
                        Text("Cuentas")
                    }
                }

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
                        "Solo TCP por ahora; el soporte UDP (udpgw) llegara mas adelante.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        val connected = state.status is TunnelStatus.Connected
        val connecting = state.status is TunnelStatus.Connecting
        val connectLabel = when {
            connecting -> "Conectando..."
            connected -> "Desconectar"
            else -> "Conectar"
        }
        val connectColor = when {
            state.status is TunnelStatus.Error -> MaterialTheme.colorScheme.error
            connected -> Color(0xFF2E7D32)
            connecting -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
        Button(
            onClick = { if (connected || connecting) vm.disconnect() else vm.connect() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = connectColor)
        ) {
            if (connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.width(18.dp).padding(end = 8.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Text(connectLabel, style = MaterialTheme.typography.titleMedium)
        }
        StatusLine(state.status)
        OutlinedButton(
            onClick = vm::testExit,
            enabled = connected && !state.checking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.checking) "Probando salida..." else "Probar salida (IP publica)")
        }

        if (vpnState == VpnUiState.On) {
            StatsLine(stats)
        }
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
            val vpnReconnecting = vpnState is VpnUiState.Reconnecting
            val statusText = when (vpnState) {
                VpnUiState.Off -> "Desconectado"
                VpnUiState.Starting -> "Conectando VPN..."
                VpnUiState.On -> "ACTIVO - todo el trafico por el tunel"
                is VpnUiState.Reconnecting -> "Reconectando (intento ${vpnState.attempt})..."
                is VpnUiState.Error -> "Error: ${vpnState.message}"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vpnStarting || vpnReconnecting) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    statusText,
                    color = if (vpnActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (vpnActive || vpnStarting || vpnReconnecting) {
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

@Composable
private fun StatsLine(stats: TrafficStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Stat("↓ ${fmtBytes(stats.rxBytes)}", Color(0xFF2E7D32))
        Stat("↑ ${fmtBytes(stats.txBytes)}", MaterialTheme.colorScheme.primary)
        Stat("Σ ${fmtBytes(stats.totalBytes)}", MaterialTheme.colorScheme.tertiary)
        Stat("⏱ ${fmtUptime(stats.uptimeSeconds)}", MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stat(label: String, color: Color) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
}

private fun fmtBytes(raw: Long): String {
    val b = if (raw < 0) 0 else raw
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB")
    var v = b.toDouble()
    var u = -1
    while (v >= 1024 && u < units.lastIndex) {
        v /= 1024
        u++
    }
    return String.format(Locale.US, "%.1f %s", v, if (u < 0) "B" else units[u])
}

private fun fmtUptime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

@Composable
private fun ProfilesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val profiles by vm.profiles.collectAsState()
    val activeId by vm.activeProfileId.collectAsState()
    var editing by remember { mutableStateOf<SshProfile?>(null) }
    var deleting by remember { mutableStateOf<SshProfile?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("←") }
            Text("Cuentas SSH", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(onClick = { editing = SshProfile(name = "", host = "", username = "") }) { Text("Añadir") }
        }

        if (profiles.isEmpty()) {
            Text(
                "Sin cuentas guardadas. Pulsa Anadir para crear una, o usa el formulario de la pantalla principal.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        profiles.forEach { p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (p.id == activeId) {
                            Spacer(Modifier.width(8.dp))
                            Text("ACTIVO", color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text("${p.username}@${p.host}:${p.port}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.activateProfile(p.id); onBack() }, modifier = Modifier.weight(1f)) {
                            Text("Usar")
                        }
                        OutlinedButton(onClick = { editing = p.copy() }) {
                            Text("Editar")
                        }
                        OutlinedButton(onClick = {
                            vm.saveProfile(p.copy(id = UUID.randomUUID().toString(), name = "${p.name} (copia)"))
                        }) {
                            Text("Dup")
                        }
                        OutlinedButton(onClick = { deleting = p }) {
                            Text("Borrar")
                        }
                    }
                }
            }
        }
    }

    editing?.let { p ->
        ProfileEditDialog(
            initial = p,
            onSave = { saved ->
                vm.saveProfile(saved)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Borrar cuenta") },
            text = { Text("¿Borrar '${p.name}'?") },
            confirmButton = {
                TextButton(onClick = { vm.deleteProfile(p.id); deleting = null }) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun ProfileEditDialog(
    initial: SshProfile,
    onSave: (SshProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.username) }
    var pass by remember { mutableStateOf(initial.password) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "Nueva cuenta" else "Editar cuenta") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host o IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = { Text("Puerto") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Usuario") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Contrasena") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && user.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.ifBlank { host },
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 22,
                            username = user.trim(),
                            password = pass,
                        )
                    )
                }
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
