package com.gameturbo

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class TurboService : Service() {
    companion object { @Volatile var running = false }

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }
    private var handle: View? = null
    private var panel: LinearLayout? = null
    private val tiles = HashMap<String, TextView>()
    private var open = false
    private var lastClose = 0L
    private var game: String? = null
    private var dndOn = false
    private var oldFilter = NotificationManager.INTERRUPTION_FILTER_ALL
    private var brightLocked = false
    private var oldBrightMode = 1
    private var autoClean = false
    private val autoTask = object : Runnable {
        override fun run() {
            Cleaner.cleanRam(this@TurboService, game)
            h.postDelayed(this, 180_000)
        }
    }
    private val handleLp by lazy {
        lp(false).apply { width = (16 * dp).toInt(); height = (110 * dp).toInt(); y = (350 * dp).toInt() }
    }
    private val panelLp by lazy { lp(true).apply { x = (20 * dp).toInt() } }

    private fun lp(isPanel: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            (if (isPanel) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.END }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        running = true
        game = i?.getStringExtra("game")
        startForeground(1, notif())
        if (handle == null) {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            oldBrightMode = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 1)
            Cleaner.cleanRam(this, game)
            if (Settings.canDrawOverlays(this)) { makePanel(); makeHandle() }
        }
        return START_NOT_STICKY
    }

    private fun round(c: Int, r: Float) = GradientDrawable().apply { setColor(c); cornerRadius = r }

    private fun makeHandle() {
        val v = View(this)
        v.background = round(Color.parseColor("#B300E676"), 12 * dp)
        var dx0 = 0f; var dy0 = 0f; var y0 = 0
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY; y0 = handleLp.y }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dx0; val dy = e.rawY - dy0
                    if (dx < -30 * dp) openPanel()
                    else if (abs(dy) > 8 * dp && !open) {
                        handleLp.y = (y0 + dy.toInt()).coerceIn(0, resources.displayMetrics.heightPixels - handleLp.height)
                        wm.updateViewLayout(view, handleLp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val tap = abs(e.rawX - dx0) < 10 * dp && abs(e.rawY - dy0) < 10 * dp
                    if (tap && SystemClock.uptimeMillis() - lastClose > 400) { if (open) closePanel() else openPanel() }
                }
            }
            true
        }
        wm.addView(v, handleLp)
        handle = v
    }

    private fun tile(key: String, label: String, act: () -> Unit): TextView {
        val m = (4 * dp).toInt(); val p = (12 * dp).toInt()
        val t = TextView(this)
        t.text = label; t.textSize = 12f; t.gravity = Gravity.CENTER
        t.setTextColor(Color.WHITE); t.setPadding(p, p, p, p)
        t.background = round(Color.parseColor("#33FFFFFF"), 16 * dp)
        t.layoutParams = LinearLayout.LayoutParams((110 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(m, m, m, m) }
        t.setOnClickListener { act() }
        tiles[key] = t
        return t
    }

    private fun setOn(key: String, on: Boolean) {
        tiles[key]?.background = round(if (on) Color.parseColor("#CC00C853") else Color.parseColor("#33FFFFFF"), 16 * dp)
    }

    private fun makePanel() {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        val pad = (8 * dp).toInt()
        p.setPadding(pad, pad, pad, pad)
        p.background = round(Color.parseColor("#EE12121A"), 24 * dp)
        p.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) closePanel(); false }
        fun row(a: TextView, b: TextView? = null) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.addView(a); if (b != null) r.addView(b)
            p.addView(r)
        }
        row(tile("turbo", "⚡ Turbo total") { turboTotal() }, tile("dnd", "🔕 Não Perturbe") { toggleDnd() })
        row(tile("ram", "🧹 Limpar RAM") { cleanRamNow() }, tile("cache", "🗑 Limpar cache") { cleanCacheNow() })
        row(tile("bright", "🔒 Travar brilho") { toggleBright() }, tile("auto", "🔁 Limpeza auto") { toggleAuto() })
        row(tile("close", "✖ Fechar turbo") { stopSelf() })
        panel = p
    }

    private fun openPanel() {
        if (open) return
        val p = panel ?: return
        panelLp.y = handleLp.y.coerceAtMost((resources.displayMetrics.heightPixels * 0.3).toInt())
        wm.addView(p, panelLp)
        open = true
    }

    private fun closePanel() {
        if (!open) return
        panel?.let { runCatching { wm.removeView(it) } }
        open = false
        lastClose = SystemClock.uptimeMillis()
    }

    private fun turboTotal() {
        if (!dndOn) toggleDnd()
        if (!brightLocked) toggleBright()
        if (!autoClean) toggleAuto()
        toast("Turbo total ativado")
    }

    private fun toggleDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) { toast("Libere o Não Perturbe no app"); return }
        if (dndOn) nm.setInterruptionFilter(oldFilter)
        else { oldFilter = nm.currentInterruptionFilter; nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE) }
        dndOn = !dndOn
        setOn("dnd", dndOn)
    }

    private fun toggleBright() {
        if (!Settings.System.canWrite(this)) { toast("Libere 'Modificar configurações' no app"); return }
        val cr = contentResolver
        if (brightLocked) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, oldBrightMode)
        else {
            oldBrightMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 1)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        }
        brightLocked = !brightLocked
        setOn("bright", brightLocked)
    }

    private fun toggleAuto() {
        autoClean = !autoClean
        if (autoClean) h.post(autoTask) else h.removeCallbacks(autoTask)
        setOn("auto", autoClean)
    }

    private fun cleanRamNow() {
        val before = Cleaner.freeRamMb(this)
        Cleaner.cleanRam(this, game)
        h.postDelayed({ toast("RAM livre: $before → ${Cleaner.freeRamMb(this)} MB") }, 1000)
    }

    private fun cleanCacheNow() {
        toast("Limpando…")
        Thread {
            val mb = Cleaner.cleanStorage(this)
            h.post { toast("Liberado: $mb MB de cache") }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun notif(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("turbo", "Game Turbo", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "turbo")
            .setContentTitle("Game Turbo ativo").setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        if (::wm.isInitialized) {
            closePanel()
            handle?.let { runCatching { wm.removeView(it) } }
        }
        if (dndOn) getSystemService(NotificationManager::class.java).setInterruptionFilter(oldFilter)
        if (brightLocked && Settings.System.canWrite(this)) {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, oldBrightMode)
        }
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
