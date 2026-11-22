package com.hrauto

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class HrService : AccessibilityService() {

    companion object {
        var instance: HrService? = null
        // الخطوات بالترتيب (لازم تطابق النص في التطبيق بالظبط)
        val STEPS = listOf("تسجيل حضور وانصراف", "وكيل دمياط", "انصراف", "تسجيل")
        const val SUCCESS_TEXT = "تم التسجيل بنجاح"
        const val MAX_ATTEMPTS = 10
        const val TICK_MS = 2500L
        const val STEP_TIMEOUT_TICKS = 8   // لو خطوة ما لقيتهاش في 20 ثانية تعيد من الأول
        const val RESULT_TIMEOUT_TICKS = 3 // بعد الضغط على "تسجيل" يستنى 3 دورات للنتيجة
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var step = 0
    private var ticks = 0
    private var attempt = 1
    private var success = false

    override fun onServiceConnected() { instance = this }

    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!running || e == null) return
        // رسالة Toast بتوصل كـ event
        if (e.text.any { it?.toString()?.contains(SUCCESS_TEXT) == true }) success = true
    }

    fun startRun() {
        handler.removeCallbacksAndMessages(null)
        running = true; step = 0; ticks = 0; attempt = 1; success = false
        handler.postDelayed(tick, 6000) // وقت لتحميل التطبيق
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (success || treeHasSuccess()) { finish(); return }

            if (step < STEPS.size) {
                if (clickText(STEPS[step])) {
                    step++; ticks = 0
                } else if (++ticks > STEP_TIMEOUT_TICKS) {
                    retry(); return
                }
            } else if (++ticks > RESULT_TIMEOUT_TICKS) {
                retry(); return
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun retry() {
        attempt++
        if (attempt > MAX_ATTEMPTS) { finish(); return }
        step = 0; ticks = 0
        performGlobalAction(GLOBAL_ACTION_BACK)
        Scheduler.findLaunchIntent(this)?.let { startActivity(it) }
        handler.postDelayed(tick, 5000)
    }

    private fun finish() {
        running = false
        handler.removeCallbacksAndMessages(null)
        Scheduler.wakeLock?.let { if (it.isHeld) it.release() }
        // قفل الشاشة (الأندرويد 8.1 مفيهوش GLOBAL_ACTION_LOCK_SCREEN فنستخدم Device Admin)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) dpm.lockNow()
    }

    private fun treeHasSuccess(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.findAccessibilityNodeInfosByText(SUCCESS_TEXT).isNotEmpty()
    }

    private fun clickText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(target).filter {
            it.text?.toString()?.trim() == target || it.contentDescription?.toString()?.trim() == target
        }
        for (n in nodes) {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }
}
