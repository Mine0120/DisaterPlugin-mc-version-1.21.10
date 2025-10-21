package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import com.kakamine.minedisaster.util.BlockUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class BacteriaManager {
    private final MineDisaster plugin;
    private final Set<BukkitTask> tasks = new HashSet<>();
    private final Random rnd = new Random();

    private Set<Material> targetBlocks;
    private Material replaceWith;

    public BacteriaManager(MineDisaster plugin) {
        this.plugin = plugin;
        reloadTypes();
    }

    public void reloadTypes() {
        targetBlocks = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("bacteria.target-blocks")) {
            Material m = Material.matchMaterial(s);
            if (m != null) targetBlocks.add(m);
        }
        replaceWith = Material.matchMaterial(plugin.getConfig().getString("bacteria.replace-with","AIR"));
        if (replaceWith == null) replaceWith = Material.AIR;
    }

    public void cancelAll() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }

    public void startInfection(Location origin, int startRadius, int perTick, int maxTotal) {
        World w = origin.getWorld();
        if (w == null) return;

        int interval = plugin.getConfig().getInt("bacteria.tick-interval", 5);

        // 초기 감염원 큐
        Queue<Block> frontier = new ArrayDeque<>();
        Set<Block> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        // 씨앗 심기
        for (Block b : BlockUtils.blocksInSphere(w, origin, startRadius)) {
            if (isTarget(b)) {
                frontier.add(b);
                visited.add(b);
            }
        }

        final int[] total = {0};

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (frontier.isEmpty() || total[0] >= maxTotal) {
                cancelAll();
                return;
            }

            int processed = 0;
            while (!frontier.isEmpty() && processed < perTick && total[0] < maxTotal) {
                Block cur = frontier.poll();
                if (cur == null) break;

                if (isTarget(cur)) {
                    cur.setType(replaceWith, false);
                    total[0]++;
                    w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, cur.getLocation().add(0.5,0.5,0.5), 2, 0.2,0.2,0.2, 0.0);
                    w.playSound(cur.getLocation(), Sound.BLOCK_HONEY_BLOCK_BREAK, 0.2f, 0.9f + rnd.nextFloat()*0.2f);
                }

                for (Block nb : BlockUtils.neighbors6(cur)) {
                    if (!visited.contains(nb) && isTarget(nb)) {
                        frontier.add(nb);
                        visited.add(nb);
                    }
                }
                processed++;
            }
        }, 0L, interval);

        tasks.add(task);
    }

    private boolean isTarget(Block b) {
        return targetBlocks.contains(b.getType());
    }
}
