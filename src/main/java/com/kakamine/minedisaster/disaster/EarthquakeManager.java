package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Random;

public class EarthquakeManager {
    private final MineDisaster plugin;
    private BukkitRunnable task;
    private final Random random = new Random();
    private boolean running = false;

    public EarthquakeManager(MineDisaster plugin) {
        this.plugin = plugin;
    }

    /** 지진 시작 */
    public void start(Location center, double magnitude, int radius, int durationSec) {
        if (running) {
            plugin.getLogger().warning("지진이 이미 발생 중입니다!");
            return;
        }
        running = true;
        World w = center.getWorld();

        task = new BukkitRunnable() {
            int ticks = 0;
            final int duration = Math.max(1, durationSec) * 20;

            @Override
            public void run() {
                if (ticks >= duration) {
                    stopAll();
                    cancel();
                    return;
                }

                // 플레이어 흔들기/효과
                for (Player p : w.getPlayers()) {
                    double dist = p.getLocation().distance(center);
                    if (dist > radius) continue;

                    double strength = Math.max(0.1, (1.0 - dist / radius)) * magnitude;

                    // 살짝 흔들기 (TP 과도 시 서버 경고가 날 수 있어 너무 큰 값은 피함)
                    if (strength > 0.2) {
                        Location loc = p.getLocation();
                        double dx = (random.nextDouble() - 0.5) * strength * 0.15;
                        double dz = (random.nextDouble() - 0.5) * strength * 0.15;
                        loc.add(dx, 0, dz);
                        p.teleport(loc);
                    }

                    // 소리
                    if (random.nextDouble() < 0.2) {
                        w.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 0.8f);
                    }
                    // 파티클: BLOCK (구 BLOCK_CRACK 대체)
                    if (random.nextDouble() < 0.35) {
                        Location l = p.getLocation().clone().add(0, 0.1, 0);
                        w.spawnParticle(
                                Particle.BLOCK,                 // ✅ BLOCK_CRACK 대체
                                l,
                                8,                               // count
                                1.0, 0.1, 1.0,                   // offsets
                                0.0,                             // extra/speed
                                Material.STONE.createBlockData() // ✅ BlockData 필수
                        );
                    }
                }

                // 주기적으로 지표 약간 붕괴
                if (ticks % 20 == 0) {
                    int attempts = Math.max(1, (int) Math.round(magnitude * 3));
                    for (int i = 0; i < attempts; i++) {
                        int dx = random.nextInt(radius * 2) - radius;
                        int dz = random.nextInt(radius * 2) - radius;
                        Location l = center.clone().add(dx, 0, dz);
                        int y = w.getHighestBlockYAt(l);
                        Block b = w.getBlockAt(l.getBlockX(), Math.max(w.getMinHeight(), y - 1), l.getBlockZ());
                        if (b.getType().isSolid() && random.nextDouble() < 0.25 && b.getType() != Material.BEDROCK) {
                            b.setType(Material.AIR, true);
                            w.spawnParticle(Particle.CLOUD, b.getLocation().add(0.5, 1, 0.5), 10, 0.3, 0.3, 0.3, 0.0);
                        }
                    }
                }

                ticks += 2; // 2틱 주기
            }
        };
        task.runTaskTimer(plugin, 0L, 2L);
        plugin.getLogger().info("지진 시작: m=" + magnitude + ", r=" + radius + ", t=" + durationSec + "s");
    }

    /** 지진 정지 */
    public void stopAll() {
        running = false;
        if (task != null) task.cancel();
        task = null;
        plugin.getLogger().info("지진 종료");
    }

    public boolean isRunning() { return running; }
}
