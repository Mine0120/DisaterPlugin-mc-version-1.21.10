package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;

/**
 * 시각 효과 제거 버전.
 * - 아무 동작도 하지 않음.
 * - 지구 멸망(DoomsdayManager)의 논리만 작동.
 */
public class DoomsdayVisualsManager {

    private final MineDisaster plugin;

    public DoomsdayVisualsManager(MineDisaster plugin) {
        this.plugin = plugin;
    }

    /** 비워둠 */
    public void reload() {}

    /** 비워둠 */
    public void start() {}

    /** 비워둠 */
    public void stop() {}
}
