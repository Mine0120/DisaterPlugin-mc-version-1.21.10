package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.Map;

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
    private boolean bossbarEnabled;
    private String bossbarTitle;

    private final Map<World, BossBar> bars = new EnumMap<>(World.class); // key로 World 사용 X → 아래에서 per-world로 따로 관리
    // 위 라인은 형식상, 실제로는 World를 키로 쓰는 HashMap이 필요
    private final java.util.HashMap<World, BossBar> worldBars = new java.util.HashMap<>();

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
        bossbarEnabled= plugin.getConfig().getBoolean("visuals.bossbar.enabled", true);
        bossbarTitle  = plugin.getConfig().getString("visuals.bossbar.title", "☼ Solar Severity");
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
                    // 날씨/시간 고정 효과를 위해 날씨/시간 사이클 비활성화 권장 (서버 gamerule)
                    // 여기서는 시각만 살짝 밀어 유지
                    long cur = w.getTime();
                    if (Math.abs(cur - sunsetTime) > 40) {
                        w.setTime(sunsetTime);
                    }
                }

                // 2) 폭풍/천둥 (severity 비례 확률)
                if (stormEnabled && sev > 0.0) {
                    double p = lerp(stormMin, stormMax, sev);
                    if (Math.random() < p) {
                        // 월드 전체 날씨를 바꾸면 거슬릴 수 있어, 플레이어별로 개인 날씨를 줄 수도 있음
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

                // 4) 보스바(세계별)
                if (bossbarEnabled) {
                    BossBar bar = worldBars.computeIfAbsent(w, ww -> {
                        BossBar b = Bukkit.createBossBar(bossbarTitle, BarColor.YELLOW, BarStyle.SOLID);
                        b.setVisible(true);
                        return b;
                    });
                    bar.setTitle(bossbarTitle + " §f" + String.format("%.0f%%", sev * 100));
                    bar.setProgress(Math.max(0.0, Math.min(1.0, sev)));
                    bar.setColor(severityToColor(sev));
                    // 월드 내 모든 플레이어에게 표시
                    for (Player p : w.getPlayers()) {
                        if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
                    }
                    // 플레이어가 없으면 숨김
                    if (w.getPlayers().isEmpty()) bar.removeAll();
                }
            }
        }, 0L, tickInterval);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        // 보스바 정리
        for (BossBar b : worldBars.values()) {
            b.removeAll();
            b.setVisible(false);
        }
        worldBars.clear();
    }

    private BarColor severityToColor(double sev) {
        if (sev >= 0.75) return BarColor.RED;
        if (sev >= 0.4)  return BarColor.YELLOW;
        return BarColor.WHITE;
    }

    private double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private double lerp(double a, double b, double t) { return a + (b - a) * clamp01(t); }
}
