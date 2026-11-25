package com.example.steamfetch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { AppRoot() }
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val ui by SteamManager.ui.collectAsState()
    when (val s = ui) {
        is UiState.LoggedIn -> LibraryScreen()
        else -> LoginScreen(s)
    }
}

// ------------------------------------------------------------------ login

@Composable
fun LoginScreen(state: UiState) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var preferCode by remember { mutableStateOf(settings.preferCode) }
    val saved = remember(state) { SteamManager.tokens.accounts() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SteamFetch", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sign in to download the game files for games you own, or can use through " +
                "Steam Family Sharing, straight into your phone's storage.",
            style = MaterialTheme.typography.bodyMedium,
        )

        when (state) {
            is UiState.Busy -> {
                CircularProgressIndicator()
                Text(state.message)
                TextButton(onClick = { SteamManager.cancelLogin() }) { Text("Cancel") }
            }

            is UiState.WaitingApproval -> {
                CircularProgressIndicator()
                Text("Approve the sign-in in your Steam mobile app (Steam Guard).")
                TextButton(onClick = { SteamManager.cancelLogin() }) { Text("Cancel") }
            }

            is UiState.Qr -> {
                val bmp = remember(state.url) { qrBitmap(state.url) }
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Steam sign-in QR code",
                    modifier = Modifier.size(260.dp),
                )
                Text("Scan this with the QR scanner in the Steam mobile app. It refreshes automatically.")
                TextButton(onClick = { SteamManager.cancelLogin() }) { Text("Cancel") }
            }

            is UiState.NeedCode -> {
                CodeDialog(state)
            }

            else -> {
                if (state is UiState.Failed) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }

                if (saved.isNotEmpty()) {
                    Text("Saved accounts", style = MaterialTheme.typography.titleSmall)
                    for (name in saved) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { SteamManager.loginSaved(name) },
                                modifier = Modifier.weight(1f),
                            ) { Text("Continue as $name") }
                            TextButton(onClick = {
                                SteamManager.forgetAccount(name)
                                Toast.makeText(ctx, "Forgot $name", Toast.LENGTH_SHORT).show()
                            }) { Text("Forget") }
                        }
                    }
                    Text("Add another account", style = MaterialTheme.typography.titleSmall)
                }

                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Steam username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = preferCode, onCheckedChange = { preferCode = it })
                    Text(
                        "  Type a Steam Guard code instead of approving in the phone app",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = {
                        settings.preferCode = preferCode
                        SteamManager.loginWithPassword(user.trim(), pass, preferCode)
                        pass = ""
                    },
                    enabled = user.isNotBlank() && pass.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign in") }
                OutlinedButton(
                    onClick = { SteamManager.loginWithQr() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign in with QR code (Steam mobile app)") }
                Text(
                    "Your password is sent only to Steam and is never stored. Only a Steam session token is kept, encrypted on this device.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun CodeDialog(state: UiState.NeedCode) {
    var code by remember { mutableStateOf("") }
    val what = if (state.kind == CodeKind.EMAIL) {
        "Enter the Steam Guard code emailed to ${state.email ?: "you"}."
    } else {
        "Enter the Steam Guard code from your Steam mobile app."
    }
    AlertDialog(
        onDismissRequest = { SteamManager.cancelLogin() },
        title = { Text("Steam Guard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.retry) Text("That code was wrong. Try again.", color = MaterialTheme.colorScheme.error)
                Text(what)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { SteamManager.submitCode(code) }, enabled = code.isNotBlank()) { Text("Submit") }
        },
        dismissButton = { TextButton(onClick = { SteamManager.cancelLogin() }) { Text("Cancel") } },
    )
}

fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) AColor.BLACK else AColor.WHITE)
        }
    }
    return bmp
}

// ------------------------------------------------------------------ library

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    val games by SteamManager.games.collectAsState()
    val loading by SteamManager.libraryLoading.collectAsState()
    val account by SteamManager.account.collectAsState()
    val dl by Downloads.state.collectAsState()

    var query by remember { mutableStateOf("") }
    var familyOnly by remember { mutableStateOf(false) }
    var dir by remember { mutableStateOf(settings.downloadDir) }
    var os by remember { mutableStateOf(settings.targetOs) }
    var storageOk by remember { mutableStateOf(Storage.hasAccess(ctx)) }

    // Re-check file access when returning from the system settings screen.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storageOk = Storage.hasAccess(ctx)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        storageOk = Storage.hasAccess(ctx)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val busy = dl is DlState.Active
    val shown = remember(games, query, familyOnly) {
        games.filter { (!familyOnly || it.familyShared) && it.name.contains(query, ignoreCase = true) }
    }

    fun startDownload(g: Game) {
        settings.downloadDir = dir.trim()
        settings.targetOs = os
        if (!storageOk) {
            Toast.makeText(ctx, "Grant file access first", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val i = Intent(ctx, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
            .putExtra(DownloadService.EXTRA_APP_ID, g.appId)
            .putExtra(DownloadService.EXTRA_NAME, g.name)
            .putExtra(DownloadService.EXTRA_OS, os)
            .putExtra(DownloadService.EXTRA_DIR, dir.trim())
        ContextCompat.startForegroundService(ctx, i)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account ?: "Library") },
                actions = { TextButton(onClick = { SteamManager.logout(false) }) { Text("Switch account") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = dir,
                    onValueChange = { dir = it },
                    label = { Text("Download folder") },
                    supportingText = { Text("Each game gets its own subfolder. SD card paths look like /storage/XXXX-XXXX/Games") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!storageOk) {
                    Button(onClick = { Storage.request(ctx, storageLauncher) }) { Text("Grant file access") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Platform:", style = MaterialTheme.typography.labelLarge)
                    for (o in listOf("windows", "linux", "macos")) {
                        FilterChip(selected = os == o, onClick = { os = o }, label = { Text(o) })
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search your games") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = familyOnly, onCheckedChange = { familyOnly = it })
                    Text("  Only Family Sharing games", style = MaterialTheme.typography.bodyMedium)
                }
                DownloadCard(dl)
            }

            if (games.isEmpty()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loading) {
                        CircularProgressIndicator()
                        Text("Loading your library…")
                    } else {
                        Text(
                            "No games found. If you expected Family Sharing games, make sure both accounts " +
                                "are in the same Steam Family group and that sharing is enabled in Steam.",
                        )
                    }
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.appId }) { g ->
                    GameRow(g, enabled = !busy) { startDownload(g) }
                }
            }
        }
    }
}

@Composable
fun GameRow(g: Game, enabled: Boolean, onDownload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(g.name, style = MaterialTheme.typography.bodyLarge)
            if (g.familyShared) {
                Text(
                    "Family Sharing",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Button(onClick = onDownload, enabled = enabled) { Text("Download") }
    }
}

@Composable
fun DownloadCard(dl: DlState) {
    val ctx = LocalContext.current
    when (dl) {
        is DlState.Active -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(dl.name, style = MaterialTheme.typography.titleSmall)
                Text(dl.status, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                if (dl.percent > 0f) {
                    LinearProgressIndicator(progress = { (dl.percent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = {
                    ctx.startService(
                        Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL),
                    )
                }) { Text("Cancel") }
            }
        }

        is DlState.Done -> Text("Finished ${dl.name}. Files are in ${dl.path}")
        is DlState.Failed -> Text("${dl.name} failed: ${dl.message}", color = MaterialTheme.colorScheme.error)
        is DlState.Idle -> {}
    }
}
