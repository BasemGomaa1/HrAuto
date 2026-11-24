package com.hrauto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import java.util.Calendar

object Scheduler {
    // ===== إعدادات تقدر تعدلها =====
    const val APP_LABEL = "Login Hr"                 // اسم التطبيق زي ما بيظهر في الموبايل
    val SKIP_DAYS: Set<Int> = emptySet()             // مثال: setOf(Calendar.FRIDAY) لاستثناء الجمعة
    // ================================

    var wakeLock: PowerManager.WakeLock? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hrauto", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("enabled", v).apply()

    // الأوقات بتتخزن كدقايق من بداية اليوم (4:00 = 240)
    fun getTimes(ctx: Context): List<Int> =
        (prefs(ctx).getString("times", "240") ?: "")
            .split(",").mapNotNull { it.toIntOrNull() }.distinct().sorted()

    fun saveTimes(ctx: Context, list: List<Int>) =
        prefs(ctx).edit().putString("times", list.distinct().sorted().joinToString(",")).apply()

    fun nextRunMillis(ctx: Context): Long? {
        val now = System.currentTimeMillis()
        return getTimes(ctx).map { m ->
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, m / 60)
                set(Calendar.MINUTE, m % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
                while (get(Calendar.DAY_OF_WEEK) in SKIP_DAYS) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }.minOrNull()
    }

    private fun alarmIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT
    )

    // بيلغي أي منبه قديم، ولو الجدولة شغالة بيضبط المنبه على أقرب وقت جاي
    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(ctx)
        am.cancel(pi)
        if (!isEnabled(ctx)) return
        val t = nextRunMillis(ctx) ?: return
        if (Build.VERSION.SDK_INT >= 23)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        else
            am.setExact(AlarmManager.RTC_WAKEUP, t, pi)
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
    fun runNow(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "hrauto:wake"
        ).also { it.acquire(5 * 60 * 1000L) }

        findLaunchIntent(ctx)?.let { ctx.startActivity(it) }
        HrService.instance?.startRun()
    }
}
