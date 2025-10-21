package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Tag;

import java.util.Random;

public class DoomsdayManager {

    private final MineDisaster plugin;
    private BukkitTask task;
    private boolean running = false;

    private long startDay = -1; // world time-based용
    private final Random rnd = new Random();

    public DoomsdayManager(MineDisaster plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) return;

        if (plugin.getConfig().getBoolean("doomsday.world-time-based", true)) {
            // 월드 day 기준(오버월드 우선)
            World w = Bukkit.getWorlds().get(0);
            if (w != null) {
                startDay = w.getFullTime() / 24000L;
            }
        } else {
            startDay = 0; // ‘시작 이후 경과일’ 모드
        }

        int interval = Math.max(5, plugin.getConfig().getInt("doomsday.tick-interval", 40));
        running = true;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, interval);
        plugin.getLogger().info("[Doomsday] started.");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        running = false;
        plugin.getLogger().info("[Doomsday] stopped.");
    }

    private void tick() {
        boolean overworldOnly = plugin.getConfig().getBoolean("doomsday.affects-overworld-only", true);
        int scanRadius = Math.max(4, Math.min(plugin.getConfig().getInt("doomsday.scan-radius", 16),
                plugin.getConfig().getInt("max-radius", 64)));
        int attempts = Math.max(8, plugin.getConfig().getInt("doomsday.attempts-per-player", 64));

        int power = computePower();

        for (World world : Bukkit.getWorlds()) {
            if (overworldOnly && world.getEnvironment() != World.Environment.NORMAL) continue;

            // 효과 사운드(희미)
            world.playSound(world.getSpawnLocation(), Sound.BLOCK_FIRE_AMBIENT, 0.05f, 0.75f);

            for (Player p : world.getPlayers()) {
                // 플레이어 노출 체크
                boolean exposed = isExposedToSky(p);
                if (exposed) applySunburn(p, power);

                // 주위 표면 샘플링/효과
                for (int i=0;i<attempts;i++) {
                    int bx = p.getLocation().getBlockX() + rnd.nextInt(scanRadius*2+1) - scanRadius;
                    int bz = p.getLocation().getBlockZ() + rnd.nextInt(scanRadius*2+1) - scanRadius;
                    int by = world.getHighestBlockYAt(bx, bz);
                    if (by < world.getMinHeight()) continue;
                    Block top = world.getBlockAt(bx, by, bz);
                    if (!isExposedToSky(top)) continue; // 지붕 아래면 영향X

                    applySurfaceEffects(top, power);
                }
            }
        }
    }

    private int computePower() {
        int base = plugin.getConfig().getInt("doomsday.base-day-power", 0);
        int perDay = Math.max(0, plugin.getConfig().getInt("doomsday.power-per-day", 1));
        if (plugin.getConfig().getBoolean("doomsday.world-time-based", true)) {
            World w = Bukkit.getWorlds().get(0);
            if (w == null) return base;
            long nowDay = w.getFullTime() / 24000L;
            long passed = Math.max(0, nowDay - Math.max(0, startDay));
            return base + (int) (passed * perDay);
        } else {
            // 시작 후 경과 틱을 일 단위로 환산
            // 간단하게 task가 도는 동안 interval로 근사해도 되지만,
            // 여기서는 시작Tick 저장 없이 base만 사용(필요시 확장)
            return base; // 필요하면 경과 일 계산 로직 추가
        }
    }

    private void applySunburn(Player p, int power) {
        int base = Math.max(0, plugin.getConfig().getInt("doomsday.player-sunburn.fire-seconds-base", 2));
        int inc = Math.max(0, plugin.getConfig().getInt("doomsday.player-sunburn.fire-seconds-per-power", 1));
        int seconds = base + power * inc;
        if (seconds <= 0) return;

        // 방화구역 고려가 필요하면 여기서 예외처리 가능
        if (!p.hasPotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE)) {
            p.setFireTicks(Math.max(p.getFireTicks(), seconds * 20));
        }

        if (plugin.getConfig().getBoolean("doomsday.player-sunburn.damage-if-under-sun", false)) {
            p.damage(0.0); // 트리거용(실피는 주지 않음), 필요시 >0 값
        }
    }

    private void applySurfaceEffects(Block top, int power) {
        // 확률 계산: /1000
        double waterProb = clampedProb("doomsday.water-evaporate", power);
        double grassProb = clampedProb("doomsday.grass-decay", power);
        double treeProb  = clampedProb("doomsday.tree-ignite", power);

        Material type = top.getType();

        // 물 증발
        if (isWater(type) && roll(waterProb)) {
            // 흐르는 물/정수 물 모두 공기화
            top.setType(Material.AIR, true);
            top.getWorld().spawnParticle(Particle.CLOUD, top.getLocation().add(0.5,0.8,0.5), 8, 0.2,0.2,0.2, 0.0);
            top.getWorld().playSound(top.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.6f);
            return;
        }

        // 잔디 → 흙
        if (type == Material.GRASS_BLOCK && roll(grassProb)) {
            top.setType(Material.DIRT, true);
            top.getWorld().spawnParticle(Particle.ASH, top.getLocation().add(0.5,0.6,0.5), 6, 0.2,0.2,0.2, 0.0);
            return;
        }

        // 나무 타기(통나무/잎사귀 위에 불 붙이기)
        if ((Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type)) && roll(treeProb)) {
            Block up = top.getRelative(0,1,0);
            if (up.getType().isAir()) {
                up.setType(Material.FIRE, true);
                up.getWorld().playSound(up.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 0.8f, 0.9f);
            }
        }
    }

    private boolean isWater(Material m) {
        return m == Material.WATER || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS
                || m == Material.BUBBLE_COLUMN;
    }

    private boolean isExposedToSky(Player p) {
        World w = p.getWorld();
        int highest = w.getHighestBlockYAt(p.getLocation());
        return p.getLocation().getBlockY() >= highest; // 머리 위에 블록 없음 → 노출
    }

    private boolean isExposedToSky(Block b) {
        World w = b.getWorld();
        int highest = w.getHighestBlockYAt(b.getX(), b.getZ());
        return b.getY() >= highest;
    }

    private boolean roll(double probPerThousand) {
        if (probPerThousand <= 0) return false;
        if (probPerThousand >= 1000) return true;
        return rnd.nextInt(1000) < probPerThousand;
    }

    private double clampedProb(String key, int power) {
        int base = plugin.getConfig().getInt(key + ".base", 0);
        int inc = plugin.getConfig().getInt(key + ".inc", 0);
        int cap = plugin.getConfig().getInt(key + ".cap", 1000);
        int v = Math.min(base + power * inc, cap);
        return Math.max(0, Math.min(v, 1000));
    }
}
