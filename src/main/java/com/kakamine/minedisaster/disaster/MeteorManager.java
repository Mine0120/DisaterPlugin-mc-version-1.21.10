package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import com.kakamine.minedisaster.util.BlockUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * 고퀄 메테오(랜덤 낙하 + 대형 이중 구 + 파편 + 스코치 + 분화구 지형 파괴 + 폭발력-크기 연동)
 */
public class MeteorManager implements Listener {
    private final MineDisaster plugin;
    private final Random rnd = new Random();

    private final Map<UUID, UUID> partToMeteor = new HashMap<>();
    private final Map<UUID, Set<UUID>> meteorParts = new HashMap<>();
    private final Map<UUID, List<BukkitTask>> meteorTasks = new HashMap<>();

    public MeteorManager(MineDisaster plugin) { this.plugin = plugin; }

    public void cancelAll() {
        meteorTasks.values().forEach(list -> list.forEach(BukkitTask::cancel));
        meteorTasks.clear();
        for (UUID partId : new ArrayList<>(partToMeteor.keySet())) {
            Entity e = Bukkit.getEntity(partId);
            if (e != null) e.remove();
        }
        partToMeteor.clear();
        meteorParts.clear();
    }

    public void spawnMeteorShower(World world, Location center, int count, int requestedPower) {
        final boolean ignite = plugin.getConfig().getBoolean("meteor.ignite-fire", true);
        final int addY = plugin.getConfig().getInt("meteor.spawn-height", 80);
        final int timeoutSec = Math.max(5, plugin.getConfig().getInt("meteor.timeout-seconds", 20));
        final int randRadius = Math.max(0, plugin.getConfig().getInt("meteor.random-radius", 22));

        // 폭발력 상향 + 안전 상한
        int maxP = Math.max(10, plugin.getConfig().getInt("meteor.explosion-max", 20));
        int cfgP = plugin.getConfig().getInt("meteor.explosion-power", 12);
        int power = Math.max(1, Math.min((requestedPower > 0 ? requestedPower : cfgP), maxP));

        // 재질
        final Material surfaceMat = materialOr("meteor.surface-material", Material.MAGMA_BLOCK);
        final Material coreMat    = materialOr("meteor.core-material", Material.DEEPSLATE);

        // 반경(★폭발력에 따라 자동 스케일)
        final double baseSurfaceR = Math.max(1.6, plugin.getConfig().getDouble("meteor.base-surface-radius", 3.2));
        final double baseCoreR    = Math.max(0.9, plugin.getConfig().getDouble("meteor.base-core-radius", 2.0));
        final double scalePerPow  = Math.max(0.0, plugin.getConfig().getDouble("meteor.radius-scale-per-power", 0.18));
        final double scale = 1.0 + (power * scalePerPow);

        final double surfaceR = baseSurfaceR * scale;
        final double coreR    = baseCoreR * scale;

        final int surfaceN    = Math.max(16, Math.min(plugin.getConfig().getInt("meteor.surface-pieces", 110), 320));
        final int coreN       = Math.max(8,  Math.min(plugin.getConfig().getInt("meteor.core-pieces", 42), 160));

        final Particle trailP  = Particle.valueOf(plugin.getConfig().getString("meteor.trail-particle", "FLAME"));
        final Particle smokeP  = Particle.valueOf(plugin.getConfig().getString("meteor.smoke-particle", "LARGE_SMOKE"));
        final int trailDensity = Math.max(1, Math.min(plugin.getConfig().getInt("meteor.trail-density", 2), 6));
        final double speed     = plugin.getConfig().getDouble("meteor.fall-speed", 1.35);
        final double gravBias  = plugin.getConfig().getDouble("meteor.gravity-bias", -0.30);
        final double spreadDeg = Math.max(0, Math.min(25, plugin.getConfig().getDouble("meteor.spread-angle-deg", 12)));

        for (int i = 0; i < count; i++) {
            // 타겟을 중심으로 '랜덤 낙하지점'
            Location tgt = center.clone().add(
                    (rnd.nextDouble() * 2 - 1) * randRadius,
                    0,
                    (rnd.nextDouble() * 2 - 1) * randRadius
            );

            // 스폰 위치: 타겟 위쪽 (약간 랜덤)
            Location spawn = tgt.clone().add(rnd.nextGaussian() * 2, addY + rnd.nextInt(20), rnd.nextGaussian() * 2);

            // 속도
            Vector vel = tgt.toVector().subtract(spawn.toVector()).normalize()
                    .multiply(speed).add(new Vector(0, gravBias, 0));
            if (spreadDeg > 0) vel = coneJitter(vel, Math.toRadians(spreadDeg));

            // 메테오 ID
            UUID meteorId = UUID.randomUUID();
            meteorParts.put(meteorId, new HashSet<>());
            meteorTasks.put(meteorId, new ArrayList<>());

            // 표면
            for (Vector off : fibonacciSphere(surfaceR, surfaceN)) {
                spawnMeteorPiece(world, spawn.clone().add(off), surfaceMat, vel, meteorId, trailP, trailDensity);
            }
            // 코어
            for (Vector off : fibonacciSphere(coreR, coreN)) {
                spawnMeteorPiece(world, spawn.clone().add(off), coreMat, vel, meteorId, trailP, trailDensity);
            }

            world.playSound(spawn, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.6f + rnd.nextFloat() * 0.4f);

            // 타임아웃: 현재 평균 위치에서 폭발/분화구
            BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!meteorParts.containsKey(meteorId)) return;
                Location avg = averageLocationOfMeteor(meteorId, world, spawn);
                triggerExplosionAndCrater(meteorId, avg, power, ignite, smokeP);
            }, timeoutSec * 20L);
            meteorTasks.get(meteorId).add(timeout);
        }
    }

    private void spawnMeteorPiece(World w, Location p, Material mat, Vector vel, UUID meteorId,
                                  Particle trailP, int trailDensity) {
        FallingBlock fb = w.spawnFallingBlock(p, mat.createBlockData());
        fb.setDropItem(false);
        fb.setHurtEntities(true);
        fb.setVelocity(vel);

        UUID pid = fb.getUniqueId();
        partToMeteor.put(pid, meteorId);
        meteorParts.get(meteorId).add(pid);

        BukkitTask tail = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!fb.isValid()) return;
            Location l = fb.getLocation();
            w.spawnParticle(trailP, l, trailDensity, 0, 0, 0, 0);
            w.spawnParticle(Particle.SMALL_FLAME, l, 1, 0, 0, 0, 0);
        }, 1L, 1L);
        meteorTasks.get(meteorId).add(tail);
    }

    @EventHandler
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof FallingBlock fb)) return;
        UUID pid = fb.getUniqueId();
        UUID meteorId = partToMeteor.get(pid);
        if (meteorId == null) return;

        e.setCancelled(true);
        Location impact = e.getBlock().getLocation().add(0.5, 0.0, 0.5);

        final boolean ignite = plugin.getConfig().getBoolean("meteor.ignite-fire", true);
        int cfgP = plugin.getConfig().getInt("meteor.explosion-power", 12);
        int maxP = Math.max(10, plugin.getConfig().getInt("meteor.explosion-max", 20));
        int power = Math.max(1, Math.min(cfgP, maxP));

        final Particle smokeP = Particle.valueOf(plugin.getConfig().getString("meteor.smoke-particle", "LARGE_SMOKE"));

        triggerExplosionAndCrater(meteorId, impact, power, ignite, smokeP);
    }

    /** 폭발 + 스코치 + 파편 + 분화구 + 정리까지 한 번에 */
    private void triggerExplosionAndCrater(UUID meteorId, Location loc, int power, boolean ignite, Particle smokeP) {
        World w = loc.getWorld();
        if (w != null) {
            // ★ 폭발
            w.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1);
            w.createExplosion(loc, power, ignite, true);
            w.spawnParticle(smokeP, loc, 180, 2.8, 1.9, 2.8, 0.04);

            // 충격파
            for (Player pl : w.getPlayers()) {
                double d2 = pl.getLocation().distanceSquared(loc);
                if (d2 <= 80 * 80) {
                    Vector push = pl.getLocation().toVector().subtract(loc.toVector()).normalize().multiply(0.70);
                    push.setY(0.42);
                    pl.setVelocity(pl.getVelocity().add(push));
                    pl.spawnParticle(Particle.SONIC_BOOM, pl.getLocation(), 1);
                    w.playSound(pl, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.75f);
                }
            }

            // 스코치
            applyScorch(w, loc);

            // ★ 분화구 지형 파괴
            if (plugin.getConfig().getBoolean("meteor.crater.enabled", true)) {
                carveCrater(w, loc, power);
            }

            // 파편(폭발력에 비례)
            spawnDebris(w, loc, power);
        }

        // 엔티티/태스크 정리
        Set<UUID> parts = meteorParts.remove(meteorId);
        if (parts != null) {
            for (UUID pid : parts) {
                Entity e = Bukkit.getEntity(pid);
                if (e != null) e.remove();
                partToMeteor.remove(pid);
            }
        }
        List<BukkitTask> tasks = meteorTasks.remove(meteorId);
        if (tasks != null) tasks.forEach(BukkitTask::cancel);
    }

    /** 파편: 폭발력 비례로 수량 증가 */
    private void spawnDebris(World w, Location origin, int power) {
        int base = Math.max(0, plugin.getConfig().getInt("meteor.debris.base-count", 45));
        int perP = Math.max(0, plugin.getConfig().getInt("meteor.debris.per-power", 2));
        int count = base + perP * Math.max(0, power);

        if (count == 0) return;

        Material m1 = materialOr("meteor.debris.material-1", Material.DEEPSLATE);
        Material m2 = materialOr("meteor.debris.material-2", Material.MAGMA_BLOCK);
        Material m3 = materialOr("meteor.debris.material-3", Material.COBBLESTONE);

        double vMin = plugin.getConfig().getDouble("meteor.debris.speed-min", 0.6);
        double vMax = plugin.getConfig().getDouble("meteor.debris.speed-max", 1.9);
        double up   = plugin.getConfig().getDouble("meteor.debris.up-boost", 0.40);
        double placeChance = plugin.getConfig().getDouble("meteor.debris.place-chance", 0.55);

        for (int i = 0; i < count; i++) {
            Material mat = switch (rnd.nextInt(3)) { case 0 -> m1; case 1 -> m2; default -> m3; };
            FallingBlock fb = w.spawnFallingBlock(origin, mat.createBlockData());
            fb.setDropItem(false);
            fb.setHurtEntities(true);

            Vector dir = randomUnitHemisphere().multiply(vMin + rnd.nextDouble() * (vMax - vMin));
            dir.setY(Math.abs(dir.getY()) + up);
            fb.setVelocity(dir);

            boolean keep = rnd.nextDouble() < placeChance;
            if (!keep) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (fb.isValid()) fb.remove(); }, 20L * (6 + rnd.nextInt(4)));
            }
        }
    }

    /** 분화구: 원형 반경과 깊이를 power 기반으로 계산해 그릇 형태로 파낸다 */
    private void carveCrater(World w, Location center, int power) {
        double rBase = plugin.getConfig().getDouble("meteor.crater.radius-base", 6.0);
        double rPerP = plugin.getConfig().getDouble("meteor.crater.radius-per-power", 1.7);
        double depthScale = plugin.getConfig().getDouble("meteor.crater.depth-scale", 0.58);
        double rimNoise = Math.max(0, Math.min(0.5, plugin.getConfig().getDouble("meteor.crater.rim-random", 0.22)));
        boolean smooth = plugin.getConfig().getBoolean("meteor.crater.smooth", true);

        double radius = rBase + rPerP * Math.max(0, power);
        int maxDepth = Math.max(2, (int)Math.round(radius * depthScale)); // 최대 깊이

        int cx = center.getBlockX();
        int cz = center.getBlockZ();

        for (int dx = (int)-Math.ceil(radius); dx <= Math.ceil(radius); dx++) {
            for (int dz = (int)-Math.ceil(radius); dz <= Math.ceil(radius); dz++) {
                double dist = Math.sqrt(dx*dx + dz*dz);
                if (dist > radius + rnd.nextDouble() * rimNoise) continue;

                int x = cx + dx, z = cz + dz;
                int topY = w.getHighestBlockYAt(x, z);
                Block top = w.getBlockAt(x, topY, z);

                // 그릇(포물면) 깊이: 가장자리 0, 중심 maxDepth
                double t = 1.0 - (dist / Math.max(0.0001, radius));
                if (smooth) t = t*t; // 부드러움
                int depthHere = Math.max(1, (int)Math.round(maxDepth * t));

                for (int dy = 0; dy < depthHere; dy++) {
                    Block b = w.getBlockAt(x, topY - dy, z);
                    if (!BlockUtils.canBreak(b.getType())) continue;
                    b.setType(Material.AIR, true); // 물리 적용: 물 흐름/상부 붕괴 유도
                }

                // 림/바닥 연출
                if (t > 0.85 && rnd.nextDouble() < plugin.getConfig().getDouble("meteor.crater.magma-rim-chance", 0.18)) {
                    if (top.getType() != Material.BEDROCK) top.setType(Material.MAGMA_BLOCK, true);
                }
                if (t > 0.95 && rnd.nextDouble() < plugin.getConfig().getDouble("meteor.crater.lava-bottom-chance", 0.38)) {
                    Block bottom = w.getBlockAt(x, topY - depthHere, z);
                    if (bottom.getType().isAir()) bottom.setType(Material.LAVA, true);
                }
            }
        }

        // 살짝 연기
        w.spawnParticle(Particle.LARGE_SMOKE, center, 80, radius*0.6, maxDepth*0.3, radius*0.6, 0.02);
        w.playSound(center, Sound.BLOCK_BASALT_BREAK, 1.0f, 0.6f + rnd.nextFloat() * 0.2f);
    }

    private void applyScorch(World w, Location center) {
        int radius = Math.max(0, plugin.getConfig().getInt("meteor.scorch.radius", 6));
        if (radius <= 0) return;

        double patchChance = plugin.getConfig().getDouble("meteor.scorch.magma-patch-chance", 0.40);
        double fireChance  = plugin.getConfig().getDouble("meteor.scorch.fire-chance", 0.45);
        boolean ash        = plugin.getConfig().getBoolean("meteor.scorch.ash-particles", true);

        int cx = center.getBlockX();
        int cz = center.getBlockZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx*dx + dz*dz > radius*radius) continue;

                int x = cx + dx, z = cz + dz;
                int y = w.getHighestBlockYAt(x, z);
                Block top = w.getBlockAt(x, y, z);
                if (!isExposedToSky(top)) continue;

                if (rnd.nextDouble() < patchChance) {
                    if (top.getType() != Material.BEDROCK) {
                        top.setType(Material.MAGMA_BLOCK, true);
                    }
                }
                if (rnd.nextDouble() < fireChance) {
                    Block up = top.getRelative(0, 1, 0);
                    if (up.getType().isAir()) up.setType(Material.FIRE, true);
                }
                if (ash) {
                    w.spawnParticle(Particle.ASH, top.getLocation().add(0.5, 1.0, 0.5), 3, 0.3, 0.2, 0.3, 0.0);
                }
            }
        }
    }

    // ----- 유틸 -----
    private List<Vector> fibonacciSphere(double radius, int n) {
        List<Vector> v = new ArrayList<>(n);
        double ga = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < n; i++) {
            double t = (double) i / Math.max(1, n - 1);
            double y = 1.0 - 2.0 * t;
            double r = Math.sqrt(1 - y * y);
            double phi = i * ga;
            double x = Math.cos(phi) * r;
            double z = Math.sin(phi) * r;
            v.add(new Vector(x * radius, y * radius, z * radius));
        }
        return v;
    }

    private Vector randomUnitHemisphere() {
        double u = rnd.nextDouble();
        double v = rnd.nextDouble();
        double theta = 2 * Math.PI * u;
        double z = v; // 0..1
        double r = Math.sqrt(Math.max(0, 1 - z * z));
        return new Vector(r * Math.cos(theta), z, r * Math.sin(theta)).normalize();
    }

    private Vector coneJitter(Vector base, double maxAngleRad) {
        Vector n = base.clone().normalize();
        Vector u = n.getX() != 0 || n.getZ() != 0 ? new Vector(-n.getZ(), 0, n.getX()).normalize() : new Vector(1,0,0);
        Vector v = n.clone().getCrossProduct(u).normalize();

        double a = rnd.nextDouble() * maxAngleRad;
        double t = rnd.nextDouble() * 2 * Math.PI;
        Vector dir = n.clone().multiply(Math.cos(a))
                .add(u.multiply(Math.sin(a) * Math.cos(t)))
                .add(v.multiply(Math.sin(a) * Math.sin(t)));
        return dir.normalize().multiply(base.length());
    }

    private Location averageLocationOfMeteor(UUID meteorId, World fallbackWorld, Location fallbackLoc) {
        Set<UUID> parts = meteorParts.get(meteorId);
        if (parts == null || parts.isEmpty()) return fallbackLoc;

        double x=0,y=0,z=0; int n=0; World w = null;
        for (UUID pid : parts) {
            Entity e = Bukkit.getEntity(pid);
            if (e != null && e.isValid()) {
                Location l = e.getLocation();
                x+=l.getX(); y+=l.getY(); z+=l.getZ(); n++;
                if (w == null) w = l.getWorld();
            }
        }
        if (n==0) return fallbackLoc;
        return new Location(w==null?fallbackWorld:w, x/n, y/n, z/n);
    }

    private Material materialOr(String key, Material fallback) {
        String name = plugin.getConfig().getString(key, fallback.name());
        Material m = Material.matchMaterial(name);
        return m == null ? fallback : m;
    }

    private boolean isExposedToSky(Block b) {
        World w = b.getWorld();
        int highest = w.getHighestBlockYAt(b.getX(), b.getZ());
        return b.getY() >= highest;
    }
}
