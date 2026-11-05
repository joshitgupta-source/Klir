package com.joshit.klir

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class KlirMaintenanceWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val WORK_NAME = "klir_periodic_maintenance"
        private const val CHANNEL_ID = "klir_storage_insights"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_THRESHOLD_BYTES = 250L * 1024 * 1024 // 250 MB

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<KlirMaintenanceWorker>(
                24, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val engine = StorageCleanerEngine(applicationContext)
        if (!engine.hasStoragePermission()) {
            return Result.success()
        }

        // 1. Enforce 3-day .klir_trash expiration automatically in the background
        engine.loadAndPurgeTrash()

        // 2. Perform silent background scan of safe categories
        val backgroundTargets = setOf(
            JunkType.APP_CORPSES,
            JunkType.THUMBNAILS,
            JunkType.INSTALLERS,
            JunkType.TEMP_FILES,
            JunkType.MESSENGER_MEDIA,
            JunkType.DUPLICATE_FILES
        )
        val summary = engine.scanStorage(backgroundTargets)

        // 3. Notify user only if reclaimable clutter exceeds 250 MB
        if (summary.totalBytes >= ALERT_THRESHOLD_BYTES) {
            sendStorageAlertNotification(summary.totalBytes, summary.items.size)
        }

        return Result.success()
    }

    private fun sendStorageAlertNotification(totalBytes: Long, itemCount: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Storage Insights & Auto-Purge",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when residual junk or duplicate files accumulate"
            }
            nm.createNotificationChannel(channel)
        }

        val tapIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedSize = formatFileSize(totalBytes)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sdcard)
            .setContentTitle("Klir: $formattedSize Reclaimable")
            .setContentText("Found $itemCount junk & duplicate items ready to clean.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}