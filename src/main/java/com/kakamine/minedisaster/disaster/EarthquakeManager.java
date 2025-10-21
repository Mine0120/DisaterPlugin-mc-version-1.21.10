package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import com.kakamine.minedisaster.util.BlockUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public class EarthquakeManager {
    private final MineDisaster plugin;
    private final Set<BukkitTask> tasks = new HashSet<>();
    private final Random rnd = new Random();

    public EarthquakeManager(MineDisaster plugin) { this.plugin = plugin; }

    public void cancelAll() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }

    public void startEarthquake(Location center, int radius, int seconds) {
        World w = center.getWorld();
        if (w == null) return;

        double fissureChance = plugin.getConfig().getDouble("earthquake.fissure-chance", 0.15);
        double knock = plugin.getConfig().getDouble("earthquake.knock-strength", 0.7);
        int depth = plugin.getConfig().getInt("earthquake.breakable-depth", 3);

        final int[] ticksLeft = { seconds * 20 };

        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (ticksLeft[0] <= 0) {
                cancelAll();
                return;
            }
            ticksLeft[0] -= 6;

            // 효과음/파티클
            w.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.5f, 0.5f + rnd.nextFloat()*0.3f);
            w.spawnParticle(Particle.BLOCK, center, 50, radius, 0.5, radius, 0, Material.STONE.createBlockData());

            // 플레이어 흔들기
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(center) <= radius*radius) {
                    Vector v = new Vector((rnd.nextDouble()-0.5)*knock, 0.12, (rnd.nextDouble()-0.5)*knock);
                    p.setVelocity(p.getVelocity().add(v));
                    p.spawnParticle(Particle.SWEEP_ATTACK, p.getLocation(), 2);
                }
            }

            // 균열 생성 + 지면 파괴
            for (int i=0;i<Math.max(4, radius/2);i++) {
                double ang = rnd.nextDouble()*Math.PI*2;
                int len = 3 + rnd.nextInt(Math.max(3, radius/3));
                Location cur = center.clone();
                for (int j=0;j<len;j++) {
                    cur.add(Math.cos(ang), 0, Math.sin(ang));
                    Block top = BlockUtils.getTopSolidOrLiquidBlock(w, cur.getBlockX(), cur.getBlockZ(), center.getY()+5);
                    if (top == null) continue;

                    if (rnd.nextDouble() < fissureChance) {
                        for (int d=0; d<depth; d++) {
                            Block b = w.getBlockAt(top.getX(), top.getY()-d, top.getZ());
                            if (BlockUtils.canBreak(b.getType())) {
                                w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5,0.5,0.5), 8, 0.2,0.2,0.2, 0, b.getBlockData());
                                b.setType(Material.AIR, false);
                            }
                        }
                        w.playSound(top.getLocation(), Sound.BLOCK_DEEPSLATE_BREAK, 0.7f, 0.6f + rnd.nextFloat()*0.4f);
                    }
                }
            }
        }, 0L, 6L);

        tasks.add(t);
    }
}
