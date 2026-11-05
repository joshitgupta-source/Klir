package com.joshit.klir

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.ConcurrentLinkedQueue

class KlirAccessibilityService : AccessibilityService() {

    companion object {
        var instance: KlirAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val packageQueue = ConcurrentLinkedQueue<String>()

    private var isAutomating = false
    private var doClearCache = true
    private var doForceStop = false

    private var totalPackages = 0
    private var processedCount = 0
    private var currentPackage: String? = null

    private var storageEnteredForCurrentPkg = false
    private var cacheClearedForCurrentPkg = false
    private var forceStopClickedForCurrentPkg = false
    private var forceStopConfirmedForCurrentPkg = false
    private var lastActionTimestamp = 0L

    // Floating HUD Overlay UI elements
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var hudTitleText: TextView? = null
    private var hudSubtitleText: TextView? = null
    private var hudProgressBar: ProgressBar? = null

    // Watchdog timer: skips app if buttons are disabled (0 B cache / already stopped)
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isAutomating) return
            val elapsed = System.currentTimeMillis() - lastActionTimestamp
            if (elapsed > 2800L) {
                advanceToNextPackage()
            } else {
                handler.postDelayed(this, 500L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideFloatingHud()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingHud()
        instance = null
        isAutomating = false
    }

    override fun onInterrupt() {
        stopAutomation(returnToApp = false)
    }

    /**
     * Backward-compatible entry point for clearing cache only.
     */
    fun startClearing(packages: List<String>) {
        startAutomation(packages = packages, clearCache = true, forceStop = false)
    }

    /**
     * Automates Cache Clearing, App Hibernation, or both with a floating HUD.
     */
    fun startAutomation(
        packages: List<String>,
        clearCache: Boolean,
        forceStop: Boolean
    ) {
        if (packages.isEmpty() || (!clearCache && !forceStop)) return

        packageQueue.clear()
        packageQueue.addAll(packages)
        totalPackages = packages.size
        processedCount = 0
        doClearCache = clearCache
        doForceStop = forceStop
        isAutomating = true

        handler.post {
            showFloatingHud()
            processNextPackage()
        }
    }

    fun stopAutomation(returnToApp: Boolean = true) {
        isAutomating = false
        packageQueue.clear()
        currentPackage = null
        handler.removeCallbacksAndMessages(null)
        handler.post {
            hideFloatingHud()
            if (returnToApp) {
                val launchIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(launchIntent)
            }
        }
    }

    private fun processNextPackage() {
        if (!isAutomating) return

        val nextPkg = packageQueue.poll()
        if (nextPkg == null) {
            stopAutomation(returnToApp = true)
            return
        }

        currentPackage = nextPkg
        processedCount++

        storageEnteredForCurrentPkg = false
        cacheClearedForCurrentPkg = !doClearCache
        forceStopClickedForCurrentPkg = !doForceStop
        forceStopConfirmedForCurrentPkg = !doForceStop
        lastActionTimestamp = System.currentTimeMillis()

        updateFloatingHud(nextPkg)

        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", nextPkg, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(intent) }

        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, 800L)
    }

    private fun advanceToNextPackage() {
        if (!isAutomating) return
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed({ processNextPackage() }, 250L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAutomating || currentPackage == null) return
        val rootNode = rootInActiveWindow ?: return

        // 1. Force Stop Confirmation Dialog ("OK" / "Force stop" popup)
        if (doForceStop && forceStopClickedForCurrentPkg && !forceStopConfirmedForCurrentPkg) {
            val confirmBtn = findClickableNodeByText(
                rootNode,
                listOf("OK", "Force stop", "Force Stop", "Yes")
            )
            if (confirmBtn != null) {
                lastActionTimestamp = System.currentTimeMillis()
                confirmBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                forceStopConfirmedForCurrentPkg = true

                if (cacheClearedForCurrentPkg) {
                    advanceToNextPackage()
                }
                return
            }
        }

        // 2. Clear Cache navigation flow
        if (doClearCache && !cacheClearedForCurrentPkg) {
            val clearCacheBtn = findNodeByText(
                rootNode,
                listOf("Clear cache", "Clear Cache", "CLEAR CACHE")
            )

            if (clearCacheBtn != null) {
                val clickable = findClickableParent(clearCacheBtn)
                if (clickable != null && clickable.isEnabled) {
                    lastActionTimestamp = System.currentTimeMillis()
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                cacheClearedForCurrentPkg = true

                if (doForceStop && !forceStopConfirmedForCurrentPkg) {
                    // Navigate back to App Info main page to perform Force Stop
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }, 350L)
                } else {
                    advanceToNextPackage()
                }
                return
            } else if (!storageEnteredForCurrentPkg) {
                val storageEntry = findClickableNodeByText(
                    rootNode,
                    listOf("Storage & cache", "Storage", "Storage usage")
                )
                if (storageEntry != null) {
                    lastActionTimestamp = System.currentTimeMillis()
                    storageEnteredForCurrentPkg = true
                    storageEntry.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
        }

        // 3. Force Stop button click on main App Info page
        if (doForceStop && cacheClearedForCurrentPkg && !forceStopClickedForCurrentPkg) {
            val forceStopNode = findNodeByText(
                rootNode,
                listOf("Force stop", "Force Stop", "FORCE STOP")
            )
            if (forceStopNode != null) {
                val clickable = findClickableParent(forceStopNode)
                if (clickable != null && clickable.isEnabled) {
                    lastActionTimestamp = System.currentTimeMillis()
                    forceStopClickedForCurrentPkg = true
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    // App is already stopped or disabled
                    forceStopClickedForCurrentPkg = true
                    forceStopConfirmedForCurrentPkg = true
                    advanceToNextPackage()
                }
            }
        }
    }

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        targets: List<String>
    ): AccessibilityNodeInfo? {
        for (target in targets) {
            val matches = root.findAccessibilityNodeInfosByText(target)
            val exact = matches?.firstOrNull { node ->
                node.text?.toString()?.equals(target, ignoreCase = true) == true ||
                        node.contentDescription?.toString()?.equals(target, ignoreCase = true) == true
            }
            if (exact != null) return exact
        }
        return null
    }

    private fun findClickableNodeByText(
        root: AccessibilityNodeInfo,
        targets: List<String>
    ): AccessibilityNodeInfo? {
        for (target in targets) {
            val matches = root.findAccessibilityNodeInfosByText(target) ?: continue
            for (node in matches) {
                val clickable = findClickableParent(node)
                if (clickable != null && clickable.isEnabled) {
                    return clickable
                }
            }
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    // ---------------- Floating HUD Overlay ----------------

    private fun showFloatingHud() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE1E1F22"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#44FFFFFF"))
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val modeLabel = when {
            doClearCache && doForceStop -> "Klir • Cache & Hibernation"
            doForceStop -> "Klir • Hibernating Apps"
            else -> "Klir • Clearing App Cache"
        }

        val titleTv = TextView(this).apply {
            text = modeLabel
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }

        val subtitleTv = TextView(this).apply {
            text = "Preparing automation..."
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
        }

        textColumn.addView(titleTv)
        textColumn.addView(subtitleTv)

        val stopButton = Button(this).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D32F2F"))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                stopAutomation(returnToApp = true)
            }
        }

        headerRow.addView(textColumn)
        headerRow.addView(stopButton)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = totalPackages.coerceAtLeast(1)
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        }

        rootLayout.addView(headerRow)
        rootLayout.addView(progress)

        hudTitleText = titleTv
        hudSubtitleText = subtitleTv
        hudProgressBar = progress
        overlayView = rootLayout

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(32)
        }

        runCatching { wm.addView(rootLayout, params) }
    }

    private fun updateFloatingHud(pkgName: String) {
        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(pkgName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkgName)

        hudSubtitleText?.text = "Processing $processedCount / $totalPackages: $appLabel"
        hudProgressBar?.max = totalPackages.coerceAtLeast(1)
        hudProgressBar?.progress = processedCount
    }

    private fun hideFloatingHud() {
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
        hudTitleText = null
        hudSubtitleText = null
        hudProgressBar = null
    }
}