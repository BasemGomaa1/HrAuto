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
    const val APP_LABEL = "Login Hr"            // اسم التطبيق زي ما بيظهر في الموبايل
    const val START_HOUR = 4                    // الساعة (24 ساعة)
    const val START_MIN = 0
    val SKIP_DAYS = setOf(Calendar.FRIDAY)      // أيام ما يشتغلش فيها (احذفها لو عايزه كل يوم)
    // ================================

    var wakeLock: PowerManager.WakeLock? = null

    fun scheduleNext(ctx: Context) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, START_HOUR)
            set(Calendar.MINUTE, START_MIN)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        while (cal.get(Calendar.DAY_OF_WEEK) in SKIP_DAYS) cal.add(Calendar.DAY_OF_YEAR, 1)

        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Build.VERSION.SDK_INT >= 23)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        else
            am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
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
