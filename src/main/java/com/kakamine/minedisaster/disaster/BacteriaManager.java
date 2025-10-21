package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 예전 스타일 박테리아:
 * - 시작 지점에서 주변으로 계속 퍼짐(6방향)
 * - 지정 수명 후 감염 블록은 replace-after로 복원
 * - /disaster stop bacteria 시 모든 흔적 즉시 제거
 * - config.yml의 bacteria 섹션 사용
 */
public class BacteriaManager implements Listener {

    private final MineDisaster plugin;
    private final Set<Location> infected = new HashSet<>(); // 감염된 블록 좌표(정수)
    private final Random random = new Random();

    private BukkitRunnable task;
    private boolean running = false;

    // config 캐시
    private Material infectionBlock;
    private Material replaceAfter;
    private int lifetimeTicks;
    private int tickInterval;
    private int perTick;
    private double spreadChance;
    private int maxActive; // 0이면 무제한
    private Set<Material> targetBlocks;

    public BacteriaManager(MineDisaster plugin) {
        this.plugin = plugin;
        reloadTypes();
    }

    /** config.yml 값 다시 읽기 */
    public void reloadTypes() {
        infectionBlock = Material.matchMaterial(plugin.getConfig().getString("bacteria.infection-block", "SCULK"));
        replaceAfter   = Material.matchMaterial(plugin.getConfig().getString("bacteria.replace-after", "AIR"));
        lifetimeTicks  = plugin.getConfig().getInt("bacteria.lifetime-ticks", 200);
        tickInterval   = plugin.getConfig().getInt("bacteria.tick-interval", 5);
        perTick        = plugin.getConfig().getInt("bacteria.per-tick", 120);
        spreadChance   = plugin.getConfig().getDouble("bacteria.spread-chance", 0.45);
        maxActive      = plugin.getConfig().getInt("bacteria.max-active", 0);

        targetBlocks = new HashSet<>();
        List<String> list = plugin.getConfig().getStringList("bacteria.target-blocks");
        if (list == null || list.isEmpty()) {
            // 기본 대상
            list = List.of("GRASS_BLOCK","DIRT","STONE","SAND","GRAVEL","DEEPSLATE","TUFF");
        }
        for (String s : list) {
            Material m = Material.matchMaterial(s);
            if (m != null) targetBlocks.add(m);
        }
    }

    /** 정수 좌표로 고정 */
    private Location keyOf(Block b) {
        return new Location(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    /** 박테리아 시작 */
    public void startInfection(Location start, int radius, Integer perTickOverride, int startDelay) {
        if (running) cancelAll(); // 기존 진행 있으면 정리

        running = true;
        infected.clear();

        final World world = start.getWorld();
        final int workPerTick = (perTickOverride != null && perTickOverride > 0) ? perTickOverride : perTick;

        // 초깃값: 시드 몇 개 심기 (주변 지면)
        int seeds = Math.max(1, radius * 2);
        for (int i = 0; i < seeds; i++) {
            int dx = random.nextInt(radius * 2 + 1) - radius;
            int dz = random.nextInt(radius * 2 + 1) - radius;
            int x = start.getBlockX() + dx;
            int z = start.getBlockZ() + dz;
            int y = world.getHighestBlockYAt(x, z) - 1;
            Block base = world.getBlockAt(x, y, z);
            if (targetBlocks.contains(base.getType())) {
                tryInfect(base);
            }
        }
        if (infected.isEmpty()) {
            int x = start.getBlockX(), z = start.getBlockZ();
            int y = world.getHighestBlockYAt(x, z) - 1;
            tryInfect(world.getBlockAt(x, y, z));
        }

        task = new BukkitRunnable() {
            @Override public void run() {
                if (!running) return;

                // maxActive 제한
                if (maxActive > 0 && infected.size() >= maxActive) return;

                // 감염원 중 하나를 골라 이웃으로 전파
                for (int i = 0; i < workPerTick; i++) {
                    if (infected.isEmpty()) break;

                    // 임의의 감염 지점 선택
                    Location src = infected.stream()
                            .skip(random.nextInt(Math.max(1, infected.size())))
                            .findFirst().orElse(null);
                    if (src == null) break;

                    Block b = world.getBlockAt(src);
                    spreadFrom(b);
                }
            }
        };
        task.runTaskTimer(plugin, Math.max(0, startDelay), Math.max(1, tickInterval));
        plugin.getLogger().info("박테리아 시작: radius=" + radius + ", perTick=" + workPerTick + ", world=" + world.getName());
    }

    /** 특정 블록을 감염 시도 */
    private void tryInfect(Block block) {
        if (!targetBlocks.contains(block.getType())) return;
        if (random.nextDouble() >= spreadChance) return;

        block.setType(infectionBlock, true); // 물리/유체 상호작용 유도
        Location key = keyOf(block);
        infected.add(key);

        // lifetime 이후 원복
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Block b = block.getWorld().getBlockAt(block.getX(), block.getY(), block.getZ());
            if (b.getType() == infectionBlock) {
                b.setType(replaceAfter, true);
            }
            infected.remove(key);
        }, lifetimeTicks);
    }

    /** 6방향 이웃으로 전파 */
    private void spreadFrom(Block src) {
        World w = src.getWorld();
        int x = src.getX(), y = src.getY(), z = src.getZ();

        // 이웃 후보를 랜덤 순서로 섞어서 시도
        int[][] dirs = { {1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1} };
        for (int i = 0; i < dirs.length; i++) {
            int j = random.nextInt(dirs.length);
            int[] tmp = dirs[i]; dirs[i] = dirs[j]; dirs[j] = tmp;
        }

        for (int[] d : dirs) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (ny < w.getMinHeight() || ny >= w.getMaxHeight()) continue;
            Block nb = w.getBlockAt(nx, ny, nz);
            if (targetBlocks.contains(nb.getType())) {
                tryInfect(nb);
            }
        }
    }

    /** 모든 감염 중지 + 흔적 정리 */
    public void cancelAll() {
        running = false;
        if (task != null) task.cancel();
        task = null;

        // 현재 남아있는 감염 블록 원복
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Location loc : infected) {
                Block b = loc.getBlock();
                if (b.getType() == infectionBlock) {
                    b.setType(replaceAfter, true);
                }
            }
            infected.clear();
        });

        plugin.getLogger().info("박테리아 정지 및 흔적 정리 완료");
    }

    public boolean isRunning() { return running; }
}
