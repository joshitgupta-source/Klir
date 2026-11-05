package com.joshit.klir

import java.io.File

enum class JunkType(val displayName: String, val description: String) {
    APP_CACHE("App Cache (Automated)", "Clears runtime cache via Accessibility"),
    APP_HIBERNATION("Hibernate Apps (Force Stop)", "Stops background battery-draining apps via Accessibility"),
    APP_CORPSES("CorpseFinder (Orphaned Data)", "Leftover folders from uninstalled apps"),
    THUMBNAILS("Gallery Thumbnails", "Caches in DCIM/.thumbnails and media caches"),
    INSTALLERS("Old APK Files", "Leftover installer packages (.apk) in storage"),
    TEMP_FILES("Temp & Log Files", "Stale .tmp, .log, and .bak files"),
    EMPTY_DIRECTORIES("Empty Folders", "Orphaned empty folder trees"),
    MESSENGER_MEDIA("WhatsApp & Telegram", "Sent media copies, statuses, and voice notes"),
    DUPLICATE_FILES("Duplicate Files (BLAKE3)", "Byte-identical copies (preserves oldest original)"),
    LARGE_FILES("Large Files (>100 MB)", "Oversized videos, archives, and downloads"),
    STALE_FILES("Stale Files (>6 Months)", "Files over 10 MB untouched for 180+ days")
}

data class JunkItem(
    val file: File,
    val sizeBytes: Long,
    val type: JunkType,
    val details: String = ""
)

data class ScanSummary(
    val items: List<JunkItem> = emptyList(),
    val totalBytes: Long = 0L,
    val isScanning: Boolean = false
) {
    fun getBytesForType(type: JunkType): Long =
        items.filter { it.type == type }.sumOf { it.sizeBytes }

    fun getItemCountForType(type: JunkType): Int =
        items.count { it.type == type }
}

data class TrashItem(
    val trashFile: File,
    val originalPath: String,
    val sizeBytes: Long,
    val trashedAtMillis: Long
) {
    val fileName: String get() = File(originalPath).name

    fun remainingTimeLabel(nowMillis: Long = System.currentTimeMillis()): String {
        val ttlMillis = 3 * 24 * 60 * 60 * 1000L
        val remainingMs = (trashedAtMillis + ttlMillis) - nowMillis
        if (remainingMs <= 0) return "Expiring soon"
        val hours = remainingMs / (1000 * 60 * 60)
        val days = hours / 24
        val remHours = hours % 24
        return if (days > 0) "Purges in ${days}d ${remHours}h" else "Purges in ${remHours}h"
    }
}

data class DiskNode(
    val name: String,
    val file: File,
    val sizeBytes: Long,
    val fileCount: Int,
    val isDirectory: Boolean
)

data class StorageVolumeInfo(
    val name: String,
    val rootDir: File,
    val isRemovable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (totalBytes > 0L) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}