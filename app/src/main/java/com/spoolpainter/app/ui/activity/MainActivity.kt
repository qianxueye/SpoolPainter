package com.spoolpainter.app.ui.activity

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Print
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import com.google.gson.Gson
import com.spoolpainter.app.data.remote.inventory.InventoryRepository
import com.spoolpainter.app.domain.models.SpoolmanSpool
import com.spoolpainter.app.domain.primitives.NfcIntent
import com.spoolpainter.app.domain.primitives.NfcResult
import com.spoolpainter.app.ui.screens.inventory.InventoryScreen
import com.spoolpainter.app.ui.screens.printing.PrintingScreen
import com.spoolpainter.app.hardware.printer.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.spoolpainter.app.data.local.SettingsRepository
import com.spoolpainter.app.data.local.ThemeOverride
import com.spoolpainter.app.data.remote.spoolman.SpoolmanRepository
import com.spoolpainter.app.hardware.nfc.NfcRepository
import com.spoolpainter.app.ui.components.sheets.WHATS_NEW_CONTENT_VERSION
import com.spoolpainter.app.ui.components.sheets.WhatsNewSheet
import com.spoolpainter.app.ui.components.sheets.whatsNewV2Highlights
import com.spoolpainter.app.ui.screens.main.MainScreen
import com.spoolpainter.app.ui.screens.settings.SettingsScreen
import com.spoolpainter.app.ui.theme.SpoolPainterTheme
import com.spoolpainter.app.ui.whatsnew.WhatsNewController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var nfcRepository: NfcRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var spoolmanRepository: SpoolmanRepository
    @Inject lateinit var inventoryRepository: InventoryRepository
    @Inject lateinit var whatsNewController: WhatsNewController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        evaluateWhatsNew()
        requestPosPermissions()
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle()
            val darkTheme = settings.themeOverride == ThemeOverride.Dark
            SpoolPainterTheme(darkTheme = darkTheme, dynamicColor = true) {
                var page by rememberSaveable { mutableStateOf("inventory") }
                var previousPage by rememberSaveable { mutableStateOf("inventory") }
                var printRequest by remember { mutableStateOf<PrintRequest?>(null) }
                var scannedUid by rememberSaveable { mutableStateOf<String?>(null) }
                var scannedUidEvent by rememberSaveable { mutableStateOf(0L) }
                val nfcState by nfcRepository.state.collectAsStateWithLifecycle()
                LaunchedEffect(nfcState) {
                    if (page == "inventory" && nfcState is NfcResult.Success) {
                        scannedUid = (nfcState as NfcResult.Success).uid.hex
                        scannedUidEvent += 1
                    }
                }
                fun openSettings() { previousPage = page; page = "settings" }
                fun navigate(target: String) {
                    lifecycleScope.launch {
                        nfcRepository.disarm()
                        page = target
                    }
                }
                BackHandler(page == "settings" || page == "printing") {
                    navigate(if (page == "settings") previousPage else "inventory")
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Box(Modifier.weight(1f)) {
                        when (page) {
                            "settings" -> SettingsScreen(onBack = { navigate(previousPage) })
                            "tags" -> MainScreen(onNavigateToSettings = { openSettings() })
                            "printing" -> PrintingScreen(
                                request = printRequest,
                                onBack = { navigate("inventory") },
                                onSelectSpool = { navigate("inventory") },
                            )
                            else -> Column(Modifier.fillMaxSize()) {
                                when (val current = nfcState) {
                                    NfcResult.Reading -> RowScanStatus("正在扫描耗材标签…", onStop = { lifecycleScope.launch { nfcRepository.disarm() } })
                                    is NfcResult.Error -> Text("读卡失败：${current.reason}", Modifier.padding(12.dp))
                                    else -> Unit
                                }
                                InventoryScreen(
                                    onSettings = { openSettings() },
                                    scannedUid = scannedUid,
                                    scannedUidEvent = scannedUidEvent,
                                    onScanUid = { lifecycleScope.launch { nfcRepository.arm(NfcIntent.Read) } },
                                    onPrint = { spool, sourceUrl ->
                                        val id = spool.id
                                        if (id != null && id > 0) {
                                            printRequest = PrintRequest(
                                                SpoolLabel(id, spool.filament.vendor?.name.orEmpty(), spool.filament.material.orEmpty(), spool.filament.color_hex.orEmpty(), spool.remaining_weight?.toDouble(), spool.location.orEmpty(), name = spool.filament.name.orEmpty()),
                                                sourceUrl,
                                            )
                                            navigate("printing")
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (page != "settings") {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            listOf("inventory" to "库存管理", "tags" to "标签读写", "printing" to "小票标签").forEach { (target, label) ->
                                NavigationBarItem(
                                    selected = page == target,
                                    enabled = nfcState !is NfcResult.Writing && nfcState !is NfcResult.Verifying,
                                    onClick = { navigate(target) },
                                    icon = { Icon(when (target) { "inventory" -> Icons.Outlined.Inventory2; "tags" -> Icons.Outlined.Nfc; else -> Icons.Outlined.Print }, contentDescription = label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
                }
                val showWhatsNew by whatsNewController.visible.collectAsStateWithLifecycle()
                WhatsNewSheet(
                    visible = showWhatsNew,
                    highlights = whatsNewV2Highlights,
                    onDismiss = { whatsNewController.onDismiss() },
                )
            }
        }
        intent?.let { tryDispatchNfcIntent(it) }
    }

    private fun requestPosPermissions() {
        val required = arrayOf("com.pos.permission.CARD_READER_PICC", "com.pos.permission.PRINTER")
            .filter { permission -> checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED }
        if (required.isNotEmpty() && runCatching { Class.forName("com.pos.sdk.cardreader.POICardManager") }.isSuccess) {
            ActivityCompat.requestPermissions(this, required.toTypedArray(), 701)
        }
    }

    /**
     * Decide the one-time "What's new" showcase. A fresh install (first-install
     * time == last-update time) is suppressed; an in-place update is shown.
     * Evaluated once per cold start, before setContent observes the flow.
     */
    private fun evaluateWhatsNew() {
        val isFreshInstall = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            info.firstInstallTime == info.lastUpdateTime
        }.getOrDefault(true)
        // Deliberately NOT BuildConfig.VERSION_CODE: the sheet should open when
        // the highlights change, not when the app version changes.
        whatsNewController.onColdStart(
            contentVersion = WHATS_NEW_CONTENT_VERSION,
            isFreshInstall = isFreshInstall,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tryDispatchNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcRepository.attach(this)
        // F-6 (v2.0.3): refresh Spoolman cache when the user returns to the
        // app — catches the common case of editing in the Spoolman web UI in
        // a browser then switching back to SpoolPainter. Throttled internally
        // so a tight resume/pause cycle doesn't spam the server.
        lifecycleScope.launch {
            runCatching { spoolmanRepository.refreshIfStale() }
        }
    }

    override fun onPause() {
        super.onPause()
        nfcRepository.detach()
    }

    private fun tryDispatchNfcIntent(intent: Intent) {
        when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED -> {
                val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                }
                tag?.let { nfcRepository.onTagDiscovered(it) }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RowScanStatus(text: String, onStop: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 12.dp)) {
        Text(text, Modifier.weight(1f))
        TextButton(onClick = onStop) { Text("停止扫描") }
    }
}
