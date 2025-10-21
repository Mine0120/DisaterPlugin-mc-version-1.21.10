package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.block.Biome;

import java.util.Random;

public class DoomsdayManager {
    private final MineDisaster plugin;
    private BukkitTask task;
    private final Random rnd = new Random();

    private int tickInterval, samplesPerTick, startAfterDays;
    private double baseSeverity, growthPerDay;
    private boolean allowManual;

    // 스캔/성능
    private int scanRadiusChunks = 6;
    private int maxUpdatesPerTick = 80;
    private int burnCheckInterval = 20;

    // 수동 강도
    private boolean manualMode = false;
    private double manualSeverity = 0.0;

    private int tickCount = 0;

    // 물 증발 가속 파라미터
    private double waterSpeedMult = 2.5;  // 증발 확률 배수
    private int waterMaxSteps = 4;        // 일반 증발: 한 샘플에서 최대 제거 블록 수
    private int waterExtraDepth = 2;      // 일반 증발: 표면 아래로 더 파고들 깊이
    private int waterLateralTries = 2;    // 일반 증발: 수평 제거 시도

    // ★ 오션 전용 패치 증발 파라미터
    private boolean oceanModeEnabled = true;
    private int oceanPatchRadius = 2;         // 반경 2 → 5×5
    private int oceanVerticalSteps = 4;       // 아래로 몇 칸까지 지울지
    private int oceanPatchBudget = 24;        // 패치 하나에서 지울 수 있는 최대 블록 수
    private double oceanSpeedMult = 3.0;      // 오션 패치 시 추가 가속 배수
    private int oceanSeaWindow = 2;           // 해수면 ±2 높이에서만 패치 우선 적용

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

        // 일반 물 증발
        waterSpeedMult   = Math.max(0.1, plugin.getConfig().getDouble("doomsday.water.speed-mult", 2.5));
        waterMaxSteps    = Math.max(1,   plugin.getConfig().getInt("doomsday.water.max-steps", 4));
        waterExtraDepth  = Math.max(0,   plugin.getConfig().getInt("doomsday.water.extra-depth", 2));
        waterLateralTries= Math.max(0,   plugin.getConfig().getInt("doomsday.water.lateral-tries", 2));

        // 오션 전용
        oceanModeEnabled   = plugin.getConfig().getBoolean("doomsday.water.ocean.enabled", true);
        oceanPatchRadius   = Math.max(1, plugin.getConfig().getInt("doomsday.water.ocean.patch-radius", 2));
        oceanVerticalSteps = Math.max(1, plugin.getConfig().getInt("doomsday.water.ocean.vertical-steps", 4));
        oceanPatchBudget   = Math.max(4, plugin.getConfig().getInt("doomsday.water.ocean.per-patch-budget", 24));
        oceanSpeedMult     = Math.max(1.0, plugin.getConfig().getDouble("doomsday.water.ocean.speed-mult", 3.0));
        oceanSeaWindow     = Math.max(0, plugin.getConfig().getInt("doomsday.water.ocean.sea-window", 2));
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

        // ★ 오션 전용: 해수면 근처 + 바다/강/해변 바이옴이면 패치 증발 우선 적용
        if (oceanModeEnabled && (isWater(type) || isWater(aboveType))) {
            final int sea = safeSeaLevel(w);
            final int y = top.getY();
            if (Math.abs(y - sea) <= oceanSeaWindow && isOceanicBiome(w, top.getX(), y, top.getZ())) {
                return evaporateOceanPatch(w, top.getX(), y, top.getZ(), severity);
            }
        }

        // 1) 일반 물 증발 (근원 우선 + 워터로그드 + 상류 추적)
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
            if (roll(treeIgniteChance(severity))) {
                if (tryIgniteAround(top)) return 1;
            }
        }

        return 0;
    }

    // ── 확률 함수들
    private double evaporateChance(double sev) {
        double base = plugin.getConfig().getDouble("doomsday.water.base", 0.12);
        double exp  = plugin.getConfig().getDouble("doomsday.water.exp", 1.1);
        return Math.pow(sev, exp) * base * waterSpeedMult; // 일반 가속 배수
    }
    private double evaporateChanceOcean(double sev) {
        return evaporateChance(sev) * oceanSpeedMult; // 오션 추가 가속
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

    /* ── 일반 물 증발(근원 우선): 근원(source) 제거 + 워터로그드 제거 + 상류 얕은 추적 ── */
    private int evaporateWaterColumn(World w, int x, int y, int z, double sev) {
        int changed = 0;
        int budget = waterMaxSteps;

        // 0) 해당 칸 워터로그드 제거
        Block here = w.getBlockAt(x, y, z);
        if (clearWaterlogged(here)) {
            changed++; if (--budget <= 0) return changed;
        }

        // 1) 표면/수면/바로 아래
        for (int dy = 0; dy <= 2 && budget > 0; dy++) {
            Block b = w.getBlockAt(x, y + dy, z);
            if (isWaterSource(b) && roll(evaporateChance(sev))) {
                b.setType(Material.AIR, false);
                changed++; budget--;
            } else if (isFlowingWater(b)) {
                changed += drainUpstreamSources(b, sev, Math.min(2, budget));
                budget = waterMaxSteps - changed;
            } else {
                clearWaterlogged(b);
            }
        }

        // 2) 아래로 파고듦
        for (int dd = 1; dd <= waterExtraDepth && budget > 0; dd++) {
            Block b = w.getBlockAt(x, y - dd, z);
            if (isWaterSource(b) && roll(evaporateChance(sev) * 0.8)) {
                b.setType(Material.AIR, false);
                changed++; budget--;
            } else if (isFlowingWater(b)) {
                changed += drainUpstreamSources(b, sev, Math.min(2, budget));
                budget = waterMaxSteps - changed;
            } else {
                clearWaterlogged(b);
            }
        }

        // 3) 수평 확산
        int r = Math.min(3, 1 + (int)Math.round(severityClamp(sev) * 2));
        for (int i = 0; i < waterLateralTries && budget > 0; i++) {
            int dx = rnd.nextInt(r * 2 + 1) - r;
            int dz = rnd.nextInt(r * 2 + 1) - r;
            Block b = w.getBlockAt(x + dx, y, z + dz);
            if (isWaterSource(b) && roll(evaporateChance(sev))) {
                b.setType(Material.AIR, false);
                changed++; budget--;
            } else if (isFlowingWater(b)) {
                changed += drainUpstreamSources(b, sev, Math.min(2, budget));
                budget = waterMaxSteps - changed;
            } else {
                clearWaterlogged(b);
            }
        }
        return changed;
    }

    /* ── 오션 패치 증발: 표면 패치(반경 R) 내부 근원 물 다수 제거 + 상류 추적 ── */
    private int evaporateOceanPatch(World w, int x, int y, int z, double sev) {
        int changed = 0;
        int budget = oceanPatchBudget;
        int R = oceanPatchRadius;
        int sea = safeSeaLevel(w);

        // 패치 내부: 위→아래로 훑으면서 근원/흐름 처리
        for (int dx = -R; dx <= R && budget > 0; dx++) {
            for (int dz = -R; dz <= R && budget > 0; dz++) {
                // 살짝 원형 느낌
                if (dx*dx + dz*dz > R*R + R) continue;

                // 표면 y 기준으로 위/아래 범위
                for (int dy = 0; dy <= oceanVerticalSteps && budget > 0; dy++) {
                    Block bUp = w.getBlockAt(x + dx, y + dy, z + dz);
                    if (isWaterSource(bUp) && roll(evaporateChanceOcean(sev))) {
                        bUp.setType(Material.AIR, false);
                        changed++; if (--budget <= 0) break;
                    } else if (isFlowingWater(bUp)) {
                        changed += drainUpstreamSources(bUp, sev, Math.min(3, budget));
                        budget = oceanPatchBudget - changed;
                    } else {
                        clearWaterlogged(bUp);
                    }
                }
                for (int dd = 1; dd <= oceanVerticalSteps && budget > 0; dd++) {
                    Block bDown = w.getBlockAt(x + dx, y - dd, z + dz);
                    // 해수면보다 훨씬 아래는 너무 깊으니 확률 낮춤
                    double mult = (y - dd >= sea - 6) ? 1.0 : 0.6;
                    if (isWaterSource(bDown) && roll(evaporateChanceOcean(sev) * mult)) {
                        bDown.setType(Material.AIR, false);
                        changed++; if (--budget <= 0) break;
                    } else if (isFlowingWater(bDown)) {
                        changed += drainUpstreamSources(bDown, sev, Math.min(3, budget));
                        budget = oceanPatchBudget - changed;
                    } else {
                        clearWaterlogged(bDown);
                    }
                }
            }
        }
        return changed;
    }

    /* ── 판정/유틸 ── */

    private int safeSeaLevel(World w) {
        try { return w.getSeaLevel(); } catch (Throwable t) { return 62; }
    }

    private boolean isOceanicBiome(World w, int x, int y, int z) {
        Biome b;
        try { b = w.getBiome(x, y, z); }
        catch (Throwable t) { b = w.getBiome(x, z); } // 구버전 호환
        String n = b.name();
        return n.contains("OCEAN") || n.equals("BEACH") || n.equals("RIVER");
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
            case "SCAFFOLDING":
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

    // 확률 롤
    private boolean roll(double p) {
        if (p <= 0) return false;
        if (p >= 1) return true;
        return rnd.nextDouble() < p;
    }

    /** 0~1 클램프 */
    private double severityClamp(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** '근원 물'(Level 0) 판정 */
    private boolean isWaterSource(Block b) {
        if (b.getType() != Material.WATER) return false;
        org.bukkit.block.data.BlockData data = b.getBlockData();
        if (data instanceof org.bukkit.block.data.Levelled lv) {
            return lv.getLevel() == 0; // Level 0 = source
        }
        return true;
    }

    /** 흐르는 물 (= WATER & Level > 0) */
    private boolean isFlowingWater(Block b) {
        if (b.getType() != Material.WATER) return false;
        org.bukkit.block.data.BlockData data = b.getBlockData();
        return (data instanceof org.bukkit.block.data.Levelled lv) && lv.getLevel() > 0;
    }

    /** 워터로그드 물기 제거 */
    private boolean clearWaterlogged(Block b) {
        org.bukkit.block.data.BlockData data = b.getBlockData();
        if (data instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) {
            wl.setWaterlogged(false);
            b.setBlockData(wl, true);
            return true;
        }
        return false;
    }

    /**
     * 흐르는 물 기준 이웃에서 근원(source) 찾아 제거 (얕은 추적 + 예산 제한)
     */
    private int drainUpstreamSources(Block b, double sev, int budget) {
        if (budget <= 0) return 0;
        int removed = 0;

        final int[][] dirs = { {0,0,-1}, {-1,0,0}, {1,0,0}, {0,0,1}, {0,1,0} };
        final int bx = b.getX(), by = b.getY(), bz = b.getZ();
        final World w = b.getWorld();

        for (int[] d : dirs) {
            if (removed >= budget) break;
            Block nb = w.getBlockAt(bx + d[0], by + d[1], bz + d[2]);
            if (isWaterSource(nb) && roll(evaporateChance(sev))) {
                nb.setType(Material.AIR, true);
                removed++;
            } else {
                if (isFlowingWater(nb) && budget - removed > 0) {
                    removed += drainUpstreamSourcesOneStep(nb, sev, budget - removed);
                }
            }
        }
        return removed;
    }

    /** 상류 한 단계만 더 살피는 가벼운 탐색 */
    private int drainUpstreamSourcesOneStep(Block b, double sev, int budget) {
        if (budget <= 0) return 0;
        int removed = 0;
        final int[][] dirs = { {0,0,-1}, {-1,0,0}, {1,0,0}, {0,0,1}, {0,1,0} };
        final int bx = b.getX(), by = b.getY(), bz = b.getZ();
        final World w = b.getWorld();

        for (int[] d : dirs) {
            if (removed >= budget) break;
            Block nb = w.getBlockAt(bx + d[0], by + d[1], bz + d[2]);
            if (isWaterSource(nb) && roll(evaporateChance(sev))) {
                nb.setType(Material.AIR, true);
                removed++;
            }
        }
        return removed;
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
