package com.hrauto

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun btn(t: String, a: () -> Unit) = l.addView(Button(this).apply { text = t; setOnClickListener { a() } })

        l.addView(TextView(this).apply {
            text = "نفّذ الخطوات بالترتيب:"; textSize = 18f
        })
        btn("1) تفعيل Accessibility (HR Auto)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("2) تفعيل Device Admin (لقفل الشاشة)") {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, AdminReceiver::class.java))
            })
        }
        btn("3) استثناء من توفير البطارية") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }
        btn("4) جدولة التشغيل (${Scheduler.START_HOUR}:%02d)".format(Scheduler.START_MIN)) {
            Scheduler.scheduleNext(this)
            Toast.makeText(this, "تمت الجدولة", Toast.LENGTH_SHORT).show()
        }
        btn("تجربة الآن") {
            if (Scheduler.findLaunchIntent(this) == null)
                Toast.makeText(this, "مش لاقي تطبيق اسمه ${Scheduler.APP_LABEL}", Toast.LENGTH_LONG).show()
            else if (HrService.instance == null)
                Toast.makeText(this, "فعّل Accessibility الأول", Toast.LENGTH_LONG).show()
            else Scheduler.runNow(this)
        }
        setContentView(l)
    }
}
