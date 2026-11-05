package com.joshit.klir

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class KlirQuickTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isSweeping = false

    override fun onStartListening() {
        super.onStartListening()
        updateTileUi(
            state = Tile.STATE_INACTIVE,
            label = "Klir Sweep",
            subtitle = "Tap to clean junk"
        )
    }

    override fun onClick() {
        super.onClick()
        if (isSweeping) return

        val engine = StorageCleanerEngine(applicationContext)

        // If All Files Access is not granted yet, open MainActivity
        if (!engine.hasStoragePermission()) {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launchIntent)
            }
            return
        }

        isSweeping = true
        updateTileUi(
            state = Tile.STATE_ACTIVE,
            label = "Sweeping...",
            subtitle = "Scanning storage"
        )

        serviceScope.launch {
            // 1. Purge expired >3-day items from .klir_trash
            engine.loadAndPurgeTrash()

            // 2. Scan fast, non-destructive junk categories
            val quickTargets = setOf(
                JunkType.THUMBNAILS,
                JunkType.INSTALLERS,
                JunkType.TEMP_FILES,
                JunkType.EMPTY_DIRECTORIES,
                JunkType.APP_CORPSES
            )
            val summary = engine.scanStorage(quickTargets)
            val movedBytes = if (summary.items.isNotEmpty()) {
                engine.moveToTrash(summary.items)
            } else {
                0L
            }

            isSweeping = false

            val resultSubtitle = if (movedBytes > 0L) {
                "Freed ${formatFileSize(movedBytes)}"
            } else {
                "Storage is clean"
            }

            updateTileUi(
                state = Tile.STATE_INACTIVE,
                label = "Klir Sweep",
                subtitle = resultSubtitle
            )

            Toast.makeText(
                applicationContext,
                if (movedBytes > 0L) {
                    "Klir moved ${formatFileSize(movedBytes)} to Recycle Bin"
                } else {
                    "Klir: No residual junk found"
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateTileUi(state: Int, label: String, subtitle: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}