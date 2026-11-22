package com.hrauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.scheduleNext(ctx)   // جدولة اليوم التالي
        Scheduler.runNow(ctx)
    }
}
