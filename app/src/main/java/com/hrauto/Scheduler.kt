package com.hrauto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import java.util.Calendar

data class Slot(val minutes: Int, val type: String)

object Scheduler {
    // ===== إعدادات تقدر تعدلها =====
    const val APP_LABEL = "Login Hr"                 // اسم التطبيق زي ما بيظهر في الموبايل
    val SKIP_DAYS: Set<Int> = emptySet()             // مثال: setOf(Calendar.FRIDAY)
    // ================================

    const val IN = "IN"
    const val OUT = "OUT"
    fun label(type: String) = if (type == IN) "حضور" else "انصراف"

    var wakeLock: PowerManager.WakeLock? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hrauto", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("enabled", v).apply()

    // الأوقات بتتخزن: دقايق من بداية اليوم + النوع. الافتراضي 8:00 حضور و 16:00 انصراف
    fun getSlots(ctx: Context): List<Slot> =
        (prefs(ctx).getString("slots", "480:IN,960:OUT") ?: "")
            .split(",").mapNotNull { p ->
                val parts = p.split(":")
                val m = parts[0].toIntOrNull() ?: return@mapNotNull null
                Slot(m, if (parts.getOrNull(1) == IN) IN else OUT)
            }.distinctBy { it.minutes }.sortedBy { it.minutes }

    fun saveSlots(ctx: Context, list: List<Slot>) {
        val s = list.distinctBy { it.minutes }.sortedBy { it.minutes }
            .joinToString(",") { "${it.minutes}:${it.type}" }
        prefs(ctx).edit().putString("slots", s).apply()
    }

    fun nextRun(ctx: Context): Pair<Long, Slot>? {
        val now = System.currentTimeMillis()
        return getSlots(ctx).map { s ->
            val t = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, s.minutes / 60)
                set(Calendar.MINUTE, s.minutes % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
                while (get(Calendar.DAY_OF_WEEK) in SKIP_DAYS) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            t to s
        }.minByOrNull { it.first }
    }

    private fun alarmIntent(ctx: Context, type: String) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, AlarmReceiver::class.java).putExtra("type", type),
        PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(ctx, OUT))
        if (!isEnabled(ctx)) return
        val next = nextRun(ctx) ?: return
        val pi = alarmIntent(ctx, next.second.type)
        if (Build.VERSION.SDK_INT >= 23)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.first, pi)
        else
            am.setExact(AlarmManager.RTC_WAKEUP, next.first, pi)
    }

    fun findLaunchIntent(ctx: Context): Intent? {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(main, 0).firstOrNull {
            it.loadLabel(pm).toString().trim().equals(APP_LABEL, ignoreCase = true)
        } ?: return null
        return pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    @Suppress("DEPRECATION")
    fun runNow(ctx: Context, type: String) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "hrauto:wake"
        ).also { it.acquire(5 * 60 * 1000L) }

        findLaunchIntent(ctx)?.let { ctx.startActivity(it) }
        val svc = HrService.instance
        if (svc == null)
            prefs(ctx).edit().putString("status", "الخدمة (Accessibility) مش شغالة وقت التشغيل").apply()
        else svc.startRun(type)
    }
}
