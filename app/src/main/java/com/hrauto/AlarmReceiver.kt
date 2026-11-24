package com.hrauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Scheduler.isEnabled(ctx)) return
        val type = intent.getStringExtra("type") ?: Scheduler.OUT
        Scheduler.scheduleNext(ctx)   // جدولة الوقت اللي بعده
        Scheduler.runNow(ctx, type)
    }
}
