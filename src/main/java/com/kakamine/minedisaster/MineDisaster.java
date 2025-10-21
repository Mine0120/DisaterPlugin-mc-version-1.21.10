package com.kakamine.minedisaster;

import com.kakamine.minedisaster.command.DisasterCommand;
import com.kakamine.minedisaster.disaster.BacteriaManager;
import com.kakamine.minedisaster.disaster.DoomsdayManager;
import com.kakamine.minedisaster.disaster.MeteorManager;
import org.bukkit.plugin.java.JavaPlugin;

public class MineDisaster extends JavaPlugin {

    private static MineDisaster instance;

    // ───────────────────────────────
    // 매니저 인스턴스
    // ───────────────────────────────
    private MeteorManager meteorManager;
    private BacteriaManager bacteriaManager;
    private DoomsdayManager doomsdayManager;
    private com.kakamine.minedisaster.disaster.DoomsdayVisualsManager visualsManager;

    @Override
    public void onEnable() {
        instance = this;

        // config.yml 로드
        saveDefaultConfig();

        // 매니저 초기화
        meteorManager = new MeteorManager(this);
        bacteriaManager = new BacteriaManager(this);
        doomsdayManager = new DoomsdayManager(this);
        visualsManager = new com.kakamine.minedisaster.disaster.DoomsdayVisualsManager(this);
        visualsManager.start();

        // 이벤트 등록
        getServer().getPluginManager().registerEvents(meteorManager, this);
        getServer().getPluginManager().registerEvents(bacteriaManager, this);

        // 명령어 등록
        getCommand("disaster").setExecutor(new DisasterCommand(this));

        getLogger().info("MineDisaster 플러그인 활성화 완료 ✅");
    }

    @Override
    public void onDisable() {
        // 실행 중인 재앙 종료
        if (meteorManager != null) meteorManager.cancelAll();
        if (bacteriaManager != null) bacteriaManager.cancelAll();
        if (doomsdayManager != null) doomsdayManager.stop();
        if (visualsManager != null) visualsManager.stop();

        getLogger().info("MineDisaster 플러그인 비활성화 완료 💤");
    }

    // ───────────────────────────────
    // 매니저 Getter
    // ───────────────────────────────
    public static MineDisaster getInstance() {
        return instance;
    }

    public MeteorManager getMeteorManager() {
        return meteorManager;
    }

    public BacteriaManager getBacteriaManager() {
        return bacteriaManager;
    }

    public DoomsdayManager getDoomsdayManager() {
        return doomsdayManager;
    }

    public com.kakamine.minedisaster.disaster.DoomsdayVisualsManager getVisualsManager() {
        return visualsManager;
    }
}
