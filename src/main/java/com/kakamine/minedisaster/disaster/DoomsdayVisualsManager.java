package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 지구 멸망 시각 효과 매니저 (보스바 제거 버전)
 * - 해질녘 고정
 * - 폭풍/천둥
 * - ASH/SMOKE 입자
 */
public class DoomsdayVisualsManager {
    private final MineDisaster plugin;
    private BukkitTask task;

    private boolean enabled;
    private int tickInterval;
    private boolean lockSunset;
    private long sunsetTime;
    private boolean stormEnabled;
    private double stormMin;
    private double stormMax;
    private boolean ashEnabled;
    private int ashBase;
    private int ashPerSev;
    private double ashRadius;

    public DoomsdayVisualsManager(MineDisaster plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled       = plugin.getConfig().getBoolean("visuals.enabled", true);
        tickInterval  = Math.max(1, plugin.getConfig().getInt("visuals.tick-interval", 20));
        lockSunset    = plugin.getConfig().getBoolean("visuals.lock-sunset", true);
        sunsetTime    = Math.max(0, plugin.getConfig().getLong("visuals.sunset-time", 12300));
        stormEnabled  = plugin.getConfig().getBoolean("visuals.storm.enabled", true);
        stormMin      = clamp01(plugin.getConfig().getDouble("visuals.storm.min-chance", 0.05));
        stormMax      = clamp01(plugin.getConfig().getDouble("visuals.storm.max-chance", 0.50));
        ashEnabled    = plugin.getConfig().getBoolean("visuals.ash.enabled", true);
        ashBase       = Math.max(0, plugin.getConfig().getInt("visuals.ash.base-density", 2));
        ashPerSev     = Math.max(0, plugin.getConfig().getInt("visuals.ash.per-severity", 8));
        ashRadius     = Math.max(1.0, plugin.getConfig().getDouble("visuals.ash.radius", 6.0));
    }

    public void start() {
        stop();
        if (!enabled) return;

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            var dm = plugin.getDoomsdayManager();
            if (dm == null) return;

            for (World w : Bukkit.getWorlds()) {
                double sev = dm.getCurrentSeverity(w); // 0.0 ~ 1.0

                // 1) 하늘 해질녘 락
                if (lockSunset && sev > 0.0) {
                    long cur = w.getTime();
                    if (Math.abs(cur - sunsetTime) > 40) {
                        w.setTime(sunsetTime);
                    }
                }

                // 2) 폭풍/천둥 (severity 비례 확률)
                if (stormEnabled && sev > 0.0) {
                    double p = lerp(stormMin, stormMax, sev);
                    if (Math.random() < p) {
                        if (!w.hasStorm()) {
                            w.setStorm(true);
                            w.setThundering(true);
                            w.setThunderDuration(200 + (int)(400 * sev));
                            w.setWeatherDuration(600 + (int)(800 * sev));
                        }
                    }
                }

                // 3) ASH/SMOKE 입자
                if (ashEnabled && sev > 0.0) {
                    int count = ashBase + (int)Math.round(ashPerSev * sev);
                    for (Player p : w.getPlayers()) {
                        Location base = p.getLocation();
                        for (int i = 0; i < count; i++) {
                            double ang = Math.random() * Math.PI * 2;
                            double r   = Math.random() * ashRadius;
                            double dx = Math.cos(ang) * r;
                            double dz = Math.sin(ang) * r;
                            double dy = 0.2 + Math.random() * 1.2;
                            Location at = base.clone().add(dx, dy, dz);
                            w.spawnParticle(Particle.ASH, at, 1, 0.1, 0.1, 0.1, 0.0);
                            if (Math.random() < 0.25) {
                                w.spawnParticle(Particle.SMOKE, at, 1, 0.1, 0.1, 0.1, 0.0);
                            }
                        }
                    }
                }
            }
        }, 0L, tickInterval);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private double lerp(double a, double b, double t) { return a + (b - a) * clamp01(t); }
}
