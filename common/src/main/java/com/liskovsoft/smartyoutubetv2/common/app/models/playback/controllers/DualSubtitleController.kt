package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers

import android.os.Handler
import android.os.Looper
import com.liskovsoft.sharedutils.helpers.MessageHelpers
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.rx.RxHelper
import com.liskovsoft.smartyoutubetv2.common.R
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController
import com.liskovsoft.smartyoutubetv2.common.dualsub.ClaudeTranslator
import com.liskovsoft.smartyoutubetv2.common.dualsub.DualSubData
import com.liskovsoft.smartyoutubetv2.common.dualsub.DualSubSession
import com.liskovsoft.smartyoutubetv2.common.dualsub.FatalApiException
import com.liskovsoft.smartyoutubetv2.common.dualsub.LangUtil
import com.liskovsoft.smartyoutubetv2.common.dualsub.OkHttpTransport
import com.liskovsoft.smartyoutubetv2.common.dualsub.SubTrack
import com.liskovsoft.smartyoutubetv2.common.dualsub.TrackPicker
import com.liskovsoft.smartyoutubetv2.common.dualsub.TranslationCache
import io.reactivex.disposables.Disposable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Original + AI translated subtitles. Same shape as [SponsorBlockController]:
 * load once per video, then follow the playback position.
 */
class DualSubtitleController : BasePlayerController() {
    companion object {
        private val TAG = DualSubtitleController::class.java.simpleName
        private const val POLL_INTERVAL_MS = 100L
        private const val NO_CUE = Long.MIN_VALUE
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpTransport()
    private val onSettingsChange = Runnable { mainHandler.post { restart(video) } }
    private var data: DualSubData? = null
    private var cache: TranslationCache? = null
    private var session: DualSubSession? = null
    private var pollAction: Disposable? = null
    private var shownKey = NO_CUE
    private var isActive = false

    override fun onInit() {
        data = DualSubData.instance(context).also {
            it.removeOnChange(onSettingsChange)
            it.addOnChange(onSettingsChange)
        }
        cache = TranslationCache(File(context.cacheDir, "dualsub"))
    }

    override fun onNewVideo(item: Video?) {
        stop()
    }

    override fun onVideoLoaded(item: Video?) {
        restart(item)
    }

    override fun onEngineReleased() {
        stop()
    }

    override fun onFinish() {
        stop()
    }

    override fun onPlay() {
        startPolling()
    }

    override fun onPause() {
        stopPolling()
        render()
    }

    override fun onSeekEnd() {
        updatePosition()
    }

    override fun onSeekPositionChanged(positionMs: Long) {
        updatePosition()
    }

    private fun restart(video: Video?) {
        stop()

        val data = data ?: return
        val player = player ?: return

        if (!data.isEnabled || video == null || video.videoId == null || video.isLive) {
            Log.d(TAG, "Dual subs off: enabled=%s video=%s live=%s", data.isEnabled, video?.videoId, video?.isLive)
            return
        }

        if (data.apiKey.isEmpty()) {
            Log.d(TAG, "Dual subs: no API key")
            MessageHelpers.showMessage(context, R.string.dualsub_api_key_missing)
            return
        }

        val tracks = video.subtitles.orEmpty().mapNotNull { sub ->
            val url = sub.baseUrl ?: return@mapNotNull null
            SubTrack(sub.languageCode ?: "", url, "asr" == sub.type)
        }

        // No original subs: leave ExoPlayer subtitles alone
        val track = TrackPicker.pick(tracks, data.sourceLang, video.originalAudioLanguage)
        if (track == null) {
            Log.d(TAG, "Dual subs: no track in the spoken language (audio=%s) among %s", video.originalAudioLanguage,
                tracks.map { it.languageCode + if (it.isAuto) "(auto)" else "" })
            return
        }

        // Original already in the viewer's language (any Chinese script counts): nothing to add,
        // leave subtitles to SmartTube so the user's own on/off choice applies
        if (LangUtil.isSameLanguage(track.languageCode, data.targetLang)) {
            Log.d(TAG, "Dual subs: original %s is already readable as %s, skipping", track.languageCode, data.targetLang)
            return
        }

        val humanTrack = if (data.isPreferHumanTranslation) {
            TrackPicker.pickHumanTranslation(tracks, data.targetLang)
        } else null
        val useAi = humanTrack == null
        val translator = if (useAi) ClaudeTranslator(http, data.apiKey, data.model, data.targetLang) else null
        val cacheName = if (useAi) {
            TranslationCache.fileName(video.videoId, track.languageCode, data.targetLang, data.model)
        } else null

        if (humanTrack != null) {
            MessageHelpers.showMessage(context, R.string.dualsub_using_human)
        }

        Log.d(TAG, "Dual subs track: %s auto=%s ai=%s human=%s audio=%s (all: %s)", track.languageCode, track.isAuto, useAi,
            humanTrack?.languageCode,
            video.originalAudioLanguage,
            tracks.map { it.languageCode + if (it.isAuto) "(auto)" else "" })

        lateinit var newSession: DualSubSession
        val targetLang = data.targetLang
        newSession = DualSubSession(track, http, translator, cache, cacheName,
            humanTranslationTrack = humanTrack,
            isUnusable = { cues ->
                val texts = cues.map { it.text }
                !LangUtil.matchesScript(texts, track.languageCode) || LangUtil.isTextReadableAs(texts, targetLang)
            },
            listener = object : DualSubSession.Listener {
            override fun onCuesReady() = onMain {
                Log.d(TAG, "Dual subs loaded: %s", newSession.loadInfo)
                activate()
            }

            override fun onFinished(stats: String) = onMain { Log.d(TAG, "Dual subs translated: %s", stats) }

            override fun onTranslated() = onMain { render() }

            override fun onError(error: Throwable, fatal: Boolean) = onMain { showError(error, fatal) }

            override fun onUnusable() = onMain {
                Log.d(TAG, "Dual subs: %s track text is mixed, mislabeled or already readable as %s, skipping", track.languageCode, targetLang)
                stop()
            }

            private fun onMain(action: () -> Unit) {
                mainHandler.post { if (session === newSession) action() }
            }
        })
        session = newSession
        newSession.start(player.positionMs)
    }

    private fun activate() {
        isActive = true
        shownKey = NO_CUE
        render()

        if (player?.isPlaying == true) {
            startPolling()
        }
    }

    private fun stop() {
        session?.cancel()
        session = null
        stopPolling()

        if (isActive) {
            isActive = false
            player?.setDualSubtitle(null, null) // give subtitles back to ExoPlayer
        }
    }

    private fun updatePosition() {
        val player = player ?: return
        session?.setPositionMs(player.positionMs)
        render()
    }

    private fun startPolling() {
        if (!isActive || pollAction?.isDisposed == false) {
            return
        }

        pollAction = RxHelper.interval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .subscribe({ render() }, { error -> Log.e(TAG, "Poll error: %s", error.message) })
    }

    private fun stopPolling() {
        RxHelper.disposeActions(pollAction)
        pollAction = null
    }

    private fun render() {
        val session = session ?: return
        val player = player ?: return

        if (!isActive) {
            return
        }

        val index = session.findIndex(player.positionMs)
        val translation = if (index >= 0) session.translation(index) else null
        val key = index * 2L + (if (translation != null) 1 else 0)

        // Only touch the view when the cue (or its translation) changed
        if (key == shownKey) {
            return
        }
        shownKey = key
        Log.d(TAG, "Dual subs show #%s at %sms translated=%s", index, player.positionMs, translation != null)

        if (index < 0) {
            player.setDualSubtitle("", "")
            return
        }

        val original = session.original(index)
        val showOriginal = data?.isShowOriginal != false || translation == null
        player.setDualSubtitle(translation ?: "", if (showOriginal) original else "")
    }

    private fun showError(error: Throwable, fatal: Boolean) {
        Log.e(TAG, "Dual subs error (fatal=%s): %s", fatal, error.message)

        when {
            error is FatalApiException && error.isAuthError ->
                MessageHelpers.showLongMessage(context, context.getString(R.string.dualsub_api_key_invalid))
            session?.isReady == false -> {
                // Original track never loaded: give the screen back to ExoPlayer
                MessageHelpers.showMessage(context, context.getString(R.string.dualsub_load_failed, error.message))
                stop()
            }
            else ->
                MessageHelpers.showMessage(context, context.getString(R.string.dualsub_translate_failed, error.message))
        }
    }
}
