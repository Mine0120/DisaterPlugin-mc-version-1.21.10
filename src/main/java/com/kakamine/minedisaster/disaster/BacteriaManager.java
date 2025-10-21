package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import com.kakamine.minedisaster.util.BlockUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * 박테리아(무제한 확산 옵션 지원 + 중지 시 즉시 정리):
 * - 이웃 6방향 확산.
 * - 감염된 블록은 infection-block으로 유지되다가 lifetime 이후 replace-after로 사라짐.
 * - cancelAll() 호출 시, 남아있는 감염 블록을 즉시 replace-after로 치환(물리 적용 true).
 */
public class BacteriaManager {
    private final MineDisaster plugin;
    private BukkitTask task;

    private final Random rnd = new Random();

    private Set<Material> targetBlocks;
    private Material infectionBlock;
    private Material replaceAfter;
    private int lifetimeTicks;
    private int interval;
    private int perTick;
    private double spreadChance;
    private int maxActive;
    private boolean unlimitedActive;

    private int tickCounter = 0;

    private static final class Infected {
        final World world; final int x,y,z;
        int expireAt; // tickCounter 기준
        Infected(Block b, int expireAt) {
            this.world = b.getWorld(); this.x=b.getX(); this.y=b.getY(); this.z=b.getZ();
            this.expireAt = expireAt;
        }
        Block toBlock() { return world.getBlockAt(x,y,z); }
        @Override public int hashCode() { return Objects.hash(world.getUID(),x,y,z); }
        @Override public boolean equals(Object o){ if(!(o instanceof Infected i))return false;
            return i.world.getUID().equals(world.getUID()) && i.x==x && i.y==y && i.z==z; }
    }

    private final Set<Infected> active = new HashSet<>();
    private final Queue<Infected> frontier = new ArrayDeque<>();
    private final Set<String> visited = new HashSet<>();

    public BacteriaManager(MineDisaster plugin) {
        this.plugin = plugin;
        reloadTypes();
    }

    private String key(Block b) { return b.getWorld().getUID()+":"+b.getX()+":"+b.getY()+":"+b.getZ(); }

    public void reloadTypes() {
        targetBlocks = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("bacteria.target-blocks")) {
            Material m = Material.matchMaterial(s);
            if (m != null) targetBlocks.add(m);
        }
        infectionBlock = Material.matchMaterial(plugin.getConfig().getString("bacteria.infection-block","SCULK"));
        if (infectionBlock == null) infectionBlock = Material.SCULK;

        replaceAfter = Material.matchMaterial(plugin.getConfig().getString("bacteria.replace-after","AIR"));
        if (replaceAfter == null) replaceAfter = Material.AIR;

        lifetimeTicks = Math.max(40, plugin.getConfig().getInt("bacteria.lifetime-ticks", 200));
        interval = Math.max(1, plugin.getConfig().getInt("bacteria.tick-interval", 5));
        perTick = Math.max(1, plugin.getConfig().getInt("bacteria.per-tick", 120));
        spreadChance = Math.max(0.0, Math.min(1.0, plugin.getConfig().getDouble("bacteria.spread-chance", 0.45)));
        maxActive = plugin.getConfig().getInt("bacteria.max-active", 6000);
        unlimitedActive = (maxActive <= 0);
        if (!unlimitedActive) maxActive = Math.max(100, maxActive);
    }

    /** 남은 감염을 즉시 정리(물리 적용 true) */
    private void forceClearInfected() {
        for (Infected inf : active) {
            Block b = inf.toBlock();
            if (b.getType() == infectionBlock) {
                b.setType(replaceAfter, true); // 물리 적용: 물 흐름/위 잔디 붕괴 등
                b.getWorld().spawnParticle(Particle.ASH, b.getLocation().add(0.5,0.6,0.5), 4, 0.2,0.2,0.2, 0);
            }
        }
    }

    public void cancelAll() {
        if (task != null) { task.cancel(); task = null; }
        // ★ 중지 시 잔여 감염 즉시 정리
        forceClearInfected();
        active.clear();
        frontier.clear();
        visited.clear();
        tickCounter = 0;
    }

    public void startInfection(Location origin, int startRadius, int perTickArg, int ignored) {
        reloadTypes();
        if (perTickArg > 0) this.perTick = Math.min(perTickArg, 4000);

        World w = origin.getWorld();
        if (w == null) return;

        cancelAll();          // 시작 전에 기존 런을 정리
        tickCounter = 0;

        int seeds = 0;
        for (Block b : BlockUtils.blocksInSphere(w, origin, startRadius)) {
            if (isTarget(b) && (unlimitedActive || active.size() < maxActive)) {
                infect(b);
                seeds++;
            }
        }
        if (seeds == 0) {
            Block base = w.getBlockAt(origin);
            if (isTarget(base) && (unlimitedActive || active.size() < maxActive)) infect(base);
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, interval);
    }

    private boolean isTarget(Block b) {
        if (b.getType() == infectionBlock) return false;
        return targetBlocks.contains(b.getType());
    }

    private void infect(Block b) {
        String k = key(b);
        if (visited.contains(k)) return;

        b.setType(infectionBlock, false); // 감염 연출은 물리 비적용(형상 유지)
        b.getWorld().spawnParticle(Particle.SCULK_CHARGE_POP, b.getLocation().add(0.5,0.5,0.5), 1, 0,0,0, 0);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_SCULK_SPREAD, 0.3f, 1.2f);

        int expire = tickCounter + lifetimeTicks;
        Infected node = new Infected(b, expire);
        active.add(node);
        frontier.add(node);
        visited.add(k);
    }

    private void tick() {
        tickCounter += interval;
        final int now = tickCounter;

        if (active.isEmpty() && frontier.isEmpty()) {
            cancelAll();
            return;
        }

        // 수명 만료 → 제거 (물리 적용 true)
        for (Iterator<Infected> it = active.iterator(); it.hasNext();) {
            Infected inf = it.next();
            if (now >= inf.expireAt) {
                Block b = inf.toBlock();
                if (b.getType() == infectionBlock) {
                    b.setType(replaceAfter, true); // ← 물/잔디 등 상호작용 발생
                    b.getWorld().spawnParticle(Particle.ASH, b.getLocation().add(0.5,0.6,0.5), 4, 0.2,0.2,0.2, 0);
                    b.getWorld().playSound(b.getLocation(), Sound.BLOCK_HONEY_BLOCK_BREAK, 0.15f, 0.8f);
                }
                it.remove();
            }
        }

        // 확산
        int spawned = 0;
        int guard = perTick * 4;
        while (!frontier.isEmpty() && spawned < perTick && guard-- > 0) {
            Infected src = frontier.poll();
            if (src == null) break;

            Block s = src.toBlock();
            if (s.getType() != infectionBlock) continue;

            for (Block nb : BlockUtils.neighbors6(s)) {
                if (!unlimitedActive && active.size() >= maxActive) break;
                if (rnd.nextDouble() > spreadChance) continue;
                if (!nb.getChunk().isLoaded()) continue;
                if (!isTarget(nb)) continue;

                infect(nb);
                spawned++;
                if (spawned >= perTick) break;
            }

            if (src.expireAt > now && s.getType() == infectionBlock) {
                frontier.add(src);
            }
        }
    }
}
