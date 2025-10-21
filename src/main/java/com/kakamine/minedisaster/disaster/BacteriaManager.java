package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.Listener; // ✅ 이벤트 등록용
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 박테리아 감염 시스템
 * - 특정 지점에서 시작해서 주변 블록으로 계속 확산
 * - 스컬크(SCULK) 블록으로 변환 후 일정 시간 후 사라짐
 * - /disaster stop bacteria 명령으로 중지 가능
 */
public class BacteriaManager implements Listener {

    private final MineDisaster plugin;
    private final Set<Location> infected = new HashSet<>();
    private final Random random = new Random();

    private BukkitRunnable task;
    private boolean running = false;

    // config 값 캐싱
    private Material infectionBlock;
    private Material replaceAfter;
    private int lifetimeTicks;
    private int tickInterval;
    private int perTick;
    private double spreadChance;
    private int maxActive;
    private Set<Material> targetBlocks;

    public BacteriaManager(MineDisaster plugin) {
        this.plugin = plugin;
        reloadTypes();
    }

    /** config.yml 값을 다시 읽음 */
    public void reloadTypes() {
        infectionBlock = Material.matchMaterial(plugin.getConfig().getString("bacteria.infection-block", "SCULK"));
        replaceAfter = Material.matchMaterial(plugin.getConfig().getString("bacteria.replace-after", "AIR"));
        lifetimeTicks = plugin.getConfig().getInt("bacteria.lifetime-ticks", 200);
        tickInterval = plugin.getConfig().getInt("bacteria.tick-interval", 5);
        perTick = plugin.getConfig().getInt("bacteria.per-tick", 120);
        spreadChance = plugin.getConfig().getDouble("bacteria.spread-chance", 0.4);
        maxActive = plugin.getConfig().getInt("bacteria.max-active", 0);

        targetBlocks = new HashSet<>();
        List<String> list = plugin.getConfig().getStringList("bacteria.target-blocks");
        for (String s : list) {
            Material m = Material.matchMaterial(s);
            if (m != null) targetBlocks.add(m);
        }
    }

    /** 감염 시작 */
    public void startInfection(Location start, int radius, int perTickOverride, int startDelay) {
        if (running) {
            plugin.getLogger().warning("박테리아가 이미 진행 중입니다!");
            return;
        }

        running = true;
        infected.clear();

        int actualPerTick = (perTickOverride > 0 ? perTickOverride : perTick);
        World world = start.getWorld();

        task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running) return;

                // active 제한
                if (maxActive > 0 && infected.size() > maxActive) return;

                Set<Location> newInfect = new HashSet<>();

                for (int i = 0; i < actualPerTick; i++) {
                    // 주변 임의의 감염 지점 선택
                    Location loc;
                    if (infected.isEmpty()) {
                        loc = start.clone().add(
                                random.nextInt(radius * 2) - radius,
                                random.nextInt(4) - 2,
                                random.nextInt(radius * 2) - radius
                        );
                    } else {
                        loc = infected.stream().skip(random.nextInt(infected.size())).findFirst().orElse(start);
                    }

                    // 6방향 확산
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue; // 상하좌우전후만

                                Location nloc = loc.clone().add(dx, dy, dz);
                                Block block = world.getBlockAt(nloc);

                                if (targetBlocks.contains(block.getType()) && random.nextDouble() < spreadChance) {
                                    block.setType(infectionBlock, true);
                                    newInfect.add(nloc.clone());

                                    // 수명 후 사라짐
                                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                        if (block.getType() == infectionBlock) {
                                            block.setType(replaceAfter, true);
                                        }
                                    }, lifetimeTicks);
                                }
                            }
                        }
                    }
                }

                infected.addAll(newInfect);
            }
        };

        task.runTaskTimer(plugin, startDelay, tickInterval);
        plugin.getLogger().info("박테리아 감염 시작됨 (" + world.getName() + ")");
    }

    /** 모든 감염 정지 및 흔적 제거 */
    public void cancelAll() {
        running = false;
        if (task != null) task.cancel();
        task = null;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Location loc : infected) {
                Block block = loc.getBlock();
                if (block.getType() == infectionBlock) {
                    block.setType(replaceAfter, true);
                }
            }
            infected.clear();
        });

        plugin.getLogger().info("박테리아 감염 정지 및 흔적 정리 완료");
    }

    public boolean isRunning() {
        return running;
    }
}
