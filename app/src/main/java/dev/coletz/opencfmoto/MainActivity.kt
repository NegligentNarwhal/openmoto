package dev.coletz.opencfmoto

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusValue: TextView
    private lateinit var statusBlink: View
    private lateinit var prober: EasyConnProber
    private var bleWakeUp: BleWakeUp? = null
    private var blinkAnim: ObjectAnimator? = null
    private var cursorAnim: ObjectAnimator? = null
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Drives the SYS readout: IDLE = dim square, BUSY = amber blinking, LIVE = green solid. */
    private enum class Sys { IDLE, BUSY, LIVE, FAIL }

    /** Which mode button is engaged; its corner LED mirrors the SYS color. */
    private enum class Mode { AA, MIRROR }
    private var activeMode: Mode? = null
    private lateinit var btnAa: PunchButton
    private lateinit var btnMirror: PunchButton
    private lateinit var btnStop: Button
    private var csController: AaSurfaceController? = null
    /** True when the pending QR scan should kick off the Android Auto flow (vs the mirror path). */
    private var pendingAaStart = false

    /** True when the pending QR scan only pairs the bike (PAIR button) — no connect afterwards. */
    private var pendingPairOnly = false

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) {
            log("QR scan cancelled")
            pendingPairOnly = false
            setStatus("IDLE", Sys.IDLE)
            return@registerForActivityResult
        }
        log("QR raw: $raw")
        val qr = QrData.parse(raw)
        if (qr == null) {
            log("QR parse FAILED — missing ssid/pwd?")
            Toast.makeText(this, "Invalid QR", Toast.LENGTH_SHORT).show()
            pendingPairOnly = false
            setStatus("IDLE", Sys.IDLE)
            return@registerForActivityResult
        }
        log(
            "QR parsed: ssid=${qr.ssid} mac=${qr.mac} action=${qr.action} " +
                "(ap=${qr.supportsAp}, p2p=${qr.supportsP2p}) modelId=${qr.modelId} sn=${qr.sn}"
        )
        val wasPairOnly = pendingPairOnly
        pendingPairOnly = false
        // PAIR flow just remembers the bike (rider picks ANDROID AUTO or MIRROR next); the
        // Start-then-scan flow connects straight away.
        onBikePaired(qr, connectAfter = !wasPairOnly)
    }

    /**
     * Shared post-pairing path for both the QR scan and the manual Wi-Fi entry: remember the bike,
     * refresh the UI, then either connect immediately or stop and let the rider choose a mode.
     */
    private fun onBikePaired(qr: QrData, connectAfter: Boolean) {
        SavedBike.save(this, qr)
        log("bike remembered: ${SavedBike.displayLabel(this, qr)} — from now on Start connects without the QR.")
        refreshBikeUi()
        if (!connectAfter) {
            log("paired — choose ANDROID AUTO or MIRROR to connect.")
            setStatus("IDLE", Sys.IDLE)
            return
        }
        connectToBike(qr, remembered = false)
    }

    /**
     * The one connect path, shared by the QR scan and the remembered bike.
     * [remembered] only affects error handling: a saved bike whose Wi-Fi we can't join is stale,
     * so we drop it and fall back to the scanner.
     */
    private fun connectToBike(qr: QrData, remembered: Boolean) {
        setStatus("LINKING", Sys.BUSY)
        // Pick the bike profile from the QR modelId up front — it drives the Android Auto
        // resolution/orientation, which must be set before AA starts. CLIENT_INFO refines it later.
        BikeProfileHolder.active = BikeProfiles.selectByModelId(qr.modelId)
        val spec = BikeProfileHolder.active.aaVideo
        log("→ bike profile (QR modelId=${qr.modelId}): ${BikeProfileHolder.active.name} " +
            "→ AA ${spec.width}x${spec.height} @${spec.dpi}dpi")

        if (pendingAaStart) {
            pendingAaStart = false
            log("→ starting Android Auto receiver (loopback self-mode). Ensure Android Auto is installed & set up.")
            // Once AA video is steady, join the bike Wi-Fi and run the PXC handshake.
            AaVideoBridge.onSteadyVideo = {
                runOnUiThread {
                    AaVideoBridge.onSteadyVideo = null
                    log("→ Android Auto video is live — joining bike Wi-Fi")
                    // Pipeline is up now — make sure the compact preview is attached (its initial
                    // surface-callback attach can be missed amid the Wi-Fi-join lifecycle churn).
                    csController?.reattach()
                    joinAndStart(qr, remembered)
                }
            }
            AndroidAutoService.start(this)
            // Trigger Google AA to project from the FOREGROUND activity (background-activity-launch
            // safe on Android 12+/15), after giving the service's :5288 server time to bind.
            logView.postDelayed({
                dev.coletz.opencfmoto.aa.AaSelfMode.trigger(this, log = ::log)
            }, 900)
        } else {
            // Mirror path (screen projection already armed), or a re-join with Android Auto already
            // live: connect straight away.
            joinAndStart(qr, remembered)
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            log("screen-capture consent declined")
            setStatus("IDLE", Sys.IDLE)
            return@registerForActivityResult
        }
        // FGS of type mediaProjection must be RUNNING before getMediaProjection() on API 34+.
        // startForegroundService is async, so poll the service's foreground flag (~every 100ms)
        // instead of guessing a fixed delay.
        ProjectionService.start(this)
        val code = result.resultCode
        val data = result.data!!
        val maxTries = 50  // 50 * 100ms = 5s ceiling
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                if (ProjectionService.isForeground) {
                    try {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        ProjectionHolder.projection = mpm.getMediaProjection(code, data)
                        val saved = SavedBike.load(this@MainActivity)
                        if (saved != null) {
                            log("screen-capture armed (FGS up after ${tries * 100}ms) — mirroring to remembered bike ${SavedBike.displayLabel(this@MainActivity, saved)}")
                            connectToBike(saved, remembered = true)
                        } else {
                            log("screen-capture armed (FGS up after ${tries * 100}ms) — now scan the QR")
                            setStatus("SCAN QR", Sys.BUSY)
                            scanLauncher.launch(Intent(this@MainActivity, QrScanActivity::class.java))
                        }
                    } catch (e: Exception) {
                        log("getMediaProjection failed: $e")
                        ProjectionService.stop(this@MainActivity)
                        setStatus("IDLE", Sys.IDLE)
                    }
                } else if (tries++ < maxTries) {
                    logView.postDelayed(this, 100)
                } else {
                    log("foreground service did not start within 5s — aborting mirror")
                    ProjectionService.stop(this@MainActivity)
                    setStatus("IDLE", Sys.IDLE)
                }
            }
        }
        logView.post(poll)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // Keep the layout's own padding on top of the system-bar insets.
        val padH = (18 * resources.displayMetrics.density).toInt()
        val padV = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + padH, b.top + padV, b.right + padH, b.bottom + padV)
            insets
        }

        logView = findViewById(R.id.log_view)
        logScroll = findViewById(R.id.log_scroll)
        statusValue = findViewById(R.id.status_value)
        statusBlink = findViewById(R.id.status_blink)
        btnAa = findViewById(R.id.btn_aa_start)
        btnMirror = findViewById(R.id.btn_mirror_start)
        btnStop = findViewById(R.id.btn_aa_stop)
        logView.movementMethod = ScrollingMovementMethod()

        findViewById<TextView>(R.id.header_version).text =
            "V" + (packageManager.getPackageInfo(packageName, 0).versionName ?: "?")

        // Terminal cursor: square-wave blink (snap, not fade) at the end of the log.
        cursorAnim = ObjectAnimator.ofFloat(
            findViewById(R.id.log_cursor), View.ALPHA, 1f, 1f, 0f, 0f
        ).apply {
            duration = 1060
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        setLogVisible(
            getSharedPreferences("ui", MODE_PRIVATE).getBoolean("log_visible", true),
            animate = false,
        )
        findViewById<Button>(R.id.btn_toggle_log).setOnClickListener {
            setLogVisible(logScroll.visibility != View.VISIBLE, animate = true)
        }

        findViewById<Button>(R.id.btn_rename_bike).setOnClickListener { showRenameDialog() }

        // First-run flow: no bike remembered yet — PAIR scans the QR and stops there; the
        // mode buttons appear once a bike is saved.
        findViewById<PunchButton>(R.id.btn_pair).setOnClickListener {
            pendingAaStart = false
            pendingPairOnly = true
            setStatus("SCAN QR", Sys.BUSY)
            try {
                scanLauncher.launch(Intent(this, QrScanActivity::class.java))
            } catch (e: Exception) {
                log("scan launch failed ($e)")
                pendingPairOnly = false
                setStatus("IDLE", Sys.IDLE)
            }
        }

        // No-QR fallback: some bikes broadcast a Wi-Fi hotspot without a pairing QR (e.g. some US
        // 675s). Let the rider type in the SSID/password their own bike uses.
        findViewById<Button>(R.id.btn_manual_entry).setOnClickListener { showManualEntryDialog() }

        // All components (bike PXC, Android Auto receiver, video pipeline — including those
        // running in the foreground service) log through LogBus; mirror it into the view.
        LogBus.listener = { line ->
            runOnUiThread {
                logView.append("$line\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        prober = EasyConnProber(applicationContext, ::log)

        // Android 13+: request notification permission up front so the mediaProjection
        // foreground-service notification can be posted (some setups gate the FGS on it).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3,
            )
        }

        // Android Auto receiver runs in its own foreground service so it survives lock/background.
        btnAa.setOnClickListener {
            pendingAaStart = true
            ProjectionHolder.projection = null   // bike uses the AA pipeline, not mirror
            ensureLocationPermission()
            activeMode = Mode.AA
            setStatus("ARMING", Sys.BUSY)
            refreshControlButton()

            val saved = SavedBike.load(this)
            if (saved != null) {
                log("→ Android Auto: using remembered bike ${SavedBike.displayLabel(this, saved)} (Forget Bike to re-scan).")
                connectToBike(saved, remembered = true)
            } else {
                // Scan the bike QR FIRST: its modelId picks the bike profile, which sets the Android
                // Auto resolution/orientation. AA can't change resolution mid-session, so the profile
                // must be known before AA starts. After AA video is steady we join the bike Wi-Fi and
                // run the PXC handshake (which confirms the profile from CLIENT_INFO). See scanLauncher.
                log("→ Android Auto: scan the bike QR first so we pick the right screen profile.")
                try {
                    setStatus("SCAN QR", Sys.BUSY)
                    scanLauncher.launch(Intent(this, QrScanActivity::class.java))
                } catch (e: Exception) {
                    log("scan launch failed ($e)")
                    pendingAaStart = false
                    setStatus("IDLE", Sys.IDLE)
                }
            }
        }

        // Mirror the whole phone screen to the dash. Uses the same PXC video pipeline as Android
        // Auto, but the source is a MediaProjection of the device screen (VideoPipeline switches to
        // full-screen mirror mode when ProjectionHolder.projection is set). Lets the rider put ANY
        // app on the dash — set the nav app to LANDSCAPE first, or it shows pillar-boxed portrait.
        btnMirror.setOnClickListener {
            pendingAaStart = false                 // mirror path, not the AA loopback
            activeMode = Mode.MIRROR
            // Ensure no AA session is holding the shared pipeline, or the prober would reuse it
            // instead of building a mirror source. onDestroy nulls AaVideoBridge.pipeline; clear it
            // now too so there's no race with the imminent REQ_RV_DATA_START.
            AndroidAutoService.stop(this)
            AaVideoBridge.pipeline = null
            ensureLocationPermission()
            setStatus("ARMING", Sys.BUSY)
            refreshControlButton()   // hide the AA control surface entry in mirror mode
            log("→ Mirror: TIP — set your nav app to LANDSCAPE for a full-screen picture " +
                "(portrait shows with black side bars). The phone screen must stay on while riding.")
            // Ask for screen-capture consent first; projectionLauncher then joins the bike (saved or
            // via QR) and starts streaming the mirror.
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            } catch (e: Exception) {
                log("mirror launch failed ($e)")
                setStatus("IDLE", Sys.IDLE)
            }
        }

        findViewById<Button>(R.id.btn_forget_bike).setOnClickListener {
            SavedBike.clear(this)
            log("bike forgotten — the next connect will scan the QR again.")
            refreshBikeUi()
        }
        refreshBikeUi()
        // Stop everything: Android Auto receiver, screen mirror, bike PXC, and leave the bike Wi-Fi.
        btnStop.setOnClickListener {
            log("→ stopping everything (Android Auto / Mirror + bike)")
            teardownSession()
            setStatus("IDLE", Sys.IDLE)
            refreshBikeUi()
            refreshControlButton()
        }

        // Compact inline AA control surface (appears while AA is live): touch/drag the view to drive
        // Android Auto so the rider can set nav / music / view before pocketing the phone. The
        // fullscreen toggle opens the big landscape view. (Mirror mode doesn't need this — you touch
        // the phone screen directly.)
        csController = AaSurfaceController(findViewById(R.id.cs_surface)).also { it.attach() }
        findViewById<Button>(R.id.cs_fullscreen).setOnClickListener {
            if (AaVideoBridge.pipeline == null) {
                Toast.makeText(this, "Start Android Auto first", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, ControlActivity::class.java))
            }
        }

        findViewById<Button>(R.id.btn_share_log).setOnClickListener { shareLog() }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            LogBus.clear()
            logView.text = ""
        }

        SavedBike.load(this).let { saved ->
            if (saved != null) {
                log("Ready. Remembered bike: ${SavedBike.displayLabel(this, saved)} (${saved.ssid}). " +
                    "Tap Android Auto, or Mirror to cast the whole phone screen — no QR needed.")
            } else {
                log("Ready. Tap PAIR BIKE and point the camera at the bike's QR code.")
            }
        }

        setStatus("IDLE", Sys.IDLE)
        runEntryAnimation()
    }

    override fun onResume() {
        super.onResume()
        // Returning to the foreground (from fullscreen, the Wi-Fi dialog, or another app): re-assert
        // the inline preview. Ownership-checked attach makes this safe to call redundantly.
        if (activeMode == Mode.AA) csController?.reattach()
    }

    override fun onDestroy() {
        blinkAnim?.cancel()
        cursorAnim?.cancel()
        LogBus.listener = null
        AaVideoBridge.onSteadyVideo = null
        prober.stop()
        bleWakeUp?.stop()
        bleWakeUp = null
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        ProjectionService.stop(this)
        // NOTE: AndroidAutoService is intentionally NOT stopped here — it is a foreground service
        // meant to keep running when the phone is backgrounded/locked. Use "Stop Android Auto".
        BikeWifi.leave(this, ::log)
        super.onDestroy()
    }

    /** Tear down any running session (AA receiver, mirror projection, bike PXC, Wi-Fi). Leaves the
     *  saved bike and UI status untouched — the caller sets the resulting status. */
    private fun teardownSession() {
        AaVideoBridge.onSteadyVideo = null
        AndroidAutoService.stop(this)
        prober.stop()
        bleWakeUp?.stop()
        bleWakeUp = null
        ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
        ProjectionHolder.projection = null
        ProjectionService.stop(this)
        BikeWifi.leave(this, ::log)
        activeMode = null
    }

    private fun joinAndStart(qr: QrData, remembered: Boolean = false) {
        BikeWifi.join(
            context = this,
            ssid = qr.ssid,
            psk = qr.pwd,
            onAvailable = {
                // BLE wake-up is NOT required for projection (confirmed via TCP capture) — go
                // straight to the PXC flow. runBleWakeUpThenProber() remains available if needed.
                log("→ Wi-Fi bound; starting EasyConn PXC flow …")
                setStatus("LINK UP", Sys.LIVE)
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
            onLost = {
                log("bike network lost")
                setStatus("LINK LOST", Sys.BUSY)
            },
            log = ::log,
            onUnavailable = {
                // Couldn't join the bike's Wi-Fi (off, out of range, or the user declined). Enter a
                // clear failure state and tear down the half-started session — but KEEP the saved
                // bike so the rider can just retry, rather than being forced to re-scan the QR.
                runOnUiThread {
                    log("couldn't join ${qr.ssid} — bike not found / out of range / declined. " +
                        "Saved bike kept — tap Android Auto or Mirror to retry.")
                    teardownSession()
                    setStatus("BIKE NOT FOUND", Sys.FAIL)
                    refreshBikeUi()
                    refreshControlButton()
                }
            },
        )
    }

    /**
     * Reflects whether a bike is remembered. Both Android Auto and Mirror use the saved bike (no
     * QR re-scan), so the state is global; we surface it via the PAIRED BIKE panel.
     */
    /** The compact inline AA control surface is only shown while Android Auto is the active mode. */
    private fun refreshControlButton() {
        findViewById<View>(R.id.control_surface).visibility =
            if (activeMode == Mode.AA) View.VISIBLE else View.GONE
    }

    private fun refreshBikeUi() {
        val saved = SavedBike.load(this)
        val has = saved != null
        // Until a bike is paired, PAIR replaces the mode controls and the bike panel is hidden.
        // STOP only appears once Android Auto or Mirror is actually engaged.
        findViewById<View>(R.id.btn_pair).visibility = if (has) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_manual_entry).visibility = if (has) View.GONE else View.VISIBLE
        findViewById<View>(R.id.controls_row).visibility = if (has) View.VISIBLE else View.GONE
        btnStop.visibility = if (activeMode != null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.bike_panel).visibility = if (has) View.VISIBLE else View.GONE
        if (saved != null) {
            findViewById<TextView>(R.id.bike_alias).text = SavedBike.displayLabel(this, saved)
            findViewById<TextView>(R.id.bike_ssid).text = saved.ssid
        }
    }

    /** Updates the SYS readout, mode LEDs, and STOP styling. Safe to call from any thread. */
    private fun setStatus(text: String, mode: Sys) {
        runOnUiThread {
            if (mode == Sys.IDLE || mode == Sys.FAIL) activeMode = null
            statusValue.text = text
            statusValue.alpha = 0.2f
            statusValue.animate().alpha(1f).setDuration(160).start()

            val accent = ContextCompat.getColor(
                this,
                when (mode) {
                    Sys.BUSY -> R.color.accent_amber
                    Sys.LIVE -> R.color.accent_green
                    Sys.FAIL -> R.color.accent_red
                    Sys.IDLE -> R.color.paper
                },
            )
            statusBlink.setBackgroundColor(accent)
            blinkAnim?.cancel()
            blinkAnim = null
            when (mode) {
                Sys.BUSY -> blinkAnim = ObjectAnimator.ofFloat(statusBlink, View.ALPHA, 1f, 0.15f).apply {
                    duration = 420
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                }
                Sys.LIVE -> statusBlink.alpha = 1f
                Sys.FAIL -> statusBlink.alpha = 1f
                Sys.IDLE -> statusBlink.alpha = 0.25f
            }

            // The engaged mode's button takes the SYS color wholesale; the other stays paper.
            val engagedTone = if (mode == Sys.IDLE) null else accent
            btnAa.accent = if (activeMode == Mode.AA) engagedTone else null
            btnMirror.accent = if (activeMode == Mode.MIRROR) engagedTone else null

            // STOP shifts to red while there is a session it would tear down.
            val engaged = mode == Sys.BUSY || mode == Sys.LIVE
            btnStop.setBackgroundResource(
                if (engaged) R.drawable.bg_ghost_button_danger else R.drawable.bg_ghost_button
            )
            btnStop.setTextColor(
                ContextCompat.getColorStateList(
                    this,
                    if (engaged) R.color.text_ghost_button_danger else R.color.text_ghost_button,
                )
            )

            // STOP is available whenever a mode is engaged — including mid-connect so the user can
            // bail out. It reads CANCEL while connecting (BUSY) and DISCONNECT once the link is up.
            btnStop.visibility = if (activeMode != null) View.VISIBLE else View.GONE
            btnStop.text = if (mode == Sys.LIVE) "DISCONNECT" else "CANCEL"
        }
    }

    private fun setLogVisible(visible: Boolean, animate: Boolean) {
        if (animate) {
            TransitionManager.beginDelayedTransition(
                findViewById<ViewGroup>(R.id.main),
                AutoTransition().apply { duration = 200 },
            )
        }
        logScroll.visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btn_toggle_log).text =
            getString(if (visible) R.string.btn_hide else R.string.btn_show)
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("log_visible", visible).apply()
    }

    private fun showRenameDialog() {
        val saved = SavedBike.load(this) ?: return
        val view = layoutInflater.inflate(R.layout.dialog_rename, null)
        val input = view.findViewById<EditText>(R.id.rename_input)
        view.findViewById<TextView>(R.id.rename_current).text = saved.ssid
        input.setText(SavedBike.alias(this) ?: "")
        input.setSelection(input.text?.length ?: 0)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        // The layout draws its own ink panel with a paper border; drop the default window chrome.
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        fun save() {
            SavedBike.rename(this, input.text?.toString())
            log("bike renamed: ${SavedBike.displayLabel(this, saved)}")
            refreshBikeUi()
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btn_rename_save).setOnClickListener { save() }
        view.findViewById<Button>(R.id.btn_rename_cancel).setOnClickListener { dialog.dismiss() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        dialog.show()
        input.requestFocus()
    }

    /**
     * Manual Wi-Fi entry for bikes with no pairing QR: the rider types the hotspot SSID + password
     * their own bike broadcasts. We store it exactly like a scanned bike (with no modelId, so the
     * profile falls back to legacy/675 and the PXC CLIENT_INFO handshake re-scores it once
     * connected). This app ships no credentials — the rider supplies their own.
     */
    private fun showManualEntryDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_manual, null)
        val ssidInput = view.findViewById<EditText>(R.id.manual_ssid)
        val pwdInput = view.findViewById<EditText>(R.id.manual_pwd)
        // Pre-fill from a saved bike so editing (e.g. after a password change) is easy.
        SavedBike.load(this)?.let {
            ssidInput.setText(it.ssid)
            pwdInput.setText(it.pwd)
        }
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        fun submit() {
            val ssid = ssidInput.text?.toString()?.trim().orEmpty()
            val pwd = pwdInput.text?.toString().orEmpty()
            if (ssid.isEmpty()) {
                Toast.makeText(this, "Enter the bike's Wi-Fi name", Toast.LENGTH_SHORT).show()
                return
            }
            // WPA2 passphrases are 8–63 chars; WifiNetworkSpecifier rejects anything else.
            if (pwd.length !in 8..63) {
                Toast.makeText(this, "Password must be 8–63 characters", Toast.LENGTH_SHORT).show()
                return
            }
            dialog.dismiss()
            val qr = QrData(
                ssid = ssid,
                pwd = pwd,
                auth = "wpa2-psk",
                mac = null,
                name = null,
                action = 1,        // basic AP (bit0) — these bikes broadcast a plain hotspot, not P2P
                modelId = null,    // unknown → legacy/675 profile until the handshake re-scores it
                sn = null,
                channel = null,
            )
            log("manual Wi-Fi entry: ssid=$ssid — saved (legacy profile until the handshake).")
            onBikePaired(qr, connectAfter = false)
        }
        view.findViewById<Button>(R.id.btn_manual_save).setOnClickListener { submit() }
        view.findViewById<Button>(R.id.btn_manual_cancel).setOnClickListener { dialog.dismiss() }
        pwdInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        dialog.show()
        ssidInput.requestFocus()
    }

    /** Staggered slide-up of the panel sections on launch. */
    private fun runEntryAnimation() {
        val rise = 14f * resources.displayMetrics.density
        listOf(
            R.id.header_row, R.id.status_row, R.id.btn_pair, R.id.btn_manual_entry,
            R.id.controls_row, R.id.btn_aa_stop, R.id.bike_panel, R.id.log_header, R.id.log_scroll,
        ).forEachIndexed { i, id ->
            findViewById<View>(id)?.let { v ->
                v.alpha = 0f
                v.translationY = rise
                v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(40L + i * 55L)
                    .setDuration(240)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun runBleWakeUpThenProber() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ), 2,
            )
            // The user will need to tap Scan again after granting; keeping it simple for PoC.
            return
        }
        bleWakeUp?.stop()
        bleWakeUp = BleWakeUp(
            context = this,
            log = ::log,
            onUnlocked = {
                log("→ BLE wake-up OK; starting EasyConn prober …")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
            onFailed = { reason ->
                log("BLE wake-up failed: $reason — TCP probe likely useless, starting anyway")
                try {
                    prober.start(BikeWifi.currentNetwork)
                } catch (e: Exception) {
                    log("prober start failed: $e")
                }
            },
        ).also { it.start() }
    }

    private fun ensureLocationPermission() {
        // Some OEMs require fine location to associate via WifiNetworkSpecifier.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1,
            )
        }
    }

    private fun shareLog() {
        try {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "opencfmoto-$stamp.log")
            file.writeText(LogBus.snapshot())
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "opencfmoto log $stamp")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share log"))
            log("log saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            log("share failed: $e")
        }
    }

    private fun log(msg: String) = LogBus.log(msg)
}
