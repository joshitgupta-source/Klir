package com.joshit.klir

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class StorageCleanerEngine(private val context: Context) {

    private val prefs = context.getSharedPreferences("klir_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TRASH_FOLDER_NAME = ".klir_trash"
        private const val MANIFEST_FILE_NAME = ".manifest"
        private const val NOMEDIA_FILE_NAME = ".nomedia"
        private const val KEY_WHITELIST = "excluded_paths"
        private const val THREE_DAYS_MILLIS = 3 * 24 * 60 * 60 * 1000L

        init {
            System.loadLibrary("klir_core")
        }
    }

    @Keep
    private external fun scanNative(
        rootPath: String,
        excludedPathsRaw: String,
        installedPackagesRaw: String,
        checkThumbnails: Boolean,
        checkApks: Boolean,
        checkTemp: Boolean,
        checkEmptyDirs: Boolean,
        checkLarge: Boolean,
        checkStale: Boolean,
        checkMessenger: Boolean,
        checkDuplicates: Boolean,
        checkCorpses: Boolean
    ): String

    @Keep
    private external fun scanDiskMapNative(
        targetPath: String,
        excludedPathsRaw: String
    ): String

    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Discovers Internal Storage, MicroSD cards, and USB-OTG drives currently mounted.
     */
    fun getMountedVolumes(): List<StorageVolumeInfo> {
        val volumes = mutableListOf<StorageVolumeInfo>()
        val seenPaths = mutableSetOf<String>()

        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && sm != null) {
            for (sv in sm.storageVolumes) {
                if (sv.state != Environment.MEDIA_MOUNTED) continue
                val dir = sv.directory ?: continue
                if (seenPaths.add(dir.absolutePath)) {
                    val desc = sv.getDescription(context)
                    val label = when {
                        sv.isPrimary -> "Internal Storage"
                        desc.isNotBlank() -> desc
                        else -> "External (${dir.name})"
                    }
                    volumes.add(
                        StorageVolumeInfo(
                            name = label,
                            rootDir = dir,
                            isRemovable = sv.isRemovable,
                            totalBytes = dir.totalSpace,
                            freeBytes = dir.usableSpace
                        )
                    )
                }
            }
        }

        // Fallback discovery via external files dirs for older APIs or secondary mounts
        val extDirs = context.getExternalFilesDirs(null)
        for (extDir in extDirs) {
            if (extDir == null) continue
            val rootPath = extDir.absolutePath.substringBefore("/Android/data")
            val rootFile = File(rootPath)
            if (rootFile.exists() && seenPaths.add(rootFile.absolutePath)) {
                val isPrimary = rootFile.absolutePath == Environment.getExternalStorageDirectory().absolutePath
                val label = if (isPrimary) "Internal Storage" else "Drive (${rootFile.name})"
                volumes.add(
                    StorageVolumeInfo(
                        name = label,
                        rootDir = rootFile,
                        isRemovable = !isPrimary,
                        totalBytes = rootFile.totalSpace,
                        freeBytes = rootFile.usableSpace
                    )
                )
            }
        }

        if (volumes.isEmpty()) {
            val fallback = Environment.getExternalStorageDirectory()
            volumes.add(
                StorageVolumeInfo(
                    name = "Internal Storage",
                    rootDir = fallback,
                    isRemovable = false,
                    totalBytes = fallback.totalSpace,
                    freeBytes = fallback.usableSpace
                )
            )
        }

        return volumes
    }

    fun getWhitelist(): Set<String> {
        return prefs.getStringSet(KEY_WHITELIST, emptySet())?.toSet() ?: emptySet()
    }

    fun addToWhitelist(path: String): Set<String> {
        val updated = getWhitelist().toMutableSet().apply { add(path) }
        prefs.edit().putStringSet(KEY_WHITELIST, updated).apply()
        return updated
    }

    fun removeFromWhitelist(path: String): Set<String> {
        val updated = getWhitelist().toMutableSet().apply { remove(path) }
        prefs.edit().putStringSet(KEY_WHITELIST, updated).apply()
        return updated
    }

    private fun getInstalledPackagesRaw(): String {
        return runCatching {
            val pm = context.packageManager
            val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            apps.joinToString("\n") { it.packageName.lowercase() }
        }.getOrDefault("")
    }

    private fun getOrCreateTrashDir(
        volumeRoot: File = Environment.getExternalStorageDirectory()
    ): File {
        val trashDir = File(volumeRoot, TRASH_FOLDER_NAME)
        if (!trashDir.exists()) {
            trashDir.mkdirs()
        }
        val noMedia = File(trashDir, NOMEDIA_FILE_NAME)
        if (!noMedia.exists()) {
            runCatching { noMedia.createNewFile() }
        }
        return trashDir
    }

    private fun getManifestFile(
        volumeRoot: File = Environment.getExternalStorageDirectory()
    ): File = File(getOrCreateTrashDir(volumeRoot), MANIFEST_FILE_NAME)

    suspend fun scanStorage(
        selectedTypes: Set<JunkType>,
        rootDir: File = Environment.getExternalStorageDirectory()
    ): ScanSummary = withContext(Dispatchers.IO) {
        if (!rootDir.exists()) return@withContext ScanSummary()
        val excludedPathsRaw = getWhitelist().joinToString("\n")
        val installedPackagesRaw = getInstalledPackagesRaw()

        val rawResults = scanNative(
            rootPath = rootDir.absolutePath,
            excludedPathsRaw = excludedPathsRaw,
            installedPackagesRaw = installedPackagesRaw,
            checkThumbnails = selectedTypes.contains(JunkType.THUMBNAILS),
            checkApks = selectedTypes.contains(JunkType.INSTALLERS),
            checkTemp = selectedTypes.contains(JunkType.TEMP_FILES),
            checkEmptyDirs = selectedTypes.contains(JunkType.EMPTY_DIRECTORIES),
            checkLarge = selectedTypes.contains(JunkType.LARGE_FILES),
            checkStale = selectedTypes.contains(JunkType.STALE_FILES),
            checkMessenger = selectedTypes.contains(JunkType.MESSENGER_MEDIA),
            checkDuplicates = selectedTypes.contains(JunkType.DUPLICATE_FILES),
            checkCorpses = selectedTypes.contains(JunkType.APP_CORPSES)
        )

        val junkFound = mutableListOf<JunkItem>()

        rawResults.lineSequence().forEach { line ->
            if (line.isNotBlank()) {
                val parts = line.split("|", limit = 4)
                if (parts.size == 4) {
                    val type = when (parts[0]) {
                        "0" -> JunkType.THUMBNAILS
                        "1" -> JunkType.INSTALLERS
                        "2" -> JunkType.TEMP_FILES
                        "3" -> JunkType.EMPTY_DIRECTORIES
                        "4" -> JunkType.LARGE_FILES
                        "5" -> JunkType.STALE_FILES
                        "6" -> JunkType.MESSENGER_MEDIA
                        "7" -> JunkType.DUPLICATE_FILES
                        "8" -> JunkType.APP_CORPSES
                        else -> null
                    }
                    val size = parts[1].toLongOrNull() ?: 0L
                    val details = parts[2]
                    val file = File(parts[3])

                    if (type != null) {
                        junkFound.add(JunkItem(file, size, type, details))
                    }
                }
            }
        }

        ScanSummary(
            items = junkFound,
            totalBytes = junkFound.sumOf { it.sizeBytes },
            isScanning = false
        )
    }

    suspend fun scanDiskDirectory(targetDir: File): List<DiskNode> = withContext(Dispatchers.IO) {
        if (!hasStoragePermission() || !targetDir.exists()) return@withContext emptyList()
        val excludedPathsRaw = getWhitelist().joinToString("\n")
        val raw = scanDiskMapNative(targetDir.absolutePath, excludedPathsRaw)

        val nodes = mutableListOf<DiskNode>()
        raw.lineSequence().forEach { line ->
            if (line.isNotBlank()) {
                val parts = line.split("|", limit = 5)
                if (parts.size == 5) {
                    val isDir = parts[0] == "1"
                    val sizeBytes = parts[1].toLongOrNull() ?: 0L
                    val fileCount = parts[2].toIntOrNull() ?: 1
                    val name = parts[3]
                    val fullPath = parts[4]
                    nodes.add(
                        DiskNode(
                            name = name,
                            file = File(fullPath),
                            sizeBytes = sizeBytes,
                            fileCount = fileCount,
                            isDirectory = isDir
                        )
                    )
                }
            }
        }
        nodes
    }

    suspend fun moveToTrash(
        items: List<JunkItem>,
        volumeRoot: File = Environment.getExternalStorageDirectory()
    ): Long = withContext(Dispatchers.IO) {
        val trashDir = getOrCreateTrashDir(volumeRoot)
        val manifestFile = getManifestFile(volumeRoot)
        val now = System.currentTimeMillis()
        var movedBytes = 0L
        val newEntries = StringBuilder()

        for (item in items) {
            if (!item.file.exists()) continue

            if (item.type == JunkType.EMPTY_DIRECTORIES) {
                item.file.deleteRecursively()
                continue
            }

            val size = item.sizeBytes
            val originalPath = item.file.absolutePath
            val uniqueTrashName = "${now}_${UUID.randomUUID().toString().take(8)}_${item.file.name}"
            val destFile = File(trashDir, uniqueTrashName)

            val moved = if (item.file.renameTo(destFile)) {
                true
            } else {
                runCatching {
                    item.file.copyRecursively(destFile, overwrite = true)
                    item.file.deleteRecursively()
                }.getOrDefault(false)
            }

            if (moved) {
                movedBytes += size
                newEntries.append("${destFile.name}|$now|$size|$originalPath\n")
            }
        }

        if (newEntries.isNotEmpty()) {
            manifestFile.appendText(newEntries.toString())
        }

        movedBytes
    }

    suspend fun moveDiskNodeToTrash(
        node: DiskNode,
        volumeRoot: File = Environment.getExternalStorageDirectory()
    ): Boolean = withContext(Dispatchers.IO) {
        val dummyJunk = JunkItem(
            file = node.file,
            sizeBytes = node.sizeBytes,
            type = JunkType.LARGE_FILES
        )
        moveToTrash(listOf(dummyJunk), volumeRoot) > 0L
    }

    suspend fun loadAndPurgeTrash(
        volumeRoot: File = Environment.getExternalStorageDirectory()
    ): List<TrashItem> = withContext(Dispatchers.IO) {
        if (!hasStoragePermission() || !volumeRoot.exists()) return@withContext emptyList()
        val trashDir = getOrCreateTrashDir(volumeRoot)
        val manifestFile = getManifestFile(volumeRoot)
        if (!manifestFile.exists()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val activeItems = mutableListOf<TrashItem>()
        val updatedManifest = StringBuilder()

        manifestFile.readLines().forEach { line ->
            if (line.isNotBlank()) {
                val parts = line.split("|", limit = 4)
                if (parts.size == 4) {
                    val trashFile = File(trashDir, parts[0])
                    val trashedAt = parts[1].toLongOrNull() ?: now
                    val size = parts[2].toLongOrNull() ?: 0L
                    val originalPath = parts[3]

                    val isExpired = (now - trashedAt) >= THREE_DAYS_MILLIS

                    if (isExpired) {
                        if (trashFile.exists()) trashFile.deleteRecursively()
                    } else if (trashFile.exists()) {
                        activeItems.add(TrashItem(trashFile, originalPath, size, trashedAt))
                        updatedManifest.append(line).append("\n")
                    }
                }
            }
        }

        manifestFile.writeText(updatedManifest.toString())
        activeItems.sortedByDescending { it.trashedAtMillis }
    }

    suspend fun restoreTrashItem(
        target: TrashItem,
        volumeRoot: File = Environment.getExternalStorageDirectory()
    ): Boolean = withContext(Dispatchers.IO) {
        val originalFile = File(target.originalPath)
        originalFile.parentFile?.mkdirs()

        val restored = if (target.trashFile.renameTo(originalFile)) {
            true
        } else {
            runCatching {
                target.trashFile.copyRecursively(originalFile, overwrite = true)
                target.trashFile.deleteRecursively()
            }.getOrDefault(false)
        }

        if (restored) {
            val manifestFile = getManifestFile(volumeRoot)
            if (manifestFile.exists()) {
                val remainingLines = manifestFile.readLines()
                    .filter { !it.startsWith("${target.trashFile.name}|") }
                manifestFile.writeText(
                    if (remainingLines.isEmpty()) "" else remainingLines.joinToString("\n") + "\n"
                )
            }
        }
        restored
    }

    suspend fun emptyTrash(
        volumeRoot: File = Environment.getExternalStorageDirectory()
    ): Long = withContext(Dispatchers.IO) {
        val trashDir = getOrCreateTrashDir(volumeRoot)
        val activeItems = loadAndPurgeTrash(volumeRoot)
        val totalBytes = activeItems.sumOf { it.sizeBytes }

        trashDir.listFiles()?.forEach { file ->
            if (file.name != NOMEDIA_FILE_NAME) {
                file.deleteRecursively()
            }
        }
        totalBytes
    }
}