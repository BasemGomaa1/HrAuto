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
        const val MAX_ATTEMPTS = 3          // عدد مرات إعادة الدخول من الأول
        const val MAX_REG_CLICKS = 5        // أقصى عدد ضغطات على زر "تسجيل" كلها
        const val IDLE_LIMIT = 10           // دورات من غير أي تقدم قبل إعادة المحاولة
        const val TICK_MS = 2500L

        // توحيد الحروف: أ إ آ ← ا ، ى ← ي ، ة ← ه ، وشيل التشكيل والمسافات الزايدة
        fun norm(s: CharSequence?): String = (s?.toString() ?: "")
            .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
            .replace(Regex("\\s+"), " ").trim().lowercase()

        // الحروف العربية بس (بيشيل الأرقام والشرطة والمسافات): "1-إنصراف" ← "انصراف"
        fun key(s: CharSequence?): String = norm(s).replace(Regex("[^\\u0621-\\u064A]"), "")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var type = Scheduler.OUT
    private var attempt = 1
    private var idle = 0
    private var regClicks = 0
    private var success = false
    private var locRowClicked = false
    private var typeOpened = false
    private var typeDone = false
    private var typeWait = 0
    private var typeOptWait = 0

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

    private fun resetFlags() {
        idle = 0; locRowClicked = false; typeOpened = false; typeDone = false
        typeWait = 0; typeOptWait = 0
    }

    fun startRun(t: String) {
        handler.removeCallbacksAndMessages(null)
        type = t; running = true; attempt = 1; regClicks = 0; success = false
        resetFlags()
        status("بدأ تشغيل (${Scheduler.label(t)})، بستنى التطبيق يفتح")
        handler.postDelayed(tick, 6000)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (success || hasText(norm(SUCCESS_TEXT))) {
                status("نجح: ظهرت رسالة التسجيل (ضغطات تسجيل: $regClicks)")
                finish(); return
            }
            var acted = false
            var delay = TICK_MS
            val optionKey = if (type == Scheduler.IN) "حضور" else "انصراف"

            if (hasText(norm("اختر الموقع"))) {
                // ===== نافذة اختيار الموقع (علامة صح ثم OK) =====
                if (!locRowClicked) {
                    status("نافذة الموقع: بختار $LOCATION_TEXT")
                    if (alreadyChecked()) { locRowClicked = true; acted = true }
                    else if (clickNodes(keyIs(LOCATION_TEXT))) { locRowClicked = true; acted = true }
                } else {
                    status("نافذة الموقع: بدوس OK")
                    if (clickNodes(exact("ok"))) acted = true
                }
            } else if (find(exact("الموقع")).isNotEmpty()) {
                // ===== شاشة الفورم =====
                if (find(keyIs(LOCATION_TEXT)).isEmpty()) {
                    status("بفتح اختيار الموقع")
                    if (clickNodes(exact("اختر"))) acted = true
                } else if (!typeDone) {
                    if (!typeOpened) {
                        status("بفتح قايمة نوع السجل")
                        if (clickNodes(exact("اختر نوع السجل"))) { typeOpened = true; typeOptWait = 0; acted = true }
                        else if (++typeWait > 3) { typeDone = true; acted = true }
                    } else {
                        status("بختار ${Scheduler.label(type)} من نوع السجل")
                        if (clickNodes(keyIs(optionKey))) { typeDone = true; acted = true }
                        else if (++typeOptWait > 3) { typeOpened = false; typeOptWait = 0 }
                    }
                } else {
                    if (regClicks >= MAX_REG_CLICKS) {
                        status("وقف: دست تسجيل $MAX_REG_CLICKS مرات ومظهرتش رسالة النجاح")
                        finish(); return
                    }
                    if (clickNodes(exact("تسجيل"))) {
                        regClicks++; acted = true; delay = 4000
                        status("دست تسجيل (مرة $regClicks) وبستنى رسالة النجاح")
                    }
                }
            } else if (clickNodes(exact("تسجيل حضور وانصراف"))) {
                // ===== الشاشة الرئيسية =====
                status("دست على تسجيل حضور وانصراف")
                acted = true
            } else {
                status("مش لاقي حاجة أعرفها على الشاشة (دورة $idle)")
            }

            if (acted) idle = 0 else if (++idle > IDLE_LIMIT) { retry(); return }
            handler.postDelayed(this, delay)
        }
    }

    private fun retry() {
        attempt++
        if (attempt > MAX_ATTEMPTS) {
            status("وقف: خلصت المحاولات ومظهرتش رسالة النجاح")
            finish(); return
        }
        resetFlags()
        status("بعيد المحاولة رقم $attempt")
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

    // ===== أدوات البحث في الشاشة =====
    private fun allRoots(): List<AccessibilityNodeInfo> {
        val l = mutableListOf<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { l.add(it) } } catch (_: Exception) {}
        try {
            windows?.forEach { w -> w.root?.let { r -> if (l.none { it == r }) l.add(r) } }
        } catch (_: Exception) {}
        return l
    }

    private fun exact(t: String): (AccessibilityNodeInfo) -> Boolean {
        val nt = norm(t)
        return { norm(it.text) == nt || norm(it.contentDescription) == nt }
    }

    private fun keyIs(t: String): (AccessibilityNodeInfo) -> Boolean {
        val kt = key(t)
        return { kt.isNotEmpty() && (key(it.text) == kt || key(it.contentDescription) == kt) }
    }

    private fun collect(n: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean,
                        out: MutableList<AccessibilityNodeInfo>) {
        if (n.isVisibleToUser && pred(n)) out.add(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, pred, out) }
    }

    private fun find(pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        for (r in allRoots()) collect(r, pred, out)
        return out
    }

    private fun clickNodes(pred: (AccessibilityNodeInfo) -> Boolean): Boolean {
        for (n in find(pred)) {
            var c: AccessibilityNodeInfo? = n
            while (c != null && !c.isClickable) c = c.parent
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun containsText(n: AccessibilityNodeInfo, t: String): Boolean {
        if (norm(n.text).contains(t) || norm(n.contentDescription).contains(t)) return true
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (containsText(c, t)) return true
        }
        return false
    }

    private fun hasText(t: String) = allRoots().any { containsText(it, t) }

    // هل صف "وكيل دمياط" في نافذة الموقع متعلّم صح أصلاً؟
    private fun alreadyChecked(): Boolean {
        val row = find(keyIs(LOCATION_TEXT)).firstOrNull() ?: return false
        var p = row.parent
        repeat(2) {
            if (p == null) return false
            val list = mutableListOf<AccessibilityNodeInfo>()
            checkables(p!!, list)
            if (list.size == 1) return list[0].isChecked
            p = p!!.parent
        }
        return false
    }

    private fun checkables(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (n.isCheckable) out.add(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { checkables(it, out) }
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
                .append("id=${n.viewIdResourceName} click=${n.isClickable} checked=${n.isChecked}\n")
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d + 1, sb) }
    }
}
