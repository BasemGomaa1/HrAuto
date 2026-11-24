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
        const val SUCCESS_TEXT = "تم التسجيل بنجاح"
        const val LOCATION_TEXT = "وكيل دمياط"
        const val MAX_ATTEMPTS = 5
        const val TICK_MS = 2500L
        const val STEP_TIMEOUT_TICKS = 8
        const val RESULT_TIMEOUT_TICKS = 3

        // توحيد الحروف: أ إ آ ← ا ، ى ← ي ، ة ← ه ، وشيل التشكيل والمسافات الزايدة
        fun norm(s: CharSequence?): String = (s?.toString() ?: "")
            .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
            .replace(Regex("\\s+"), " ").trim()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var steps: List<String> = emptyList()
    private var step = 0
    private var ticks = 0
    private var attempt = 1
    private var success = false

    private fun prefs() = getSharedPreferences("hrauto", Context.MODE_PRIVATE)
    private fun status(s: String) = prefs().edit().putString("status", s).apply()

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!running || e == null) return
        val s = norm(SUCCESS_TEXT)
        if (e.text.any { norm(it).contains(s) }) success = true
    }

    fun startRun(type: String) {
        handler.removeCallbacksAndMessages(null)
        steps = listOf(
            "تسجيل حضور وانصراف",                     // الزر في الشاشة الرئيسية
            "إختر",                                    // خانة الموقع
            LOCATION_TEXT,                             // وكيل دمياط
            "إختر نوع السجل",                          // خانة نوع السجل
            if (type == Scheduler.IN) "حضور" else "انصراف",
            "تسجيل"                                    // زر التسجيل
        )
        running = true; step = 0; ticks = 0; attempt = 1; success = false
        status("بدأ تشغيل (${Scheduler.label(type)})، بستنى التطبيق يفتح")
        handler.postDelayed(tick, 6000)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (success || treeHasSuccess()) { status("نجح: ظهرت رسالة التسجيل"); finish(); return }

            if (step < steps.size) {
                status("محاولة $attempt: بدور على \"${steps[step]}\" (خطوة ${step + 1} من ${steps.size}، دورة $ticks)")
                if (clickText(steps[step])) {
                    step++; ticks = 0
                } else if (++ticks > STEP_TIMEOUT_TICKS) {
                    retry(); return
                }
            } else {
                status("محاولة $attempt: دست تسجيل وبستنى رسالة النجاح (دورة $ticks)")
                if (++ticks > RESULT_TIMEOUT_TICKS) { retry(); return }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun retry() {
        attempt++
        if (attempt > MAX_ATTEMPTS) {
            status("وقف: خلصت $MAX_ATTEMPTS محاولات من غير ما تظهر رسالة النجاح")
            finish(); return
        }
        step = 0; ticks = 0
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 700)
        handler.postDelayed({ Scheduler.findLaunchIntent(this)?.let { startActivity(it) } }, 2000)
        handler.postDelayed(tick, 7000)
    }

    private fun finish() {
        running = false
        handler.removeCallbacksAndMessages(null)
        Scheduler.wakeLock?.let { if (it.isHeld) it.release() }
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) dpm.lockNow()
    }

    // كل النوافذ الظاهرة (بما فيها القوايم المنسدلة)
    private fun allRoots(): List<AccessibilityNodeInfo> {
        val l = mutableListOf<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { l.add(it) } } catch (_: Exception) {}
        try {
            windows?.forEach { w -> w.root?.let { r -> if (l.none { it == r }) l.add(r) } }
        } catch (_: Exception) {}
        return l
    }

    private fun collect(n: AccessibilityNodeInfo, t: String, out: MutableList<AccessibilityNodeInfo>) {
        if (n.isVisibleToUser && (norm(n.text) == t || norm(n.contentDescription) == t)) out.add(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, t, out) }
    }

    private fun hasText(n: AccessibilityNodeInfo, t: String): Boolean {
        if (norm(n.text).contains(t) || norm(n.contentDescription).contains(t)) return true
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (hasText(c, t)) return true
        }
        return false
    }

    private fun treeHasSuccess(): Boolean {
        val t = norm(SUCCESS_TEXT)
        return allRoots().any { hasText(it, t) }
    }

    private fun clickText(target: String): Boolean {
        val t = norm(target)
        for (root in allRoots()) {
            val found = mutableListOf<AccessibilityNodeInfo>()
            collect(root, t, found)
            for (n in found) {
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    // ===== أداة الفحص =====
    fun dumpScreenDelayed(delayMs: Long) { handler.postDelayed({ dump() }, delayMs) }

    private fun dump() {
        val roots = allRoots()
        val sb = StringBuilder()
        if (roots.isEmpty()) sb.append("مفيش نوافذ (الخدمة مش شايفة الشاشة)")
        roots.forEachIndexed { i, r ->
            sb.append("--- نافذة ${i + 1} | package: ${r.packageName}\n")
            walk(r, 0, sb)
        }
        prefs().edit().putString("dump", sb.toString()).apply()
    }

    private fun walk(n: AccessibilityNodeInfo, d: Int, sb: StringBuilder) {
        if (n.text != null || n.contentDescription != null || n.isClickable) {
            sb.append("  ".repeat(d))
                .append("[${n.className?.toString()?.substringAfterLast('.')}] ")
                .append("text=\"${n.text}\" desc=\"${n.contentDescription}\" ")
                .append("id=${n.viewIdResourceName} click=${n.isClickable}\n")
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1, sb) }
    }
}
