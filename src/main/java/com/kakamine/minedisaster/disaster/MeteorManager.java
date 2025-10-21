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

public class MeteorManager implements Listener {
    private final MineDisaster plugin;
    private final Random rnd = new Random();

    // 메테오 파트 추적
    private final Map<UUID, UUID> partToMeteor = new HashMap<>();
    private final Map<UUID, Set<UUID>> meteorParts = new HashMap<>();
    private final Map<UUID, List<BukkitTask>> meteorTasks = new HashMap<>();

    // 파편 추적
    private final Set<UUID> debrisAll = new HashSet<>();

    // ▶ 성능 상한 / 파티클 빈도
    private final int MAX_PARTS_PER_METEOR = 220;
    private final int TRAIL_TICK_PERIOD = 2;

    public MeteorManager(MineDisaster plugin) { this.plugin = plugin; }

    public void cancelAll() {
        meteorTasks.values().forEach(list -> list.forEach(BukkitTask::cancel));
        meteorTasks.clear();
        for (UUID id : new ArrayList<>(partToMeteor.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        partToMeteor.clear();
        for (UUID id : new ArrayList<>(debrisAll)) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        debrisAll.clear();
        meteorParts.clear();
    }

    public void spawnMeteorShower(World world, Location center, int count, int requestedPower) {
        final boolean ignite = plugin.getConfig().getBoolean("meteor.ignite-fire", true);
        final int addY = plugin.getConfig().getInt("meteor.spawn-height", 80);
        final int timeoutSec = Math.max(5, plugin.getConfig().getInt("meteor.timeout-seconds", 20));
        final int randRadius = Math.max(0, plugin.getConfig().getInt("meteor.random-radius", 22));

        // 폭발력
        int maxP = Math.max(10, plugin.getConfig().getInt("meteor.explosion-max", 20));
        int cfgP = plugin.getConfig().getInt("meteor.explosion-power", 12);
        int power = Math.max(1, Math.min((requestedPower > 0 ? requestedPower : cfgP), maxP));

        // 재질
        Material surfaceMat = matchMat(plugin.getConfig().getString("meteor.surface-material", "MAGMA_BLOCK"), Material.MAGMA_BLOCK);
        Material coreMat    = matchMat(plugin.getConfig().getString("meteor.core-material",    "DEEPSLATE"),   Material.DEEPSLATE);
        Material fillMat    = matchMat(plugin.getConfig().getString("meteor.fill-material",    "NETHERRACK"),  Material.NETHERRACK);

        // 반경 스케일
        double baseSurfaceR = Math.max(1.6, plugin.getConfig().getDouble("meteor.base-surface-radius", 3.2));
        double baseCoreR    = Math.max(0.9, plugin.getConfig().getDouble("meteor.base-core-radius",    2.0));
        double scalePerPow  = Math.max(0.0, plugin.getConfig().getDouble("meteor.radius-scale-per-power", 0.18));
        double scale = 1.0 + (power * scalePerPow);
        double surfaceR = baseSurfaceR * scale;
        double coreR    = baseCoreR * scale;

        // 밀도
        int surfaceN = Math.max(32, Math.min(plugin.getConfig().getInt("meteor.surface-pieces", 80), 320));
        int coreN    = Math.max(12, Math.min(plugin.getConfig().getInt("meteor.core-pieces",    30), 160));
        double fillDensity = Math.max(0.0, plugin.getConfig().getDouble("meteor.fill-density", 1.2));
        int fillN = (int)Math.round(surfaceN * fillDensity * Math.max(1.0, surfaceR / 3.0));

        // 총 파트 상한(동적 다운스케일)
        int totalParts = surfaceN + coreN + fillN;
        if (totalParts > MAX_PARTS_PER_METEOR) {
            double s = MAX_PARTS_PER_METEOR / (double) totalParts;
            surfaceN = Math.max(32, (int)Math.round(surfaceN * s));
            coreN    = Math.max(12, (int)Math.round(coreN * s));
            fillN    = Math.max(16, (int)Math.round(fillN * s));
        }

        // 이펙트/속도
        Particle trailP  = Particle.valueOf(plugin.getConfig().getString("meteor.trail-particle", "FLAME"));
        Particle smokeP  = Particle.valueOf(plugin.getConfig().getString("meteor.smoke-particle", "LARGE_SMOKE"));
        int trailDensity = Math.max(1, Math.min(plugin.getConfig().getInt("meteor.trail-density", 1), 6));
        double speed     = plugin.getConfig().getDouble("meteor.fall-speed", 1.35);
        double gravBias  = plugin.getConfig().getDouble("meteor.gravity-bias", -0.30);
        double spreadDeg = Math.max(0, Math.min(25, plugin.getConfig().getDouble("meteor.spread-angle-deg", 12)));

        for (int i = 0; i < count; i++) {
            // 랜덤 타겟/스폰
            Location tgt = center.clone().add(
                    (rnd.nextDouble() * 2 - 1) * randRadius,
                    0,
                    (rnd.nextDouble() * 2 - 1) * randRadius
            );
            Location spawn = tgt.clone().add(rnd.nextGaussian() * 2, addY + rnd.nextInt(20), rnd.nextGaussian() * 2);

            Vector vel = tgt.toVector().subtract(spawn.toVector()).normalize()
                    .multiply(speed).add(new Vector(0, gravBias, 0));
            if (spreadDeg > 0) vel = coneJitter(vel, Math.toRadians(spreadDeg));

            UUID meteorId = UUID.randomUUID();
            meteorParts.put(meteorId, new HashSet<>());
            meteorTasks.put(meteorId, new ArrayList<>());

            // 표면/코어/내부 채움(솔리드)
            for (Vector off : fibonacciSphere(surfaceR, surfaceN)) spawnMeteorPiece(world, spawn.clone().add(off), surfaceMat, vel, meteorId, trailP, trailDensity);
            for (Vector off : fibonacciSphere(coreR, coreN))       spawnMeteorPiece(world, spawn.clone().add(off), coreMat,    vel, meteorId, trailP, trailDensity);
            for (int k = 0; k < fillN; k++) {
                Vector off = randomPointInSolidSphere(coreR * 0.9 + rnd.nextDouble() * (surfaceR - coreR * 0.9));
                spawnMeteorPiece(world, spawn.clone().add(off), fillMat, vel, meteorId, trailP, trailDensity);
            }

            world.playSound(spawn, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.6f + rnd.nextFloat() * 0.4f);

            // 타임아웃 강제 폭발
            BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!meteorParts.containsKey(meteorId)) return;
                Location avg = averageLocationOfMeteor(meteorId, world, spawn);
                triggerExplosionAndAftereffects(meteorId, avg, power, ignite, smokeP);
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
            if (rnd.nextBoolean()) w.spawnParticle(Particle.SMALL_FLAME, l, 1, 0, 0, 0, 0);
        }, 1L, TRAIL_TICK_PERIOD);
        meteorTasks.get(meteorId).add(tail);
    }

    @EventHandler
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof FallingBlock fb)) return;

        UUID pid = fb.getUniqueId();
        UUID meteorId = partToMeteor.get(pid);

        // 파편은 기본 동작 허용 후 착지 점화 처리
        if (meteorId == null && debrisAll.contains(pid)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Block placed = e.getBlock();
                if (!placed.getType().isAir()) {
                    double igniteChance = plugin.getConfig().getDouble("meteor.debris.ignite-on-land-chance", 0.55);
                    double toNeth = plugin.getConfig().getDouble("meteor.debris.turn-to-netherrack-chance", 0.25);
                    if (rnd.nextDouble() < toNeth && placed.getType() != Material.BEDROCK) placed.setType(Material.NETHERRACK, true);
                    if (rnd.nextDouble() < igniteChance) {
                        Block up = placed.getRelative(0,1,0);
                        if (up.getType().isAir()) up.setType(Material.FIRE, true);
                    }
                }
                debrisAll.remove(pid);
            });
            return;
        }

        // 메테오 본체: 블록으로 변환 취소 + 즉시 폭발
        if (meteorId != null) {
            e.setCancelled(true);
            Location impact = e.getBlock().getLocation().add(0.5, 0.0, 0.5);

            boolean ignite = plugin.getConfig().getBoolean("meteor.ignite-fire", true);
            int cfgP = plugin.getConfig().getInt("meteor.explosion-power", 12);
            int maxP = Math.max(10, plugin.getConfig().getInt("meteor.explosion-max", 20));
            int power = Math.max(1, Math.min(cfgP, maxP));
            Particle smokeP = Particle.valueOf(plugin.getConfig().getString("meteor.smoke-particle", "LARGE_SMOKE"));

            triggerExplosionAndAftereffects(meteorId, impact, power, ignite, smokeP);
        }
    }

    private void triggerExplosionAndAftereffects(UUID meteorId, Location loc, int power, boolean ignite, Particle smokeP) {
        World w = loc.getWorld();
        if (w != null) {
            w.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1);
            w.createExplosion(loc, power, ignite, true);
            w.spawnParticle(smokeP, loc, 140, 3.0, 2.0, 3.0, 0.045);

            // 플레이어 충격파
            for (Player pl : w.getPlayers()) {
                double d2 = pl.getLocation().distanceSquared(loc);
                if (d2 <= 80 * 80) {
                    Vector push = pl.getLocation().toVector().subtract(loc.toVector()).normalize().multiply(0.70);
                    push.setY(0.42);
                    pl.setVelocity(pl.getVelocity().add(push));
                    w.playSound(pl, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.75f);
                }
            }

            // 스코치
            applyScorch(w, loc);

            // 분화구
            double craterR = 0;
            if (plugin.getConfig().getBoolean("meteor.crater.enabled", true)) craterR = carveCrater(w, loc, power);

            // 파편
            spawnDebris(w, loc, power);

            // 화염 폭풍(주변 대량 점화)
            if (plugin.getConfig().getBoolean("meteor.postfire.enabled", true)) firestorm(w, loc, power, craterR);
        }

        // 정리
        Set<UUID> parts = meteorParts.remove(meteorId);
        if (parts != null) for (UUID pid : parts) { Entity e = Bukkit.getEntity(pid); if (e != null) e.remove(); partToMeteor.remove(pid); }
        List<BukkitTask> tasks = meteorTasks.remove(meteorId);
        if (tasks != null) tasks.forEach(BukkitTask::cancel);
    }

    private void spawnDebris(World w, Location origin, int power) {
        int base = Math.max(0, plugin.getConfig().getInt("meteor.debris.base-count", 45));
        int perP = Math.max(0, plugin.getConfig().getInt("meteor.debris.per-power", 2));
        int count = base + perP * Math.max(0, power);
        if (count == 0) return;

        Material m1 = matchMat(plugin.getConfig().getString("meteor.debris.material-1","DEEPSLATE"), Material.DEEPSLATE);
        Material m2 = matchMat(plugin.getConfig().getString("meteor.debris.material-2","MAGMA_BLOCK"), Material.MAGMA_BLOCK);
        Material m3 = matchMat(plugin.getConfig().getString("meteor.debris.material-3","COBBLESTONE"), Material.COBBLESTONE);

        double vMin = plugin.getConfig().getDouble("meteor.debris.speed-min", 0.6);
        double vMax = plugin.getConfig().getDouble("meteor.debris.speed-max", 1.9);
        double up   = plugin.getConfig().getDouble("meteor.debris.up-boost", 0.40);

        for (int i = 0; i < count; i++) {
            Material mat = switch (rnd.nextInt(3)) { case 0 -> m1; case 1 -> m2; default -> m3; };
            FallingBlock fb = w.spawnFallingBlock(origin, mat.createBlockData());
            fb.setDropItem(false);
            fb.setHurtEntities(true);

            Vector dir = randomUnitHemisphere().multiply(vMin + rnd.nextDouble() * (vMax - vMin));
            dir.setY(Math.abs(dir.getY()) + up);
            fb.setVelocity(dir);

            debrisAll.add(fb.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (fb.isValid()) fb.remove(); debrisAll.remove(fb.getUniqueId()); }, 20L * (6 + rnd.nextInt(4)));
        }
    }

    private void firestorm(World w, Location center, int power, double craterRadius) {
        double mult = plugin.getConfig().getDouble("meteor.postfire.radius-multiplier", 1.3);
        int attempts = Math.max(0, plugin.getConfig().getInt("meteor.postfire.attempts", 260));
        boolean skyOnly = plugin.getConfig().getBoolean("meteor.postfire.sky-only", true);
        double nethChance = plugin.getConfig().getDouble("meteor.postfire.netherrack-chance", 0.12);

        int baseR = plugin.getConfig().getInt("meteor.scorch.radius", 6);
        double R = (craterRadius > 0 ? craterRadius : baseR) * Math.max(0.5, mult);

        int cx = center.getBlockX(), cz = center.getBlockZ();
        for (int i = 0; i < attempts; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double r = rnd.nextDouble() * R;
            int x = cx + (int)Math.round(Math.cos(ang) * r);
            int z = cz + (int)Math.round(Math.sin(ang) * r);
            int y = w.getHighestBlockYAt(x, z);
            Block top = w.getBlockAt(x, y, z);
            if (skyOnly && !isExposedToSky(top)) continue;

            Block up = top.getRelative(0,1,0);
            if (up.getType().isAir()) {
                if (rnd.nextDouble() < nethChance && top.getType() != Material.BEDROCK) top.setType(Material.NETHERRACK, true);
                up.setType(Material.FIRE, true);
            }
            w.spawnParticle(Particle.ASH, up.getLocation().add(0.5,0.2,0.5), 2, 0.1,0.1,0.1, 0);
        }
    }

    private double carveCrater(World w, Location center, int power) {
        double rBase = plugin.getConfig().getDouble("meteor.crater.radius-base", 6.0);
        double rPerP = plugin.getConfig().getDouble("meteor.crater.radius-per-power", 1.7);
        double depthScale = plugin.getConfig().getDouble("meteor.crater.depth-scale", 0.58);
        double rimNoise = Math.max(0, Math.min(0.5, plugin.getConfig().getDouble("meteor.crater.rim-random", 0.22)));
        boolean smooth = plugin.getConfig().getBoolean("meteor.crater.smooth", true);

        double radius = rBase + rPerP * Math.max(0, power);
        int maxDepth = Math.max(2, (int)Math.round(radius * depthScale));

        int cx = center.getBlockX();
        int cz = center.getBlockZ();

        for (int dx = (int)-Math.ceil(radius); dx <= Math.ceil(radius); dx++) {
            for (int dz = (int)-Math.ceil(radius); dz <= Math.ceil(radius); dz++) {
                double dist = Math.sqrt(dx*dx + dz*dz);
                if (dist > radius + rnd.nextDouble() * rimNoise) continue;

                int x = cx + dx, z = cz + dz;
                int topY = w.getHighestBlockYAt(x, z);
                Block top = w.getBlockAt(x, topY, z);

                double t = 1.0 - (dist / Math.max(0.0001, radius));
                if (smooth) t = t*t;
                int depthHere = Math.max(1, (int)Math.round(maxDepth * t));

                for (int dy = 0; dy < depthHere; dy++) {
                    Block b = w.getBlockAt(x, topY - dy, z);
                    if (b.getType() == Material.BEDROCK) continue;
                    b.setType(Material.AIR, true);
                }

                if (t > 0.85 && rnd.nextDouble() < plugin.getConfig().getDouble("meteor.crater.magma-rim-chance", 0.18)) {
                    if (top.getType() != Material.BEDROCK) top.setType(Material.MAGMA_BLOCK, true);
                }
                if (t > 0.95 && rnd.nextDouble() < plugin.getConfig().getDouble("meteor.crater.lava-bottom-chance", 0.38)) {
                    Block bottom = w.getBlockAt(x, topY - depthHere, z);
                    if (bottom.getType().isAir()) bottom.setType(Material.LAVA, true);
                }
            }
        }

        w.spawnParticle(Particle.LARGE_SMOKE, center, 90, radius*0.6, maxDepth*0.3, radius*0.6, 0.02);
        w.playSound(center, Sound.BLOCK_BASALT_BREAK, 1.0f, 0.6f + rnd.nextFloat() * 0.2f);
        return radius;
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

                if (rnd.nextDouble() < patchChance && top.getType() != Material.BEDROCK) top.setType(Material.MAGMA_BLOCK, true);
                if (rnd.nextDouble() < fireChance) {
                    Block up = top.getRelative(0, 1, 0);
                    if (up.getType().isAir()) up.setType(Material.FIRE, true);
                }
                if (ash) w.spawnParticle(Particle.ASH, top.getLocation().add(0.5, 1.0, 0.5), 3, 0.3, 0.2, 0.3, 0.0);
            }
        }
    }

    // ---- 유틸 ----
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

    private Vector randomPointInSolidSphere(double maxR) {
        double u = rnd.nextDouble(), v = rnd.nextDouble(), w = rnd.nextDouble();
        double theta = 2 * Math.PI * u;
        double phi = Math.acos(2 * v - 1);
        double r = Math.cbrt(w) * maxR;
        double sx = r * Math.sin(phi) * Math.cos(theta);
        double sy = r * Math.cos(phi);
        double sz = r * Math.sin(phi) * Math.sin(theta);
        return new Vector(sx, sy, sz);
    }

    private Vector randomUnitHemisphere() {
        double u = rnd.nextDouble(), v = rnd.nextDouble();
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

    private Material matchMat(String name, Material fallback) {
        Material m = Material.matchMaterial(name);
        return m == null ? fallback : m;
    }
    private boolean isExposedToSky(Block b) {
        World w = b.getWorld();
        int highest = w.getHighestBlockYAt(b.getX(), b.getZ());
        return b.getY() >= highest;
    }
}
