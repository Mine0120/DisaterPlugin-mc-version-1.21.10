package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Random;

public class DoomsdayManager {
    private final MineDisaster plugin;
    private BukkitTask task;
    private final Random rnd = new Random();

    private int tickInterval, samplesPerTick, startAfterDays;
    private double baseSeverity, growthPerDay;
    private boolean allowManual;

    // 성능/스캔
    private int scanRadiusChunks = 6;
    private int maxUpdatesPerTick = 80;
    private int burnCheckInterval = 20;

    // 수동
    private boolean manualMode = false;
    private double manualSeverity = 0.0;

    private int tickCount = 0;

    public DoomsdayManager(MineDisaster plugin) {
        this.plugin = plugin;
        reloadConfig();
        double md = plugin.getConfig().getDouble("doomsday.manual-default", -1.0);
        if (md >= 0.0 && md <= 1.0 && allowManual) { manualMode = true; manualSeverity = clamp01(md); }
    }

    public void reloadConfig() {
        tickInterval   = Math.max(1, plugin.getConfig().getInt("doomsday.tick-interval", 20));
        samplesPerTick = Math.max(1, plugin.getConfig().getInt("doomsday.samples-per-tick", 80));
        startAfterDays = Math.max(0, plugin.getConfig().getInt("doomsday.start-after-days", 0));
        baseSeverity   = clamp01(plugin.getConfig().getDouble("doomsday.base-severity", 0.0));
        growthPerDay   = Math.max(0.0, plugin.getConfig().getDouble("doomsday.growth-per-day", 0.12));
        allowManual    = plugin.getConfig().getBoolean("doomsday.allow-manual", true);

        scanRadiusChunks   = Math.max(2,  plugin.getConfig().getInt("doomsday.scan-radius-chunks", 6));
        maxUpdatesPerTick  = Math.max(10, plugin.getConfig().getInt("doomsday.max-updates-per-tick", 80));
        burnCheckInterval  = Math.max(5,  plugin.getConfig().getInt("doomsday.burn-check-interval", 20));
    }

    public void start() { stop(); task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, tickInterval); }
    public void stop()  { if (task != null) { task.cancel(); task = null; } }

    public double getCurrentSeverity(World world) {
        if (manualMode && allowManual) return manualSeverity;
        long day = (world.getFullTime() / 24000L);
        long eff = Math.max(0, day - startAfterDays);
        return clamp01(baseSeverity + eff * growthPerDay);
    }
    public boolean setManualSeverity(double value){ if(!allowManual)return false; manualMode=true; manualSeverity=clamp01(value); return true; }
    public void unsetManual(){ manualMode=false; }
    public boolean isManualMode(){ return manualMode; }

    private double clamp01(double v){ return Math.max(0.0, Math.min(1.0, v)); }

    private void tick() {
        if (!plugin.getConfig().getBoolean("doomsday.enabled", true)) return;
        tickCount += tickInterval;

        for (World w : Bukkit.getWorlds()) {
            double sev = getCurrentSeverity(w);
            if (sev <= 0.0) continue;

            int updates = 0;
            // ▶ 플레이어 주변 청크에서 분산 스캔
            for (Player p : w.getPlayers()) {
                if (updates >= maxUpdatesPerTick) break;

                int baseCX = p.getLocation().getBlockX() >> 4;
                int baseCZ = p.getLocation().getBlockZ() >> 4;

                for (int dcx = -scanRadiusChunks; dcx <= scanRadiusChunks; dcx++) {
                    if (updates >= maxUpdatesPerTick) break;
                    for (int dcz = -scanRadiusChunks; dcz <= scanRadiusChunks; dcz++) {
                        if (dcx*dcx + dcz*dcz > scanRadiusChunks*scanRadiusChunks) continue;

                        int cx = baseCX + dcx, cz = baseCZ + dcz;
                        if (!w.isChunkLoaded(cx, cz)) continue;

                        int perChunk = Math.max(1, (int)Math.round((samplesPerTick / 16.0) * (0.4 + 0.8 * sev)));
                        for (int i = 0; i < perChunk; i++) {
                            if (updates >= maxUpdatesPerTick) break;

                            int bx = (cx << 4) + rnd.nextInt(16);
                            int bz = (cz << 4) + rnd.nextInt(16);
                            int y  = w.getHighestBlockYAt(bx, bz);
                            Block top = w.getBlockAt(bx, y, bz);

                            // 하늘 노출
                            if (y < w.getHighestBlockYAt(bx, bz)) continue;

                            // 내부 게이트: sev 높으면 거의 통과
                            double gate = (sev >= 0.9) ? 1.0 : (0.15 + sev * 0.5);
                            if (rnd.nextDouble() > gate) continue;

                            updates += applySurfaceEffects(w, top, sev);
                        }
                    }
                }
            }

            // 주기적으로 플레이어 화상
            if ((tickCount % Math.max(1, burnCheckInterval)) == 0) {
                applySunburn(w, sev);
            }
        }
    }

    private int applySurfaceEffects(World w, Block top, double severity) {
        int changed = 0;
        Material type = top.getType();

        // 물 증발
        if (isWater(type)) {
            double base = plugin.getConfig().getDouble("doomsday.water.base", 0.12);
            double exp  = plugin.getConfig().getDouble("doomsday.water.exp", 1.1);
            if (roll(Math.pow(severity, exp) * base)) {
                top.setType(Material.AIR, true);
                return 1;
            }
        }

        // 잔디 -> 흙
        if (type == Material.GRASS_BLOCK) {
            double base = plugin.getConfig().getDouble("doomsday.grass.base", 0.12);
            double exp  = plugin.getConfig().getDouble("doomsday.grass.exp", 1.0);
            if (roll(Math.pow(severity, exp) * base)) {
                top.setType(Material.DIRT, true);
                return 1;
            }
        }

        // 나무/잎 점화
        if (isTree(type)) {
            double base = plugin.getConfig().getDouble("doomsday.trees.base", 0.10);
            double exp  = plugin.getConfig().getDouble("doomsday.trees.exp", 1.0);
            if (roll(Math.pow(severity, exp) * base)) {
                Block up = top.getRelative(0,1,0);
                if (up.getType().isAir()) { up.setType(Material.FIRE, true); return 1; }
            }
        }
        return changed;
    }

    private void applySunburn(World w, double severity) {
        for (Player p : w.getPlayers()) {
            if (p.isDead() || p.isInvulnerable() || p.getGameMode()==GameMode.CREATIVE || p.getGameMode()==GameMode.SPECTATOR) continue;

            // 낮 판정(Spigot): 0~11999
            long time = w.getTime();
            boolean isDay = time < 12000;
            if (!isDay) continue;

            Location l = p.getLocation();
            boolean sky = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) <= l.getBlockY();
            if (!sky) continue;
            if (l.getBlock().getLightFromSky() < 14) continue;

            double dps = plugin.getConfig().getDouble("doomsday.burn.base-dps", 0.6)
                    + plugin.getConfig().getDouble("doomsday.burn.scale-dps", 1.8) * severity;
            if (dps > 0) {
                double dmgPerTick = dps / 20.0 * burnCheckInterval;
                p.setFireTicks(Math.max(p.getFireTicks(), 40));
                p.damage(dmgPerTick);
            }
        }
    }

    private boolean isWater(Material m) {
        return m == Material.WATER || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.BUBBLE_COLUMN;
    }
    private boolean isTree(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_HYPHAE")
                || n.endsWith("_STEM") || n.endsWith("_LEAVES");
    }
    private boolean roll(double p){ if(p<=0)return false; if(p>=1)return true; return rnd.nextDouble()<p; }
}
