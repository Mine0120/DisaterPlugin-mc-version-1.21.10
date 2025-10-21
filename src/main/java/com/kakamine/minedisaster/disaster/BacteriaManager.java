package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class BacteriaManager implements Listener {

    private final MineDisaster plugin;

    // 설정 캐시
    private Material infectionBlock, replaceAfter;
    private int lifetimeTicks, tickInterval, perTick, maxActive;
    private double spreadChance;
    private final Set<Material> targetBlocks = new HashSet<>();

    // 상태
    private BukkitRunnable task;
    private boolean running = false;

    // 프론티어(퍼질 후보)와 기록
    private final Deque<BlockPos> frontier = new ArrayDeque<>();
    private final Set<BlockPos> visited = new HashSet<>();
    private final Set<BlockPos> placedNow = new HashSet<>();

    public BacteriaManager(MineDisaster plugin) {
        this.plugin = plugin;
        reloadTypes();
    }

    public void reloadTypes() {
        infectionBlock = Material.matchMaterial(plugin.getConfig().getString("bacteria.infection-block", "SCULK"));
        replaceAfter   = Material.matchMaterial(plugin.getConfig().getString("bacteria.replace-after", "AIR"));
        lifetimeTicks  = plugin.getConfig().getInt("bacteria.lifetime-ticks", 200);
        tickInterval   = plugin.getConfig().getInt("bacteria.tick-interval", 5);
        perTick        = plugin.getConfig().getInt("bacteria.per-tick", 120);
        spreadChance   = plugin.getConfig().getDouble("bacteria.spread-chance", 0.45);
        maxActive      = plugin.getConfig().getInt("bacteria.max-active", 0);

        targetBlocks.clear();
        for (String s : plugin.getConfig().getStringList("bacteria.target-blocks")) {
            Material m = Material.matchMaterial(s);
            if (m != null) targetBlocks.add(m);
        }
        if (targetBlocks.isEmpty()) {
            targetBlocks.addAll(Arrays.asList(Material.GRASS_BLOCK, Material.DIRT, Material.STONE, Material.DEEPSLATE, Material.SAND, Material.GRAVEL, Material.TUFF));
        }
    }

    private record BlockPos(World w, int x, int y, int z) {}
    private BlockPos posOf(Block b){ return new BlockPos(b.getWorld(), b.getX(), b.getY(), b.getZ()); }

    public void startInfection(Location start, int seedRadius, Integer perTickOverride, int startDelay) {
        if (running) cancelAll();

        running = true;
        frontier.clear();
        visited.clear();
        placedNow.clear();

        World w = start.getWorld();
        Random rnd = new Random();
        int seeds = Math.max(1, seedRadius * 2);
        for (int i = 0; i < seeds; i++) {
            int dx = rnd.nextInt(seedRadius * 2 + 1) - seedRadius;
            int dz = rnd.nextInt(seedRadius * 2 + 1) - seedRadius;
            int x = start.getBlockX() + dx;
            int z = start.getBlockZ() + dz;
            int y = w.getHighestBlockYAt(x, z);
            Block b = w.getBlockAt(x, y - 1, z);
            if (targetBlocks.contains(b.getType())) frontier.add(new BlockPos(w, x, y - 1, z));
        }
        if (frontier.isEmpty()) {
            int x = start.getBlockX(), z = start.getBlockZ();
            int y = w.getHighestBlockYAt(x, z);
            frontier.add(new BlockPos(w, x, y - 1, z));
        }

        final int workPerTick = (perTickOverride != null && perTickOverride > 0) ? perTickOverride : perTick;

        task = new BukkitRunnable() {
            @Override public void run() {
                if (!running) return;
                int work = 0;
                while (work < workPerTick && !frontier.isEmpty()) {
                    BlockPos p = frontier.pollFirst();
                    if (p == null) break;
                    if (visited.contains(p)) continue;
                    visited.add(p);

                    Block b = p.w.getBlockAt(p.x, p.y, p.z);
                    if (!targetBlocks.contains(b.getType())) continue;

                    if (Math.random() < spreadChance) {
                        b.setType(infectionBlock, true); // 물리 적용
                        placedNow.add(p);

                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            Block bb = p.w.getBlockAt(p.x, p.y, p.z);
                            if (bb.getType() == infectionBlock) bb.setType(replaceAfter, true);
                            placedNow.remove(p);
                        }, lifetimeTicks);

                        for (int[] d : DIR6) {
                            int nx = p.x + d[0], ny = p.y + d[1], nz = p.z + d[2];
                            if (ny < p.w.getMinHeight() || ny >= p.w.getMaxHeight()) continue;
                            BlockPos np = new BlockPos(p.w, nx, ny, nz);
                            if (!visited.contains(np)) frontier.addLast(np);
                        }
                        work++;
                        if (maxActive > 0 && placedNow.size() >= maxActive) break;
                    }
                }
            }
        };
        task.runTaskTimer(plugin, Math.max(0, startDelay), Math.max(1, tickInterval));
        plugin.getLogger().info("박테리아 시작: seeds=" + seeds + ", perTick=" + workPerTick + ", world=" + w.getName());
    }

    private static final int[][] DIR6 = new int[][]{
            { 1,0,0 }, {-1,0,0 }, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}
    };

    public void cancelAll() {
        running = false;
        if (task != null) task.cancel();
        task = null;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (BlockPos p : placedNow) {
                Block b = p.w.getBlockAt(p.x, p.y, p.z);
                if (b.getType() == infectionBlock) b.setType(replaceAfter, true);
            }
            placedNow.clear(); frontier.clear(); visited.clear();
        });
    }

    public boolean isRunning() { return running; }
}
