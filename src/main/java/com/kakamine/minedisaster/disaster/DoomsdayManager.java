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

    private boolean roll(double p) {
        if (p <= 0) return false;
        if (p >= 1) return true;
        return rnd.nextDouble() < p; // rnd는 클래스 상단에 있는 Random 필드입니다.
    }

    // 스캔/성능
    private int scanRadiusChunks = 6;
    private int maxUpdatesPerTick = 80;
    private int burnCheckInterval = 20;

    // 수동 강도
    private boolean manualMode = false;
    private double manualSeverity = 0.0;

    private int tickCount = 0;

    // ── 물 증발 가속 파라미터(없으면 기본값 사용)
    private double waterSpeedMult = 2.5;  // 증발 확률 배수
    private int waterMaxSteps = 4;        // 한 샘플에서 최대 제거 블록 수
    private int waterExtraDepth = 2;      // 표면 아래로 더 파고들 깊이
    private int waterLateralTries = 2;    // 표면 동일 Y에서 옆으로 퍼져 제거 시도

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

        // 물 증발 가속 옵션(없으면 기본값 유지)
        waterSpeedMult   = Math.max(0.1, plugin.getConfig().getDouble("doomsday.water.speed-mult", 2.5));
        waterMaxSteps    = Math.max(1,   plugin.getConfig().getInt("doomsday.water.max-steps", 4));
        waterExtraDepth  = Math.max(0,   plugin.getConfig().getInt("doomsday.water.extra-depth", 2));
        waterLateralTries= Math.max(0,   plugin.getConfig().getInt("doomsday.water.lateral-tries", 2));
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

            // ▶ 모든 생명체 일광 화상(물속 면제)
            if ((tickCount % Math.max(1, burnCheckInterval)) == 0) {
                applySunburn(w, sev);
            }
        }
    }

    /* ---------------- 표면 효과 (잔디/눈/물/나무/목조물) ---------------- */

    private int applySurfaceEffects(World w, Block top, double severity) {
        Material type = top.getType();
        Block above = top.getRelative(0, 1, 0);
        Material aboveType = above.getType();

        // 1) 물 증발 (강화판)
        if (isWater(type) || isWater(aboveType)) {
            int evaporated = evaporateWaterColumn(w, top.getX(), top.getY(), top.getZ(), severity);
            if (evaporated > 0) return evaporated;
        }

        // 2) 눈 녹이기
        if (isSnow(aboveType)) {
            if (roll(meltChance(severity))) { above.setType(Material.AIR, true); return 1; }
        }
        if (isSnow(type)) {
            if (roll(meltChance(severity))) { top.setType(Material.AIR, true); return 1; }
        }

        // 3) 잔디 → COARSE_DIRT (재확산 방지) + 위 식생/눈 제거
        if (type == Material.GRASS_BLOCK) {
            if (roll(grassDecayChance(severity))) {
                if (isVegetation(aboveType) || isSnow(aboveType)) { above.setType(Material.AIR, true); }
                top.setType(Material.COARSE_DIRT, true);
                return 1;
            }
        }

        // 4) 나무(통나무/잎) 점화
        if (isTree(type)) {
            if (roll(treeIgniteChance(severity))) {
                if (tryIgniteAround(top)) return 1;
            }
        }

        // 5) 목재 구조물(판자/계단/슬랩/울타리/문/트랩도어/간판/책장/상자 등) 점화
        if (isWoodenBlock(type)) {
            if (roll(treeIgniteChance(severity))) { // 같은 확률 재활용(원하면 config 분리 가능)
                if (tryIgniteAround(top)) return 1;
            }
        }

        return 0;
    }

    // ── 확률 함수들
    private double evaporateChance(double sev) {
        double base = plugin.getConfig().getDouble("doomsday.water.base", 0.12);
        double exp  = plugin.getConfig().getDouble("doomsday.water.exp", 1.1);
        return Math.pow(sev, exp) * base * waterSpeedMult; // ★ 가속 배수 적용
    }
    private double grassDecayChance(double sev) {
        double base = plugin.getConfig().getDouble("doomsday.grass.base", 0.12);
        double exp  = plugin.getConfig().getDouble("doomsday.grass.exp", 1.0);
        return Math.pow(sev, exp) * base;
    }
    private double treeIgniteChance(double sev) {
        double base = plugin.getConfig().getDouble("doomsday.trees.base", 0.10);
        double exp  = plugin.getConfig().getDouble("doomsday.trees.exp", 1.0);
        return Math.pow(sev, exp) * base;
    }
    private double meltChance(double sev) {
        return Math.min(1.0, 0.15 + sev * 0.6);
    }

    // ── 물 증발(강화판): 위/아래/옆으로 빠르게 줄이기
    private int evaporateWaterColumn(World w, int x, int y, int z, double sev) {
        int changed = 0;

        // 위로 0..2칸 (표면/수면)
        for (int dy = 0; dy <= 2 && changed < waterMaxSteps; dy++) {
            Block b = w.getBlockAt(x, y + dy, z);
            if (isWater(b.getType()) && roll(evaporateChance(sev))) {
                b.setType(Material.AIR, true);
                changed++;
            }
        }
        // 아래로 1..extra-depth칸 (웅덩이 바닥 쪽)
        for (int dd = 1; dd <= waterExtraDepth && changed < waterMaxSteps; dd++) {
            Block b = w.getBlockAt(x, y - dd, z);
            if (isWater(b.getType()) && roll(evaporateChance(sev) * 0.8)) {
                b.setType(Material.AIR, true);
                changed++;
            }
        }
        // 수평 확산(같은 높이 y에서 이웃도 제거 시도) — 물 웅덩이가 더 빨리 줄어듦
        for (int i = 0; i < waterLateralTries && changed < waterMaxSteps; i++) {
            int dx = (rnd.nextBoolean() ? 1 : -1) * (1 + rnd.nextInt(1));
            int dz = (rnd.nextBoolean() ? 1 : -1) * (1 + rnd.nextInt(1));
            Block b = w.getBlockAt(x + dx, y, z + dz);
            if (isWater(b.getType()) && roll(evaporateChance(sev))) {
                b.setType(Material.AIR, true);
                changed++;
            }
        }
        return changed;
    }

    private boolean isWater(Material m) {
        return m == Material.WATER || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.BUBBLE_COLUMN;
    }
    private boolean isSnow(Material m) {
        return m == Material.SNOW || m == Material.SNOW_BLOCK || m == Material.POWDER_SNOW;
    }

    // 1.21 호환(이름 기반): SHORT_GRASS/GRASS/TALL_GRASS 등 폭넓게 처리
    private boolean isVegetation(Material m) {
        String n = m.name();
        if (n.equals("SHORT_GRASS") || n.equals("GRASS") || n.equals("TALL_GRASS")) return true;
        return n.endsWith("_FLOWER") || n.endsWith("_SEEDS") || n.endsWith("_FUNGUS") || n.endsWith("_ROOTS")
                || n.endsWith("_SAPLING") || n.endsWith("_BUSH") || n.endsWith("_FERN")
                || n.endsWith("_DEAD_BUSH") || n.endsWith("_VINES") || n.endsWith("_VINE")
                || n.endsWith("_TORCHFLOWER") || n.endsWith("_PITCHER_PLANT");
    }
    private boolean isTree(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_HYPHAE")
                || n.endsWith("_STEM") || n.endsWith("_LEAVES");
    }

    // 목재 구조물(판자/계단/슬랩/울타리/문/트랩도어/간판/책장/상자/사다리 등)
    private boolean isWoodenBlock(Material m) {
        String n = m.name();
        if (n.endsWith("_PLANKS") || n.endsWith("_STAIRS") || n.endsWith("_SLAB")
                || n.endsWith("_FENCE") || n.endsWith("_FENCE_GATE")
                || n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR")
                || n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE")
                || n.endsWith("_SIGN") || n.endsWith("_HANGING_SIGN") || n.endsWith("_WALL_SIGN")) {
            return true;
        }
        switch (n) {
            case "BOOKSHELF": case "CHISELED_BOOKSHELF":
            case "NOTE_BLOCK": case "JUKEBOX":
            case "CRAFTING_TABLE": case "CARTOGRAPHY_TABLE":
            case "FLETCHING_TABLE": case "SMITHING_TABLE": case "LOOM":
            case "CHEST": case "TRAPPED_CHEST": case "BARREL":
            case "LECTERN":
            case "LADDER":
            case "SCAFFOLDING": // 대나무 足場(가연성)
                return true;
            default:
                return false;
        }
    }

    // 대상 블록 위/옆 공기칸에 불을 붙이기
    private boolean tryIgniteAround(Block base) {
        Block up = base.getRelative(0, 1, 0);
        if (up.getType().isAir()) { up.setType(Material.FIRE, true); return true; }
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : dirs) {
            Block side = base.getRelative(d[0], 0, d[1]);
            if (side.getType().isAir()) { side.setType(Material.FIRE, true); return true; }
        }
        return false;
    }

    /* ---------------- 일광 화상(물 속 면제 + 모든 LivingEntity 적용) ---------------- */
    private void applySunburn(World w, double severity) {
        for (org.bukkit.entity.LivingEntity e : w.getLivingEntities()) {
            applySunburnTo(w, e, severity);
        }
    }

    private void applySunburnTo(World w, org.bukkit.entity.LivingEntity e, double severity) {
        if (e.isDead() || e.isInvulnerable()) return;

        // 낮만 적용(0~11999)
        if (w.getTime() >= 12000) return;

        // 하늘 노출 + 밝기
        var loc = e.getLocation();
        boolean sky = w.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ()) <= loc.getBlockY();
        if (!sky) return;
        if (loc.getBlock().getLightFromSky() < 14) return;

        // 물 속/표면에 있으면 면제
        if (isSubmergedInWater(e)) {
            e.setFireTicks(0);
            return;
        }

        // 플레이어 모드 예외
        if (e instanceof Player p) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        }

        double dps = plugin.getConfig().getDouble("doomsday.burn.base-dps", 0.6)
                + plugin.getConfig().getDouble("doomsday.burn.scale-dps", 1.8) * severity;

        if (dps > 0) {
            double dmgPerTick = dps / 20.0 * burnCheckInterval;
            e.setFireTicks(Math.max(e.getFireTicks(), 40));
            e.damage(dmgPerTick);
        }
    }

    /** 엔티티가 물 속/물 표면인지 간단 판정 */
    private boolean isSubmergedInWater(org.bukkit.entity.LivingEntity e) {
        Material feet = e.getLocation().getBlock().getType();
        Material eyeB = e.getEyeLocation().getBlock().getType();
        return isWater(feet) || isWater(eyeB);
    }
}
