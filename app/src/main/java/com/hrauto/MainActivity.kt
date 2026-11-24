package com.hrauto

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
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
    private lateinit var svcTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var dumpTv: TextView

    private fun prefs() = getSharedPreferences("hrauto", Context.MODE_PRIVATE)

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

        root.addView(TextView(this).apply { text = "\nالجدولة:"; textSize = 18f })
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
                AlertDialog.Builder(this)
                    .setTitle("نوع التسجيل")
                    .setItems(arrayOf("حضور", "انصراف")) { _, which ->
                        val type = if (which == 0) Scheduler.IN else Scheduler.OUT
                        val min = h * 60 + m
                        Scheduler.saveSlots(this,
                            Scheduler.getSlots(this).filter { it.minutes != min } + Slot(min, type))
                        Scheduler.scheduleNext(this)
                        refresh()
                    }.show()
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }

        fun test(type: String) {
            if (Scheduler.findLaunchIntent(this) == null)
                Toast.makeText(this, "مش لاقي تطبيق اسمه ${Scheduler.APP_LABEL}", Toast.LENGTH_LONG).show()
            else if (HrService.instance == null)
                Toast.makeText(this, "فعّل Accessibility الأول", Toast.LENGTH_LONG).show()
            else Scheduler.runNow(this, type)
        }
        btn("تجربة الآن: حضور") { test(Scheduler.IN) }
        btn("تجربة الآن: انصراف") { test(Scheduler.OUT) }
        btn("⏹ إيقاف التشغيل الحالي") {
            val svc = HrService.instance
            if (svc == null || !svc.isRunning())
                Toast.makeText(this, "مفيش تشغيل شغال دلوقتي", Toast.LENGTH_SHORT).show()
            else {
                svc.stopRun()
                Toast.makeText(this, "اتوقف", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }

        // ===== الفحص =====
        root.addView(TextView(this).apply { text = "\nالفحص:"; textSize = 18f })
        svcTv = TextView(this).apply { textSize = 16f }
        root.addView(svcTv)
        statusTv = TextView(this).apply { textSize = 14f; setPadding(0, 8, 0, 8) }
        root.addView(statusTv)

        btn("فحص شاشة Login Hr (بعد 10 ثواني)") {
            val svc = HrService.instance
            if (svc == null) {
                Toast.makeText(this, "فعّل Accessibility الأول", Toast.LENGTH_LONG).show()
            } else {
                prefs().edit().remove("dump").apply()
                Scheduler.findLaunchIntent(this)?.let { startActivity(it) }
                svc.dumpScreenDelayed(10000)
                Toast.makeText(this, "وصّل للشاشة المطلوبة، وبعد 10 ثواني ارجع لـ HR Auto", Toast.LENGTH_LONG).show()
            }
        }
        btn("نسخ نتيجة الفحص") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("dump", prefs().getString("dump", "") ?: ""))
            Toast.makeText(this, "اتنسخت", Toast.LENGTH_SHORT).show()
        }
        dumpTv = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(dumpTv)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    private fun refresh() {
        timesBox.removeAllViews()
        for (s in Scheduler.getSlots(this)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = "${fmt(s.minutes)}  —  ${Scheduler.label(s.type)}"; textSize = 20f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "حذف"
                setOnClickListener {
                    Scheduler.saveSlots(this@MainActivity,
                        Scheduler.getSlots(this@MainActivity).filter { it.minutes != s.minutes })
                    Scheduler.scheduleNext(this@MainActivity)
                    refresh()
                }
            })
            timesBox.addView(row)
        }
        val next = Scheduler.nextRun(this)
        nextTv.text = if (Scheduler.isEnabled(this) && next != null)
            "التشغيل القادم: " + SimpleDateFormat("EEE yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(next.first)) +
                " (${Scheduler.label(next.second.type)})"
        else "الجدولة متوقفة"

        val svc = HrService.instance
        svcTv.text = "Accessibility: " + (if (svc != null) "شغالة ✅" else "مش شغالة ❌") +
            (if (svc?.isRunning() == true) "\nالتشغيل: شغال الآن 🔄" else "\nالتشغيل: واقف")
        statusTv.text = "آخر حالة: " + (prefs().getString("status", "-") ?: "-")
        dumpTv.text = prefs().getString("dump", "") ?: ""
    }
}
