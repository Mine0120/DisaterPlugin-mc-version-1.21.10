package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
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
 * 고퀄 메테오:
 * - 표면/코어 2겹 구(피보나치 분포)로 구성된 여러 FallingBlock을 묶어 낙하.
 * - 구성 파트 중 어느 하나라도 지면 접촉 시 강력 폭발, 파편 방출, 스코치(그을음/불) 생성.
 * - 파편은 FallingBlock으로 흩뿌려지며 일부는 실제 블록으로 남는다.
 * - 안전 타임아웃이 지나면 현재 평균 위치에서 폭발.
 */
public class MeteorManager implements Listener {
    private final MineDisaster plugin;
    private final Random rnd = new Random();

    // 파트ID -> 메테오ID
    private final Map<UUID, UUID> partToMeteor = new HashMap<>();
    // 메테오ID -> 파트ID들
    private final Map<UUID, Set<UUID>> meteorParts = new HashMap<>();
    // 메테오ID -> 태스크 리스트(꼬리, 타임아웃 등)
    private final Map<UUID, List<BukkitTask>> meteorTasks = new HashMap<>();

    public MeteorManager(MineDisaster plugin) {
        this.plugin = plugin;
    }

    public void cancelAll() {
        meteorTasks.values().forEach(list -> list.forEach(BukkitTask::cancel));
        meteorTasks.clear();

        // 모든 파트 엔티티 제거
        for (UUID partId : new ArrayList<>(partToMeteor.keySet())) {
            Entity e = Bukkit.getEntity(partId);
            if (e != null) e.remove();
        }
        partToMeteor.clear();
        meteorParts.clear();
    }

    public void spawnMeteorShower(World world, Location center, int count, int explosionPower) {
        final boolean ignite = plugin.getConfig().getBoolean("meteor.ignite-fire", true);
        final int addY = plugin.getConfig().getInt("meteor.spawn-height", 70);
        final int timeoutSec = Math.max(5, plugin.getConfig().getInt("meteor.timeout-seconds", 20));

        final Material surfaceMat = materialOr("meteor.surface-material", Material.MAGMA_BLOCK);
        final Material coreMat    = materialOr("meteor.core-material", Material.DEEPSLATE);

        final double surfaceR = Math.max(1.4, plugin.getConfig().getDouble("meteor.surface-radius", 2.7));
        final double coreR    = Math.max(0.8, plugin.getConfig().getDouble("meteor.core-radius", 1.6));
        final int surfaceN    = Math.max(16, Math.min(plugin.getConfig().getInt("meteor.surface-pieces", 72), 256));
        final int coreN       = Math.max(8,  Math.min(plugin.getConfig().getInt("meteor.core-pieces", 28), 128));

        final Particle trailP  = Particle.valueOf(plugin.getConfig().getString("meteor.trail-particle", "FLAME"));
        final Particle smokeP  = Particle.valueOf(plugin.getConfig().getString("meteor.smoke-particle", "LARGE_SMOKE"));
        final int trailDensity = Math.max(1, Math.min(plugin.getConfig().getInt("meteor.trail-density", 2), 5));
        final double speed     = plugin.getConfig().getDouble("meteor.fall-speed", 1.28);
        final double gravBias  = plugin.getConfig().getDouble("meteor.gravity-bias", -0.27);

        for (int i = 0; i < count; i++) {
            // 스폰/타겟 위치
            double r = 6 + rnd.nextDouble() * 12;
            double ang = rnd.nextDouble() * Math.PI * 2;
            Location spawn = center.clone().add(Math.cos(ang) * r, addY + rnd.nextInt(20), Math.sin(ang) * r);
            Location target = center.clone().add(rnd.nextGaussian() * 2.0, 0, rnd.nextGaussian() * 2.0);

            Vector baseVel = target.toVector().subtract(spawn.toVector()).normalize()
                    .multiply(speed).add(new Vector(0, gravBias, 0));

            // 메테오 ID 할당
            UUID meteorId = UUID.randomUUID();
            meteorParts.put(meteorId, new HashSet<>());
            meteorTasks.put(meteorId, new ArrayList<>());

            // 표면 구 생성
            for (Vector off : fibonacciSphere(surfaceR, surfaceN)) {
                spawnMeteorPiece(world, spawn.clone().add(off), surfaceMat, baseVel, meteorId, trailP, trailDensity);
            }
            // 코어 구 생성 (덜 퍼지게)
            for (Vector off : fibonacciSphere(coreR, coreN)) {
                spawnMeteorPiece(world, spawn.clone().add(off), coreMat, baseVel, meteorId, trailP, trailDensity);
            }

            // 사운드
            world.playSound(spawn, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.6f + rnd.nextFloat() * 0.4f);

            // 타임아웃
            BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!meteorParts.containsKey(meteorId)) return; // 이미 터짐
                Location avg = averageLocationOfMeteor(meteorId, world, spawn);
                triggerExplosion(meteorId, avg, explosionPower, ignite, smokeP);
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

        // 꼬리 파티클
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

        e.setCancelled(true); // 메테오 파트는 블록으로 변하지 않게(공중 폭발 연출)
        Location impact = e.getBlock().getLocation().add(0.5, 0.0, 0.5);

        final boolean ignite = plugin.getConfig().getBoolean("meteor.ignite-fire", true);
        final int explosionPower = Math.max(1, Math.min(plugin.getConfig().getInt("meteor.explosion-power", 7), 10));
        final Particle smokeP = Particle.valueOf(plugin.getConfig().getString("meteor.smoke-particle", "LARGE_SMOKE"));

        triggerExplosion(meteorId, impact, explosionPower, ignite, smokeP);
    }

    private void triggerExplosion(UUID meteorId, Location loc, int power, boolean ignite, Particle smokeP) {
        World w = loc.getWorld();
        if (w != null) {
            // 메인 폭발
            w.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1);
            w.createExplosion(loc, power, ignite, true);
            w.spawnParticle(smokeP, loc, 140, 2.2, 1.6, 2.2, 0.03);

            // 충격파 - 넉백 & 이펙트 강화
            for (Player pl : w.getPlayers()) {
                double d2 = pl.getLocation().distanceSquared(loc);
                if (d2 <= 60 * 60) {
                    Vector push = pl.getLocation().toVector().subtract(loc.toVector()).normalize()
                            .multiply(0.55);
                    push.setY(0.35);
                    pl.setVelocity(pl.getVelocity().add(push));
                    pl.spawnParticle(Particle.SONIC_BOOM, pl.getLocation(), 1);
                    w.playSound(pl, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.8f);
                }
            }

            // 스코치(지표 그을음/불/마그마 패치)
            applyScorch(w, loc);
            // 파편 방출
            spawnDebris(w, loc);
        }

        // 모든 파트 제거 + 태스크 정리
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

    private void spawnDebris(World w, Location origin) {
        int count = Math.max(0, plugin.getConfig().getInt("meteor.debris.count", 35));
        if (count == 0) return;

        Material m1 = materialOr("meteor.debris.material-1", Material.DEEPSLATE);
        Material m2 = materialOr("meteor.debris.material-2", Material.MAGMA_BLOCK);
        Material m3 = materialOr("meteor.debris.material-3", Material.COBBLESTONE);

        double vMin = plugin.getConfig().getDouble("meteor.debris.speed-min", 0.55);
        double vMax = plugin.getConfig().getDouble("meteor.debris.speed-max", 1.6);
        double up   = plugin.getConfig().getDouble("meteor.debris.up-boost", 0.35);
        double placeChance = plugin.getConfig().getDouble("meteor.debris.place-chance", 0.45);

        for (int i = 0; i < count; i++) {
            Material mat = switch (rnd.nextInt(3)) {
                case 0 -> m1; case 1 -> m2; default -> m3;
            };
            Location p = origin.clone();
            FallingBlock fb = w.spawnFallingBlock(p, mat.createBlockData());
            fb.setDropItem(false);
            fb.setHurtEntities(true);

            // 방사형 속도
            Vector dir = randomUnitHemisphere().multiply(vMin + rnd.nextDouble() * (vMax - vMin));
            dir.setY(Math.abs(dir.getY()) + up);
            fb.setVelocity(dir);

            // 착지 시 실제 블록으로 남길지 여부를 태그로 판단하기 위해 메타 없음: 확률로만 처리
            // -> FallingBlock은 기본적으로 착지 시 블록이 됨. 확률로 '없애기'만 구현.
            boolean keep = rnd.nextDouble() < placeChance;
            if (!keep) {
                // 일정 시간 후 사라지게 kill (착지 전에)
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (fb.isValid()) fb.remove();
                }, 20L * (6 + rnd.nextInt(4))); // 6~9초
            }
        }
    }

    private void applyScorch(World w, Location center) {
        int radius = Math.max(0, plugin.getConfig().getInt("meteor.scorch.radius", 4));
        if (radius <= 0) return;

        double patchChance = plugin.getConfig().getDouble("meteor.scorch.magma-patch-chance", 0.30);
        double fireChance  = plugin.getConfig().getDouble("meteor.scorch.fire-chance", 0.35);
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
                        top.setType(Material.MAGMA_BLOCK, false);
                    }
                }
                if (rnd.nextDouble() < fireChance) {
                    Block up = top.getRelative(0, 1, 0);
                    if (up.getType().isAir()) up.setType(Material.FIRE, false);
                }
                if (ash) {
                    w.spawnParticle(Particle.ASH, top.getLocation().add(0.5, 1.0, 0.5), 3, 0.3, 0.2, 0.3, 0.0);
                }
            }
        }
    }

    // 유틸: 구 표면 균등 분포
    private List<Vector> fibonacciSphere(double radius, int n) {
        List<Vector> v = new ArrayList<>(n);
        double ga = Math.PI * (3.0 - Math.sqrt(5.0)); // golden angle
        for (int i = 0; i < n; i++) {
            double t = (double) i / Math.max(1, n - 1); // 0..1
            double y = 1.0 - 2.0 * t;                    // 1..-1
            double r = Math.sqrt(1 - y * y);
            double phi = i * ga;
            double x = Math.cos(phi) * r;
            double z = Math.sin(phi) * r;
            v.add(new Vector(x * radius, y * radius, z * radius));
        }
        return v;
    }

    // 임의의 반구 방향(수평은 랜덤, 위쪽 성분 포함)
    private Vector randomUnitHemisphere() {
        double u = rnd.nextDouble();
        double v = rnd.nextDouble();
        double theta = 2 * Math.PI * u;
        double z = v; // 0..1 (위쪽 반구)
        double r = Math.sqrt(Math.max(0, 1 - z * z));
        return new Vector(r * Math.cos(theta), z, r * Math.sin(theta)).normalize();
    }

    // 파트 평균 위치
    private Location averageLocationOfMeteor(UUID meteorId, World fallbackWorld, Location fallbackLoc) {
        Set<UUID> parts = meteorParts.get(meteorId);
        if (parts == null || parts.isEmpty()) return fallbackLoc;

        double x = 0, y = 0, z = 0; int n = 0; World w = null;
        for (UUID pid : parts) {
            Entity e = Bukkit.getEntity(pid);
            if (e != null && e.isValid()) {
                Location l = e.getLocation();
                x += l.getX(); y += l.getY(); z += l.getZ(); n++;
                if (w == null) w = l.getWorld();
            }
        }
        if (n == 0) return fallbackLoc;
        return new Location(w == null ? fallbackWorld : w, x / n, y / n, z / n);
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
