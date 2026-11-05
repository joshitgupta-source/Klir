package com.joshit.klir

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.joshit.klir.ui.theme.KlirTheme
import java.io.File
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val viewModel: KlirViewModel by viewModels()
    private var hasPermissionState by mutableStateOf(false)
    private var hasOverlayPermissionState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Track 2: Schedule daily background .klir_trash purge & storage insights
        KlirMaintenanceWorker.schedulePeriodic(this)

        // Request notification permission on Android 13+ for background insights
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }

        hasPermissionState = viewModel.hasStoragePermission()
        hasOverlayPermissionState = canDrawOverlay()

        setContent {
            KlirTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.uiState.collectAsState()

                    KlirCleanerScreen(
                        state = state,
                        hasPermission = hasPermissionState,
                        hasOverlayPermission = hasOverlayPermissionState,
                        canNavigateUpDiskMap = viewModel.canNavigateUpDiskMap(),
                        onSelectVolume = { viewModel.selectVolume(it) },
                        onRefreshVolumes = { viewModel.refreshVolumesAndTrash() },
                        onToggleType = { viewModel.toggleJunkType(it) },
                        onScanClick = {
                            if (!viewModel.hasStoragePermission()) {
                                requestStoragePermission()
                            } else {
                                viewModel.scan()
                            }
                        },
                        onCleanClick = {
                            handleStartCleaning(state)
                        },
                        onExcludePath = { viewModel.excludePath(it) },
                        onRemoveWhitelistedPath = { viewModel.removeWhitelistedPath(it) },
                        onRestoreTrashItem = { viewModel.restoreFromTrash(it) },
                        onEmptyTrashClick = { viewModel.emptyTrashNow() },
                        onLoadDiskMap = { dir ->
                            if (!viewModel.hasStoragePermission()) {
                                requestStoragePermission()
                            } else if (dir != null) {
                                viewModel.loadDiskMap(dir)
                            } else {
                                viewModel.loadDiskMap()
                            }
                        },
                        onNavigateUpDiskMap = { viewModel.navigateUpDiskMap() },
                        onTrashDiskNode = { viewModel.trashDiskNode(it) },
                        onRequestPermissionClick = { requestStoragePermission() },
                        onRequestOverlayClick = { requestOverlayPermission() },
                        onOpenAccessibilityClick = { openAccessibilitySettings() },
                        onAddQuickTileClick = { requestAddQuickTile() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasPermissionState = viewModel.hasStoragePermission()
        hasOverlayPermissionState = canDrawOverlay()
        viewModel.refreshVolumesAndTrash()
    }

    private fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun handleStartCleaning(state: KlirUiState) {
        if (!viewModel.hasStoragePermission()) {
            requestStoragePermission()
            return
        }

        val wantCacheClear = state.selectedTypes.contains(JunkType.APP_CACHE)
        val wantForceStop = state.selectedTypes.contains(JunkType.APP_HIBERNATION)

        viewModel.cleanSelectedFiles {
            if (wantCacheClear || wantForceStop) {
                triggerAccessibilityAutomation(
                    clearCache = wantCacheClear,
                    forceStop = wantForceStop
                )
            }
        }
    }

    private fun triggerAccessibilityAutomation(clearCache: Boolean, forceStop: Boolean) {
        if (!isAccessibilityServiceEnabled(this) || !KlirAccessibilityService.isServiceRunning()) {
            Toast.makeText(
                this,
                "Enable Klir in Accessibility to automate App Cache / Hibernation",
                Toast.LENGTH_LONG
            ).show()
            openAccessibilitySettings()
            return
        }

        if (!canDrawOverlay()) {
            Toast.makeText(
                this,
                "Grant 'Display over other apps' to see the Floating Progress HUD",
                Toast.LENGTH_LONG
            ).show()
            requestOverlayPermission()
            return
        }

        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }

        val targetPackages = installedApps.filter { app ->
            val isUserApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isStopped = (app.flags and ApplicationInfo.FLAG_STOPPED) != 0
            val needsAction = if (forceStop && !clearCache) !isStopped else true
            isUserApp && app.packageName != packageName && needsAction
        }.map { it.packageName }

        if (targetPackages.isNotEmpty()) {
            KlirAccessibilityService.instance?.startAutomation(
                packages = targetPackages,
                clearCache = clearCache,
                forceStop = forceStop
            )
        } else {
            Toast.makeText(this, "All background apps are already hibernated!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${KlirAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expected)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun requestAddQuickTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = getSystemService(StatusBarManager::class.java)
            statusBarManager?.requestAddTileService(
                ComponentName(this, KlirQuickTileService::class.java),
                "Klir Sweep",
                Icon.createWithResource(this, R.mipmap.ic_launcher),
                mainExecutor
            ) { resultCode ->
                if (resultCode == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                    Toast.makeText(this, "Klir Sweep tile added!", Toast.LENGTH_SHORT).show()
                } else if (resultCode == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                    Toast.makeText(this, "Klir Sweep tile is already in Quick Settings", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(
                this,
                "Pull down Quick Settings and tap the Pencil icon to add 'Klir Sweep'",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KlirCleanerScreen(
    state: KlirUiState,
    hasPermission: Boolean,
    hasOverlayPermission: Boolean,
    canNavigateUpDiskMap: Boolean,
    onSelectVolume: (StorageVolumeInfo) -> Unit,
    onRefreshVolumes: () -> Unit,
    onToggleType: (JunkType) -> Unit,
    onScanClick: () -> Unit,
    onCleanClick: () -> Unit,
    onExcludePath: (String) -> Unit,
    onRemoveWhitelistedPath: (String) -> Unit,
    onRestoreTrashItem: (TrashItem) -> Unit,
    onEmptyTrashClick: () -> Unit,
    onLoadDiskMap: (File?) -> Unit,
    onNavigateUpDiskMap: () -> Unit,
    onTrashDiskNode: (DiskNode) -> Unit,
    onRequestPermissionClick: () -> Unit,
    onRequestOverlayClick: () -> Unit,
    onOpenAccessibilityClick: () -> Unit,
    onAddQuickTileClick: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var expandedTypes by remember { mutableStateOf(setOf<JunkType>()) }
    var showTrashSheet by remember { mutableStateOf(false) }
    var showWhitelistSheet by remember { mutableStateOf(false) }

    val selectedBytes = remember(state.scanSummary, state.selectedTypes) {
        state.scanSummary.items
            .filter { state.selectedTypes.contains(it.type) }
            .sumOf { it.sizeBytes }
    }

    if (showTrashSheet) {
        ModalBottomSheet(onDismissRequest = { showTrashSheet = false }) {
            RecycleBinSheetContent(
                volumeName = state.selectedVolume.name,
                trashItems = state.trashItems,
                totalTrashBytes = state.totalTrashBytes,
                onRestoreItem = onRestoreTrashItem,
                onEmptyTrash = {
                    onEmptyTrashClick()
                    showTrashSheet = false
                }
            )
        }
    }

    if (showWhitelistSheet) {
        ModalBottomSheet(onDismissRequest = { showWhitelistSheet = false }) {
            WhitelistSheetContent(
                whitelist = state.whitelist.toList().sorted(),
                onRemovePath = onRemoveWhitelistedPath
            )
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Klir", fontWeight = FontWeight.Bold) },
                    actions = {
                        OutlinedButton(
                            onClick = { showWhitelistSheet = true },
                            modifier = Modifier.padding(end = 6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Whitelist (${state.whitelist.size})",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        FilledTonalButton(
                            onClick = { showTrashSheet = true },
                            modifier = Modifier.padding(end = 8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            val badgeText = if (state.trashItems.isNotEmpty()) {
                                "Bin (${state.trashItems.size})"
                            } else {
                                "Bin"
                            }
                            Text(badgeText, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Smart Cleaner", fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            if (state.diskMapNodes.isEmpty() && !state.isDiskMapLoading) {
                                onLoadDiskMap(null)
                            }
                        },
                        text = { Text("Disk Map (Treemap)", fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Track 3: Multi-Volume Selector Bar (Internal, SD Card, USB-OTG)
            StorageVolumeSelectorBar(
                volumes = state.availableVolumes,
                selectedVolume = state.selectedVolume,
                onSelectVolume = onSelectVolume,
                onRefreshVolumes = onRefreshVolumes
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (!hasPermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "All Files Access Required",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Klir needs file permission to scan internal storage, SD cards, and USB-OTG drives.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onRequestPermissionClick) {
                            Text("Grant Storage Access")
                        }
                    }
                }
            }

            if (selectedTab == 0) {
                // ---------------- TAB 0: SMART CLEANER ----------------
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = formatFileSize(state.scanSummary.totalBytes),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Reclaimable on ${state.selectedVolume.name}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Text(
                    text = "Clean Targets (Tap category to inspect & exclude)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(JunkType.entries) { type ->
                        val isChecked = state.selectedTypes.contains(type)
                        val bytes = state.scanSummary.getBytesForType(type)
                        val count = state.scanSummary.getItemCountForType(type)
                        val isExpanded = expandedTypes.contains(type)
                        val categoryItems = remember(state.scanSummary, type) {
                            state.scanSummary.items.filter { it.type == type }
                        }

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (count > 0) {
                                        expandedTypes = if (isExpanded) {
                                            expandedTypes - type
                                        } else {
                                            expandedTypes + type
                                        }
                                    } else {
                                        onToggleType(type)
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { onToggleType(type) }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = type.displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = type.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (count > 0) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = if (isExpanded) "Hide $count items ▲" else "Inspect $count items ▼",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    if (bytes > 0 || count > 0) {
                                        Text(
                                            text = if (type == JunkType.EMPTY_DIRECTORIES) "$count dirs" else formatFileSize(bytes),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                AnimatedVisibility(visible = isExpanded && categoryItems.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(bottom = 4.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                        )

                                        categoryItems.take(40).forEach { item ->
                                            JunkFileRow(
                                                item = item,
                                                onExcludePath = onExcludePath
                                            )
                                        }

                                        if (categoryItems.size > 40) {
                                            Text(
                                                text = "...and ${categoryItems.size - 40} more items",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onOpenAccessibilityClick,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Accessibility", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = onAddQuickTileClick,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Add Quick Tile", maxLines = 1)
                            }
                            if (!hasOverlayPermission) {
                                OutlinedButton(
                                    onClick = onRequestOverlayClick,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("HUD Overlay", maxLines = 1)
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onScanClick,
                        enabled = !state.isScanning && !state.isCleaning,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        if (state.isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Scan")
                        }
                    }

                    Button(
                        onClick = onCleanClick,
                        enabled = !state.isScanning && !state.isCleaning && state.selectedTypes.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        if (state.isCleaning) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            val buttonLabel = if (selectedBytes > 0L) {
                                "Clean (${formatFileSize(selectedBytes)})"
                            } else {
                                "Clean"
                            }
                            Text(buttonLabel)
                        }
                    }
                }
            } else {
                // ---------------- TAB 1: INTERACTIVE DISK MAP ----------------
                DiskMapScreenContent(
                    selectedVolume = state.selectedVolume,
                    currentDir = state.diskMapCurrentDir,
                    nodes = state.diskMapNodes,
                    totalBytes = state.diskMapTotalBytes,
                    isLoading = state.isDiskMapLoading,
                    canNavigateUp = canNavigateUpDiskMap,
                    onDrillDown = { folder -> onLoadDiskMap(folder) },
                    onNavigateUp = onNavigateUpDiskMap,
                    onRefresh = { onLoadDiskMap(state.diskMapCurrentDir) },
                    onTrashNode = onTrashDiskNode,
                    onExcludePath = onExcludePath
                )
            }
        }
    }
}

@Composable
fun StorageVolumeSelectorBar(
    volumes: List<StorageVolumeInfo>,
    selectedVolume: StorageVolumeInfo,
    onSelectVolume: (StorageVolumeInfo) -> Unit,
    onRefreshVolumes: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(volumes, key = { it.rootDir.absolutePath }) { vol ->
                val isSelected = vol.rootDir.absolutePath == selectedVolume.rootDir.absolutePath
                val iconPrefix = if (vol.isRemovable) "🔌 " else "📱 "

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    },
                    border = if (isSelected) {
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                    modifier = Modifier
                        .widthIn(min = 150.dp, max = 220.dp)
                        .clickable { onSelectVolume(vol) }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "$iconPrefix${vol.name}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${formatFileSize(vol.freeBytes)} free of ${formatFileSize(vol.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { vol.usedFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        TextButton(
            onClick = onRefreshVolumes,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("↻ Drives", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------- Phase 4: Interactive Treemap UI & Layout Engine ----------------

private data class TreemapBlock(
    val node: DiskNode,
    val rect: Rect,
    val color: Color
)

private val treemapPalette = listOf(
    Color(0xFF1E88E5),
    Color(0xFF00897B),
    Color(0xFF8E24AA),
    Color(0xFFF4511E),
    Color(0xFF3949AB),
    Color(0xFF43A047),
    Color(0xFFFB8C00),
    Color(0xFFD81B60),
    Color(0xFF00ACC1),
    Color(0xFF5E35B1)
)

private fun computeTreemapLayout(
    nodes: List<DiskNode>,
    bounds: Rect
): List<TreemapBlock> {
    if (nodes.isEmpty() || bounds.width <= 1f || bounds.height <= 1f) return emptyList()

    val result = mutableListOf<TreemapBlock>()

    fun subdivide(subNodes: List<Pair<Int, DiskNode>>, rect: Rect) {
        if (subNodes.isEmpty()) return
        if (subNodes.size == 1) {
            val (originalIdx, node) = subNodes[0]
            val baseColor = treemapPalette[originalIdx % treemapPalette.size]
            result.add(TreemapBlock(node, rect, baseColor))
            return
        }

        val total = subNodes.sumOf { it.second.sizeBytes.toDouble() }
        if (total <= 0.0) return

        val half = total / 2.0
        var running = 0.0
        var splitIndex = 1
        var bestDiff = Double.MAX_VALUE

        for (i in 0 until subNodes.size - 1) {
            running += subNodes[i].second.sizeBytes.toDouble()
            val diff = abs(running - half)
            if (diff < bestDiff) {
                bestDiff = diff
                splitIndex = i + 1
            }
        }

        val firstGroup = subNodes.subList(0, splitIndex)
        val secondGroup = subNodes.subList(splitIndex, subNodes.size)
        val firstWeight = firstGroup.sumOf { it.second.sizeBytes.toDouble() }
        val ratio = (firstWeight / total).toFloat().coerceIn(0.10f, 0.90f)

        if (rect.width >= rect.height) {
            val splitX = rect.left + rect.width * ratio
            subdivide(firstGroup, Rect(rect.left, rect.top, splitX, rect.bottom))
            subdivide(secondGroup, Rect(splitX, rect.top, rect.right, rect.bottom))
        } else {
            val splitY = rect.top + rect.height * ratio
            subdivide(firstGroup, Rect(rect.left, rect.top, rect.right, splitY))
            subdivide(secondGroup, Rect(rect.left, splitY, rect.right, rect.bottom))
        }
    }

    val indexed = nodes.take(24).mapIndexed { idx, node -> idx to node }
    subdivide(indexed, bounds)
    return result
}

@Composable
fun DiskMapScreenContent(
    selectedVolume: StorageVolumeInfo,
    currentDir: File,
    nodes: List<DiskNode>,
    totalBytes: Long,
    isLoading: Boolean,
    canNavigateUp: Boolean,
    onDrillDown: (File) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onTrashNode: (DiskNode) -> Unit,
    onExcludePath: (String) -> Unit
) {
    var selectedNode by remember(currentDir, nodes) { mutableStateOf<DiskNode?>(null) }

    BackHandler(enabled = canNavigateUp) {
        onNavigateUp()
    }

    val rootPath = selectedVolume.rootDir.absolutePath
    val readableBreadcrumb = remember(currentDir, selectedVolume) {
        val rel = currentDir.absolutePath.removePrefix(rootPath).trimStart('/')
        if (rel.isEmpty()) "${selectedVolume.name} (/)" else "${selectedVolume.name} / ${rel.replace("/", " / ")}"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canNavigateUp) {
                    FilledTonalButton(
                        onClick = onNavigateUp,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("⬆ Up", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = readableBreadcrumb,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Folder Footprint: ${formatFileSize(totalBytes)} (${nodes.size} items)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Refresh", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Building Rust Treemap...",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else if (nodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No sizable files in this directory.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            var layoutBlocks by remember { mutableStateOf<List<TreemapBlock>>(emptyList()) }

            val titlePaint = remember {
                Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 30f
                    isAntiAlias = true
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
            }
            val sizePaint = remember {
                Paint().apply {
                    color = android.graphics.Color.argb(220, 255, 255, 255)
                    textSize = 25f
                    isAntiAlias = true
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(nodes, currentDir) {
                            detectTapGestures { tapOffset: Offset ->
                                val hit = layoutBlocks.firstOrNull { it.rect.contains(tapOffset) }
                                if (hit != null) {
                                    if (hit.node.isDirectory) {
                                        onDrillDown(hit.node.file)
                                    } else {
                                        selectedNode = hit.node
                                    }
                                }
                            }
                        }
                ) {
                    val blocks = computeTreemapLayout(
                        nodes = nodes,
                        bounds = Rect(0f, 0f, size.width, size.height)
                    )
                    layoutBlocks = blocks

                    val pad = 2.dp.toPx()

                    blocks.forEach { block ->
                        val r = block.rect
                        val insetLeft = r.left + pad
                        val insetTop = r.top + pad
                        val insetW = (r.width - pad * 2).coerceAtLeast(1f)
                        val insetH = (r.height - pad * 2).coerceAtLeast(1f)

                        val isSelected = selectedNode?.file?.absolutePath == block.node.file.absolutePath

                        drawRoundRect(
                            color = block.color,
                            topLeft = Offset(insetLeft, insetTop),
                            size = Size(insetW, insetH),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                        )

                        if (isSelected) {
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(insetLeft, insetTop),
                                size = Size(insetW, insetH),
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }

                        if (insetW > 56.dp.toPx() && insetH > 34.dp.toPx()) {
                            drawContext.canvas.nativeCanvas.apply {
                                save()
                                clipRect(
                                    insetLeft + 6f,
                                    insetTop + 4f,
                                    insetLeft + insetW - 6f,
                                    insetTop + insetH - 4f
                                )
                                val prefix = if (block.node.isDirectory) "📁 " else ""
                                drawText(
                                    "$prefix${block.node.name}",
                                    insetLeft + 12f,
                                    insetTop + 34f,
                                    titlePaint
                                )
                                if (insetH > 52.dp.toPx()) {
                                    drawText(
                                        formatFileSize(block.node.sizeBytes),
                                        insetLeft + 12f,
                                        insetTop + 64f,
                                        sizePaint
                                    )
                                }
                                restore()
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Tip: Tap any folder block on the map to drill down, or tap a file block to inspect.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        selectedNode?.let { node ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${formatFileSize(node.sizeBytes)} • Selected on Treemap",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(onClick = { onExcludePath(node.file.absolutePath) }) {
                        Text("Whitelist")
                    }
                    Button(
                        onClick = {
                            onTrashNode(node)
                            selectedNode = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Move to Bin", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(nodes, key = { it.file.absolutePath }) { node ->
                val share = if (totalBytes > 0) {
                    (node.sizeBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val pctString = String.format("%.1f%%", share * 100f)

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (node.isDirectory) {
                                onDrillDown(node.file)
                            } else {
                                selectedNode = node
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (node.isDirectory) "📁 ${node.name}" else "📄 ${node.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (node.isDirectory) {
                                        "${node.fileCount} files • $pctString of folder"
                                    } else {
                                        "File • $pctString of folder"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = formatFileSize(node.sizeBytes),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            OutlinedButton(
                                onClick = { onTrashNode(node) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text("Bin", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { share },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun JunkFileRow(
    item: JunkItem,
    onExcludePath: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.file.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.details.isNotBlank()) {
                    Text(
                        text = item.details,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = item.file.parent ?: item.file.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (item.sizeBytes > 0L) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = formatFileSize(item.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Box {
                TextButton(
                    onClick = { showMenu = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Exclude", style = MaterialTheme.typography.labelSmall)
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(if (item.file.isDirectory) "Exclude this folder" else "Exclude this file")
                        },
                        onClick = {
                            showMenu = false
                            onExcludePath(item.file.absolutePath)
                        }
                    )
                    if (!item.file.isDirectory) {
                        item.file.parentFile?.let { parent ->
                            DropdownMenuItem(
                                text = { Text("Exclude parent folder (${parent.name})") },
                                onClick = {
                                    showMenu = false
                                    onExcludePath(parent.absolutePath)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WhitelistSheetContent(
    whitelist: List<String>,
    onRemovePath: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "Exclusion Whitelist",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Whitelisted files and folders are skipped during all native Rust scans",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (whitelist.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No excluded paths yet. Tap 'Exclude' on any item in the inspector to protect it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(whitelist, key = { it }) { path ->
                    val fileObj = File(path)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = fileObj.name.ifEmpty { path },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = path,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { onRemovePath(path) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Remove", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecycleBinSheetContent(
    volumeName: String,
    trashItems: List<TrashItem>,
    totalTrashBytes: Long,
    onRestoreItem: (TrashItem) -> Unit,
    onEmptyTrash: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Recycle Bin • $volumeName",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Items auto-purge 3 days after deletion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trashItems.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onEmptyTrash,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Empty (${formatFileSize(totalTrashBytes)})")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (trashItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Recycle Bin on $volumeName is empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(trashItems, key = { it.trashFile.name }) { item ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.fileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${formatFileSize(item.sizeBytes)} • ${item.remainingTimeLabel()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = item.originalPath,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { onRestoreItem(item) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Restore", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}