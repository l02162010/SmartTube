package com.liskovsoft.smartyoutubetv2.common.app.views;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerManager;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;

public interface PlaybackView extends PlayerManager {
    void showProgressBar(boolean show);
    /**
     * MOD: dual subtitles. Replaces ExoPlayer subtitles while active.<br/>
     * Both null = off (ExoPlayer subtitles come back), empty strings = nothing to show right now.
     */
    void setDualSubtitle(String translation, String original);
}
