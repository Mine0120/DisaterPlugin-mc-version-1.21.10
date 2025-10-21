package com.kakamine.minedisaster;

import com.kakamine.minedisaster.commands.DisasterCommand;
import com.kakamine.minedisaster.disaster.*;
import org.bukkit.plugin.java.JavaPlugin;

public class MineDisaster extends JavaPlugin {

    private static MineDisaster instance;

    private MeteorManager meteorManager;
    private EarthquakeManager earthquakeManager;
    private BacteriaManager bacteriaManager;
    private DoomsdayManager doomsdayManager;

    @Override
    public void onLoad() {
        instance = this;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        meteorManager = new MeteorManager(this);
        earthquakeManager = new EarthquakeManager(this);
        bacteriaManager = new BacteriaManager(this);
        doomsdayManager = new DoomsdayManager(this);

        var cmd = getCommand("disaster");
        if (cmd != null) {
            var executor = new DisasterCommand(this, meteorManager, earthquakeManager, bacteriaManager, doomsdayManager);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // 메테오 착지 감지 리스너 등록
        getServer().getPluginManager().registerEvents(meteorManager, this);

        // 서버 시작 시 자동 도입 옵션
        if (getConfig().getBoolean("doomsday.enabled-by-default", false)) {
            doomsdayManager.start();
        }

        getLogger().info("MineDisaster enabled!");
    }

    @Override
    public void onDisable() {
        if (bacteriaManager != null) bacteriaManager.cancelAll();
        if (earthquakeManager != null) earthquakeManager.cancelAll();
        if (meteorManager != null) meteorManager.cancelAll();
        if (doomsdayManager != null && doomsdayManager.isRunning()) doomsdayManager.stop();
        getLogger().info("MineDisaster disabled!");
    }

    public static MineDisaster get() { return instance; }
}
