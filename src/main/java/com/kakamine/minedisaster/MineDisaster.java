package com.kakamine.minedisaster;

import com.kakamine.minedisaster.command.*;
import com.kakamine.minedisaster.disaster.*;
import org.bukkit.plugin.java.JavaPlugin;

public class MineDisaster extends JavaPlugin {
    private MeteorManager meteorManager;
    private BacteriaManager bacteriaManager;
    private DoomsdayManager doomsdayManager;
    private EarthquakeManager earthquakeManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        meteorManager = new MeteorManager(this);
        bacteriaManager = new BacteriaManager(this);
        doomsdayManager = new DoomsdayManager(this);
        earthquakeManager = new EarthquakeManager(this);

        getCommand("disaster").setExecutor(new DisasterCommand(this));
        getCommand("disaster").setTabCompleter(new DisasterTab(this));

        getServer().getPluginManager().registerEvents(bacteriaManager, this);
        getLogger().info("[MineDisaster] 플러그인 활성화 완료");
    }

    public MeteorManager getMeteorManager() { return meteorManager; }
    public BacteriaManager getBacteriaManager() { return bacteriaManager; }
    public DoomsdayManager getDoomsdayManager() { return doomsdayManager; }
    public EarthquakeManager getEarthquakeManager() { return earthquakeManager; }
}
