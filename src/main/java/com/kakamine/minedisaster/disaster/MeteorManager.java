package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.*;
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
    private final Set<BukkitTask> tasks = new HashSet<>();
    private final Random rnd = new Random();

    // 추락 중인 유성 추적용
    private final Set<UUID> tracking = Collections.synchronizedSet(new HashSet<>());

    public MeteorManager(MineDisaster plugin) { this.plugin = plugin; }

    public void cancelAll() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        tracking.clear();
    }

    public void spawnMeteorShower(World world, Location center, int count, int explosionPower) {
        final boolean ignite = plugin.getConfig().getBoolean("meteor.ignite-fire", true);
        final int addY = plugin.getConfig().getInt("meteor.spawn-height", 60);
        final Material mat = Material.matchMaterial(plugin.getConfig().getString("meteor.material","MAGMA_BLOCK"));
        final Particle fireP = Particle.valueOf(plugin.getConfig().getString("meteor.fireball-particle","FLAME"));
        final Particle smokeP = Particle.valueOf(plugin.getConfig().getString("meteor.smoke-particle","LARGE_SMOKE"));
        final Sound s = Sound.valueOf(plugin.getConfig().getString("meteor.sound","ENTITY_BLAZE_SHOOT"));

        for (int i = 0; i < count; i++) {
            double r = 6 + rnd.nextDouble() * 10;
            double ang = rnd.nextDouble() * Math.PI * 2;
            Location spawn = center.clone().add(Math.cos(ang)*r, addY + rnd.nextInt(15), Math.sin(ang)*r);
            Location target = center.clone().add(rnd.nextGaussian()*2, 0, rnd.nextGaussian()*2);

            FallingBlock fb = world.spawnFallingBlock(spawn, (mat==null?Material.MAGMA_BLOCK:mat).createBlockData());
            fb.setDropItem(false);
            fb.setHurtEntities(true);
            fb.setVelocity(target.toVector().subtract(spawn.toVector()).normalize().multiply(1.2).add(new Vector(0,-0.3,0)));

            tracking.add(fb.getUniqueId());
            world.playSound(spawn, s, 1.0f, 0.6f + rnd.nextFloat()*0.4f);

            // 꼬리 파티클
            BukkitTask tail = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (!fb.isValid()) return;
                world.spawnParticle(Particle.LAVA, fb.getLocation(), 2, 0,0,0,0);
                world.spawnParticle(fireP, fb.getLocation(), 1, 0,0,0,0);
            }, 1L, 1L);
            tasks.add(tail);

            // 안전 타임아웃
            BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (fb.isValid()) {
                    Location impact = fb.getLocation();
                    fb.remove();
                    world.createExplosion(impact, explosionPower, ignite, true);
                    world.spawnParticle(smokeP, impact, 40, 1.5,1.0,1.5, 0.02);
                }
                tracking.remove(fb.getUniqueId());
            }, 20L * 20); // 20초
            tasks.add(timeout);
        }
    }

    // 낙하 블록이 땅이 되려고 할 때(착지) 폭발로 전환
    @EventHandler
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof FallingBlock fb)) return;
        if (!tracking.contains(fb.getUniqueId())) return;

        e.setCancelled(true); // 실제 블록으로 변하지 않게
        Location impact = e.getBlock().getLocation().add(0.5, 0.0, 0.5);

        final boolean ignite = plugin.getConfig().getBoolean("meteor.ignite-fire", true);
        final int explosionPower = Math.max(1, Math.min(plugin.getConfig().getInt("meteor.explosion-power", 4), 10));
        final Particle smokeP = Particle.valueOf(plugin.getConfig().getString("meteor.smoke-particle","LARGE_SMOKE"));

        fb.remove();
        impact.getWorld().createExplosion(impact, explosionPower, ignite, true);
        impact.getWorld().spawnParticle(smokeP, impact, 40, 1.5,1.0,1.5, 0.02);

        // 주변 플레이어 넉백
        for (Player pl : impact.getWorld().getPlayers()) {
            if (pl.getLocation().distanceSquared(impact) < 40*40) {
                Vector push = pl.getLocation().toVector().subtract(impact.toVector()).normalize().multiply(0.3);
                push.setY(0.2);
                pl.setVelocity(pl.getVelocity().add(push));
            }
        }

        tracking.remove(fb.getUniqueId());
    }
}
