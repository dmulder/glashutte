package io.github.dmulder.glashutte

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaDrm
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.github.dmulder.glashutte.glasses.GlassesDiagnostics
import java.util.UUID

/**
 * Plays Google's public Widevine test vector ("Tears of Steel", encrypted with Widevine's
 * public test key server) so the real DRM path can be exercised on a chosen display:
 * phone, the glasses' own display (flat), or mirrored.
 *
 * This is the same thing any streaming app does: it asks the platform for a licence and plays
 * through the platform's secure decoder into a secure surface. It uses no private content and
 * no capture or decryption outside the platform's own DRM stack. Everything it observes is
 * logged under `GlashutteDiag` as `DRM-PLAY:` lines, and shown on screen.
 *
 * What it cannot see is whether the picture reaches the panel: that is the question the
 * person looking at the glasses answers (picture, or audio only).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DrmTestActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var status: TextView
    private lateinit var surface: SurfaceView
    private val handler = Handler(Looper.getMainLooper())
    private val lines = ArrayList<String>()
    private var firstFrameAtMs = -1L
    private var startedAtMs = 0L
    private var decoderName = "?"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this).apply { setSecure(true) }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            textSize = 14f
            setPadding(24, 16, 24, 16)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        val close = TextView(this).apply {
            text = "  Close test  "
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC333333.toInt())
            textSize = 16f
            setPadding(24, 16, 24, 16)
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        setContentView(root)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onStart() {
        super.onStart()
        startPlayback()
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onStop()
    }

    private fun note(message: String) {
        lines.add(message)
        while (lines.size > 14) lines.removeAt(0)
        status.text = lines.joinToString("\n")
        GlassesDiagnostics.i(GlassesDiagnostics.TAG, "DRM-PLAY: $message")
    }

    private fun startPlayback() {
        startedAtMs = System.currentTimeMillis()
        val display = display ?: windowManager.defaultDisplay
        note("test started on display ${display.displayId} '${display.name}' ${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} @${resources.displayMetrics.densityDpi}dpi")
        note("Widevine security level: ${widevineLevel()}")
        val item = MediaItem.Builder()
            .setUri(DASH_URL)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(LICENSE_URL).build(),
            )
            .build()
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        exo.setVideoSurfaceView(surface)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                note("player state: " + when (state) {
                    Player.STATE_IDLE -> "idle"
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> "ready (playing=${exo.isPlaying})"
                    Player.STATE_ENDED -> "ended"
                    else -> state.toString()
                })
            }

            override fun onPlayerError(error: PlaybackException) {
                note("PLAYER ERROR ${error.errorCodeName}: ${error.message}")
                var cause: Throwable? = error.cause
                while (cause != null) {
                    note("  caused by ${cause.javaClass.simpleName}: ${cause.message}")
                    cause = cause.cause
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                note("video size ${videoSize.width}x${videoSize.height}")
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                this@DrmTestActivity.decoderName = decoderName
                note("video decoder: $decoderName${if (decoderName.contains("secure", true)) "  (SECURE decoder: L1 path)" else "  (non-secure decoder)"}")
            }

            override fun onDrmSessionAcquired(eventTime: AnalyticsListener.EventTime, state: Int) {
                note("DRM session acquired")
            }

            override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime) {
                note("licence granted, keys loaded")
            }

            override fun onDrmSessionManagerError(eventTime: AnalyticsListener.EventTime, error: Exception) {
                note("DRM error: ${error.javaClass.simpleName}: ${error.message}")
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                note("video format ${format.width}x${format.height} ${format.sampleMimeType}")
            }

            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                firstFrameAtMs = System.currentTimeMillis()
                note("first frame handed to the display after ${firstFrameAtMs - startedAtMs} ms")
            }
        })
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
        handler.postDelayed({ conclude() }, 12_000)
    }

    private fun conclude() {
        val exo = player ?: return
        val rendered = firstFrameAtMs >= 0
        note(
            "RESULT after 12 s: " + when {
                exo.playerError != null -> "playback failed (see error above)"
                rendered -> "frames are being decoded and rendered (decoder $decoderName). Look at the panel: " +
                    "PICTURE visible = DRM path works here; BLACK with sound = the display path is blanking it."
                else -> "no frame was rendered; the licence or decoder step failed (see above)"
            },
        )
    }

    private fun widevineLevel(): String = try {
        val drm = MediaDrm(WIDEVINE)
        try {
            drm.getPropertyString("securityLevel")
        } finally {
            drm.close()
        }
    } catch (t: Throwable) {
        "unavailable (${t.javaClass.simpleName})"
    }

    companion object {
        private val WIDEVINE: UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

        /** Widevine's public test content and its public UAT licence proxy (the ExoPlayer demo uses the same). */
        const val DASH_URL = "https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd"
        const val LICENSE_URL = "https://proxy.uat.widevine.com/proxy?provider=widevine_test"

        fun start(context: Context, displayId: Int?) {
            val intent = Intent(context, DrmTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val options = displayId?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
            context.startActivity(intent, options)
        }
    }
}
