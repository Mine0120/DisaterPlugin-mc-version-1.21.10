package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Random;

/**
 * 태양 강화(지구 멸망): 자동/수동 강도(Severity) 지원
 * - 자동: 일수(day)에 따라 severity가 상승
 * - 수동: /disaster doomsday set <0..1> 로 고정. /disaster doomsday auto 로 자동 복귀
 */
public class DoomsdayManager {
    private final MineDisaster plugin;
    private BukkitTask task;
    private final Random rnd = new Random();

    // 설정값
    private int tickInterval;
    private int samplesPerTick;
    private int startAfterDays;
    private double baseSeverity;
    private double growthPerDay;
    private boolean allowManual;

    // 수동 모드 제어
    private boolean manualMode = false;
    private double manualSeverity = 0.0;

    public DoomsdayManager(MineDisaster plugin) {
        this.plugin = plugin;
        reloadConfig();
        // config에 manual-default가 지정되어 있으면 바로 반영
        double md = plugin.getConfig().getDouble("doomsday.manual-default", -1.0);
        if (md >= 0.0 && md <= 1.0 && allowManual) {
            manualMode = true;
            manualSeverity = md;
        }
    }

    public void reloadConfig() {
        tickInterval   = Math.max(1, plugin.getConfig().getInt("doomsday.tick-interval", 10));
        samplesPerTick = Math.max(1, plugin.getConfig().getInt("doomsday.samples-per-tick", 250));
        startAfterDays = Math.max(0, plugin.getConfig().getInt("doomsday.start-after-days", 0));
        baseSeverity   = clamp01(plugin.getConfig().getDouble("doomsday.base-severity", 0.0));
        growthPerDay   = Math.max(0.0, plugin.getConfig().getDouble("doomsday.growth-per-day", 0.12));
        allowManual    = plugin.getConfig().getBoolean("doomsday.allow-manual", true);
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, tickInterval);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    /** 현재 강도 반환(0.0~1.0). 수동 모드면 수동값, 아니면 자동 계산 */
    public double getCurrentSeverity(World world) {
        if (manualMode && allowManual) return manualSeverity;
        return computeAutoSeverity(world);
    }

    /** 수동 강도 고정 (0.0~1.0), allow-manual=false면 무시 */
    public boolean setManualSeverity(double value) {
        if (!allowManual) return false;
        manualMode = true;
        manualSeverity = clamp01(value);
        return true;
    }

    /** 자동 모드로 복귀 */
    public void unsetManual() {
        manualMode = false;
    }

    /** 수동 모드 여부 */
    public boolean isManualMode() { return manualMode; }

    /** 자동 강도 계산: dayCount에 따라 상승 */
    private double computeAutoSeverity(World w) {
        long day = (w.getFullTime() / 24000L);
        long eff = Math.max(0, day - startAfterDays);
        double sev = baseSeverity + eff * growthPerDay;
        return clamp01(sev);
    }

    private double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    private void tick() {
        if (!plugin.getConfig().getBoolean("doomsday.enabled", true)) return;

        for (World w : Bukkit.getWorlds()) {
            double severity = getCurrentSeverity(w);
            if (severity <= 0.0) continue;

            // 샘플링 기반 처리(부하 관리)
            for (int i = 0; i < samplesPerTick; i++) {
                int x = rnd.nextInt(256) - 128 + w.getSpawnLocation().getBlockX();
                int z = rnd.nextInt(256) - 128 + w.getSpawnLocation().getBlockZ();
                int y = w.getHighestBlockYAt(x, z);
                Block top = w.getBlockAt(x, y, z);
                applySurfaceEffects(w, top, severity);
            }

            // 일광 화상(노출 플레이어)
            for (Player p : w.getPlayers()) {
                if (p.isDead() || p.isInvulnerable() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)
                    continue;

                long time = w.getTime();
                boolean isDay = time < 12000; // 낮: 0~11999
                if (!isDay) continue;

                Location l = p.getLocation();
                boolean sky = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) <= l.getBlockY();
                if (!sky) continue;

                float light = l.getBlock().getLightFromSky();
                if (light < 14) continue;

                double dps = plugin.getConfig().getDouble("doomsday.burn.base-dps", 0.5)
                        + plugin.getConfig().getDouble("doomsday.burn.scale-dps", 2.0) * severity;
                if (dps > 0) {
                    double dmgPerTick = dps / 20.0 * tickInterval;
                    p.setFireTicks(Math.max(p.getFireTicks(), 40)); // 최소 불태우기
                    p.damage(dmgPerTick);
                }
            }
        }
    }

    // 지표 변화(물 증발/잔디->흙/나무 점화 등). 확률은 severity 기반
    private void applySurfaceEffects(World w, Block top, double severity) {
        Material type = top.getType();

        // 물 증발
        if (isWater(type)) {
            double base = plugin.getConfig().getDouble("doomsday.water.base", 0.08);
            double exp  = plugin.getConfig().getDouble("doomsday.water.exp", 1.2);
            if (roll(Math.pow(severity, exp) * base)) {
                top.setType(Material.AIR, true);
                w.spawnParticle(Particle.CLOUD, top.getLocation().add(0.5,0.9,0.5), 2, 0.2,0.1,0.2, 0);
                return;
            }
        }

        // 잔디 -> 흙
        if (type == Material.GRASS_BLOCK) {
            double base = plugin.getConfig().getDouble("doomsday.grass.base", 0.06);
            double exp  = plugin.getConfig().getDouble("doomsday.grass.exp", 1.0);
            if (roll(Math.pow(severity, exp) * base)) {
                top.setType(Material.DIRT, true); // 물리 적용
                return;
            }
        }

        // 나무/잎 점화
        if (isTree(type)) {
            double base = plugin.getConfig().getDouble("doomsday.trees.base", 0.05);
            double exp  = plugin.getConfig().getDouble("doomsday.trees.exp", 1.1);
            if (roll(Math.pow(severity, exp) * base)) {
                Block up = top.getRelative(0,1,0);
                if (up.getType().isAir()) {
                    up.setType(Material.FIRE, true);
                }
            }
        }
    }

    private boolean isWater(Material m) {
        return m == Material.WATER || m == Material.KELP || m == Material.KELP_PLANT || m == Material.SEAGRASS
                || m == Material.TALL_SEAGRASS || m == Material.BUBBLE_COLUMN;
    }

    private boolean isTree(Material m) {
        // 간단 판정(빠르고 안전)
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_HYPHAE") || n.endsWith("_STEM") || n.endsWith("_LEAVES");
    }

    private boolean roll(double p) {
        if (p <= 0) return false;
        if (p >= 1) return true;
        return rnd.nextDouble() < p;
    }
}
