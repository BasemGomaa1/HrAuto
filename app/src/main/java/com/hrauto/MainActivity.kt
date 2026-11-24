package com.hrauto

import android.app.Activity
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var timesBox: LinearLayout
    private lateinit var nextTv: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        fun btn(t: String, a: () -> Unit) = root.addView(Button(this).apply {
            text = t; setOnClickListener { a() }
        })

        root.addView(TextView(this).apply { text = "الإعداد (مرة واحدة):"; textSize = 18f })
        btn("1) تفعيل Accessibility (HR Auto)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("2) تفعيل Device Admin (لقفل الشاشة)") {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this@MainActivity, AdminReceiver::class.java))
            })
        }
        btn("3) استثناء من توفير البطارية") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }

        root.addView(TextView(this).apply {
            text = "\nالجدولة:"; textSize = 18f
        })
        root.addView(Switch(this).apply {
            text = "تشغيل الجدولة  "
            textSize = 18f
            isChecked = Scheduler.isEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Scheduler.setEnabled(this@MainActivity, on)
                Scheduler.scheduleNext(this@MainActivity)
                refresh()
            }
        })
        nextTv = TextView(this).apply { textSize = 15f; setPadding(0, 10, 0, 10) }
        root.addView(nextTv)

        timesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(timesBox)

        btn("+ إضافة وقت") {
            val c = Calendar.getInstance()
            TimePickerDialog(this, TimePickerDialog.OnTimeSetListener { _, h, m ->
                Scheduler.saveTimes(this, Scheduler.getTimes(this) + (h * 60 + m))
                Scheduler.scheduleNext(this)
                refresh()
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }

        btn("تجربة الآن") {
            if (Scheduler.findLaunchIntent(this) == null)
                Toast.makeText(this, "مش لاقي تطبيق اسمه ${Scheduler.APP_LABEL}", Toast.LENGTH_LONG).show()
            else if (HrService.instance == null)
                Toast.makeText(this, "فعّل Accessibility الأول", Toast.LENGTH_LONG).show()
            else Scheduler.runNow(this)
        }

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    private fun refresh() {
        timesBox.removeAllViews()
        for (m in Scheduler.getTimes(this)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = fmt(m); textSize = 22f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "حذف"
                setOnClickListener {
                    Scheduler.saveTimes(this@MainActivity, Scheduler.getTimes(this@MainActivity) - m)
                    Scheduler.scheduleNext(this@MainActivity)
                    refresh()
                }
            })
            timesBox.addView(row)
        }
        val next = Scheduler.nextRunMillis(this)
        nextTv.text = if (Scheduler.isEnabled(this) && next != null)
            "التشغيل القادم: " + SimpleDateFormat("EEE yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(next))
        else "الجدولة متوقفة"
    }
}
