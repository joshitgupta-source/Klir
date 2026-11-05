package com.joshit.klir

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

val defaultSafeJunkTypes: Set<JunkType> = setOf(
    JunkType.APP_CACHE,
    JunkType.APP_CORPSES,
    JunkType.THUMBNAILS,
    JunkType.INSTALLERS,
    JunkType.TEMP_FILES,
    JunkType.EMPTY_DIRECTORIES,
    JunkType.MESSENGER_MEDIA,
    JunkType.DUPLICATE_FILES
)

data class KlirUiState(
    val availableVolumes: List<StorageVolumeInfo> = emptyList(),
    val selectedVolume: StorageVolumeInfo = StorageVolumeInfo(
        name = "Internal Storage",
        rootDir = Environment.getExternalStorageDirectory(),
        isRemovable = false,
        totalBytes = 0L,
        freeBytes = 0L
    ),
    val selectedTypes: Set<JunkType> = defaultSafeJunkTypes,
    val scanSummary: ScanSummary = ScanSummary(),
    val trashItems: List<TrashItem> = emptyList(),
    val whitelist: Set<String> = emptySet(),
    val isScanning: Boolean = false,
    val isCleaning: Boolean = false,
    val lastCleanedBytes: Long = 0L,
    val statusMessage: String = "Ready to scan",
    val diskMapCurrentDir: File = Environment.getExternalStorageDirectory(),
    val diskMapNodes: List<DiskNode> = emptyList(),
    val isDiskMapLoading: Boolean = false
) {
    val totalTrashBytes: Long get() = trashItems.sumOf { it.sizeBytes }
    val diskMapTotalBytes: Long get() = diskMapNodes.sumOf { it.sizeBytes }
}

class KlirViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = StorageCleanerEngine(application.applicationContext)

    private val _uiState: MutableStateFlow<KlirUiState>

    init {
        val initialVolumes = engine.getMountedVolumes()
        val primaryVol = initialVolumes.first()
        _uiState = MutableStateFlow(
            KlirUiState(
                availableVolumes = initialVolumes,
                selectedVolume = primaryVol,
                whitelist = engine.getWhitelist(),
                diskMapCurrentDir = primaryVol.rootDir
            )
        )
        refreshTrash()
    }

    val uiState: StateFlow<KlirUiState> = _uiState.asStateFlow()

    fun hasStoragePermission(): Boolean = engine.hasStoragePermission()

    fun refreshVolumesAndTrash() {
        val volumes = engine.getMountedVolumes()
        val currentRootPath = _uiState.value.selectedVolume.rootDir.absolutePath
        val activeVol = volumes.find { it.rootDir.absolutePath == currentRootPath } ?: volumes.first()

        _uiState.update {
            it.copy(
                availableVolumes = volumes,
                selectedVolume = activeVol,
                whitelist = engine.getWhitelist()
            )
        }
        refreshTrash()
    }

    fun selectVolume(volume: StorageVolumeInfo) {
        if (volume.rootDir.absolutePath == _uiState.value.selectedVolume.rootDir.absolutePath) return

        _uiState.update {
            it.copy(
                selectedVolume = volume,
                scanSummary = ScanSummary(),
                diskMapCurrentDir = volume.rootDir,
                diskMapNodes = emptyList(),
                statusMessage = "Switched to ${volume.name}"
            )
        }
        refreshTrash()
        loadDiskMap(volume.rootDir)
    }

    fun refreshTrash() {
        if (!hasStoragePermission()) return
        val volumeRoot = _uiState.value.selectedVolume.rootDir
        viewModelScope.launch {
            val activeTrash = engine.loadAndPurgeTrash(volumeRoot)
            _uiState.update {
                it.copy(
                    trashItems = activeTrash,
                    whitelist = engine.getWhitelist()
                )
            }
        }
    }

    fun toggleJunkType(type: JunkType) {
        _uiState.update { current ->
            val updated = current.selectedTypes.toMutableSet()
            if (updated.contains(type)) {
                updated.remove(type)
            } else {
                updated.add(type)
            }
            current.copy(selectedTypes = updated)
        }
    }

    fun excludePath(path: String) {
        val updatedWhitelist = engine.addToWhitelist(path)
        _uiState.update { current ->
            val filteredItems = current.scanSummary.items.filterNot { item ->
                val itemPath = item.file.absolutePath
                itemPath == path || itemPath.startsWith("$path/")
            }
            val filteredNodes = current.diskMapNodes.filterNot { node ->
                val nodePath = node.file.absolutePath
                nodePath == path || nodePath.startsWith("$path/")
            }
            val newSummary = ScanSummary(
                items = filteredItems,
                totalBytes = filteredItems.sumOf { it.sizeBytes },
                isScanning = false
            )
            current.copy(
                whitelist = updatedWhitelist,
                scanSummary = newSummary,
                diskMapNodes = filteredNodes,
                statusMessage = "Whitelisted ${File(path).name}"
            )
        }
    }

    fun removeWhitelistedPath(path: String) {
        val updatedWhitelist = engine.removeFromWhitelist(path)
        _uiState.update {
            it.copy(
                whitelist = updatedWhitelist,
                statusMessage = "Removed ${File(path).name} from whitelist"
            )
        }
    }

    fun scan() {
        if (!hasStoragePermission()) {
            _uiState.update { it.copy(statusMessage = "Storage permission required") }
            return
        }

        val activeVolume = _uiState.value.selectedVolume
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning = true,
                    statusMessage = "Scanning ${activeVolume.name}..."
                )
            }

            val activeTrash = engine.loadAndPurgeTrash(activeVolume.rootDir)
            val summary = engine.scanStorage(_uiState.value.selectedTypes, activeVolume.rootDir)
            val refreshedVolumes = engine.getMountedVolumes()

            _uiState.update {
                it.copy(
                    isScanning = false,
                    availableVolumes = refreshedVolumes,
                    scanSummary = summary,
                    trashItems = activeTrash,
                    whitelist = engine.getWhitelist(),
                    statusMessage = "Scan complete on ${activeVolume.name}. Found ${formatFileSize(summary.totalBytes)}"
                )
            }
        }
    }

    fun cleanSelectedFiles(onCompleteFiles: () -> Unit) {
        val currentSummary = _uiState.value.scanSummary
        val selectedTypes = _uiState.value.selectedTypes
        val volumeRoot = _uiState.value.selectedVolume.rootDir

        val itemsToTrash = currentSummary.items.filter { selectedTypes.contains(it.type) }

        viewModelScope.launch {
            _uiState.update { it.copy(isCleaning = true, statusMessage = "Moving junk to Recycle Bin...") }

            val movedBytes = engine.moveToTrash(itemsToTrash, volumeRoot)
            val updatedTrash = engine.loadAndPurgeTrash(volumeRoot)
            val remainingSummary = engine.scanStorage(selectedTypes, volumeRoot)
            val refreshedVolumes = engine.getMountedVolumes()

            _uiState.update {
                it.copy(
                    isCleaning = false,
                    availableVolumes = refreshedVolumes,
                    scanSummary = remainingSummary,
                    trashItems = updatedTrash,
                    lastCleanedBytes = movedBytes,
                    statusMessage = "Moved ${formatFileSize(movedBytes)} to Recycle Bin (3-day auto-purge)"
                )
            }
            onCompleteFiles()
        }
    }

    fun restoreFromTrash(item: TrashItem) {
        val volumeRoot = _uiState.value.selectedVolume.rootDir
        viewModelScope.launch {
            val success = engine.restoreTrashItem(item, volumeRoot)
            val updatedTrash = engine.loadAndPurgeTrash(volumeRoot)
            _uiState.update {
                it.copy(
                    trashItems = updatedTrash,
                    statusMessage = if (success) "Restored ${item.fileName}" else "Failed to restore ${item.fileName}"
                )
            }
        }
    }

    fun emptyTrashNow() {
        val volumeRoot = _uiState.value.selectedVolume.rootDir
        viewModelScope.launch {
            val freed = engine.emptyTrash(volumeRoot)
            val refreshedVolumes = engine.getMountedVolumes()
            _uiState.update {
                it.copy(
                    availableVolumes = refreshedVolumes,
                    trashItems = emptyList(),
                    statusMessage = "Permanently freed ${formatFileSize(freed)} from Recycle Bin"
                )
            }
        }
    }

    fun loadDiskMap(targetDir: File = _uiState.value.diskMapCurrentDir) {
        if (!hasStoragePermission()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    diskMapCurrentDir = targetDir,
                    isDiskMapLoading = true
                )
            }
            val nodes = engine.scanDiskDirectory(targetDir)
            _uiState.update {
                it.copy(
                    diskMapNodes = nodes,
                    isDiskMapLoading = false
                )
            }
        }
    }

    fun canNavigateUpDiskMap(): Boolean {
        val current = _uiState.value.diskMapCurrentDir.absolutePath
        val root = _uiState.value.selectedVolume.rootDir.absolutePath
        return current != root && current.startsWith(root)
    }

    fun navigateUpDiskMap() {
        if (!canNavigateUpDiskMap()) return
        val rootDir = _uiState.value.selectedVolume.rootDir
        val parent = _uiState.value.diskMapCurrentDir.parentFile ?: rootDir
        loadDiskMap(parent)
    }

    fun trashDiskNode(node: DiskNode) {
        val volumeRoot = _uiState.value.selectedVolume.rootDir
        viewModelScope.launch {
            _uiState.update { it.copy(isDiskMapLoading = true) }
            val moved = engine.moveDiskNodeToTrash(node, volumeRoot)
            val updatedTrash = engine.loadAndPurgeTrash(volumeRoot)
            val refreshedNodes = engine.scanDiskDirectory(_uiState.value.diskMapCurrentDir)
            val refreshedVolumes = engine.getMountedVolumes()
            _uiState.update {
                it.copy(
                    availableVolumes = refreshedVolumes,
                    trashItems = updatedTrash,
                    diskMapNodes = refreshedNodes,
                    isDiskMapLoading = false,
                    statusMessage = if (moved) {
                        "Moved ${node.name} (${formatFileSize(node.sizeBytes)}) to Recycle Bin"
                    } else {
                        "Could not move ${node.name}"
                    }
                )
            }
        }
    }
}