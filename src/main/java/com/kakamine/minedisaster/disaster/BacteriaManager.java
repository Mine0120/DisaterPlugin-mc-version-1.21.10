package com.kakamine.minedisaster.disaster;

import com.kakamine.minedisaster.MineDisaster;
import com.kakamine.minedisaster.util.BlockUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * 박테리아(무제한 확산 옵션 지원):
 * - 이웃 6방향으로 퍼진다(확률적).
 * - 감염된 블록은 infection-block으로 바뀌고, lifetime 이후 replace-after(보통 AIR)로 사라진다.
 * - Bukkit.getCurrentTick() 없이 내부 tickCounter로 수명 관리.
 * - config의 bacteria.max-active <= 0 이면 "동시 활성 무제한"으로 끝없이 퍼짐.
 */
public class BacteriaManager {
    private final MineDisaster plugin;
    private BukkitTask task;

    private final Random rnd = new Random();

    private Set<Material> targetBlocks;   // 감염 대상 지형
    private Material infectionBlock;      // 감염 시 겉모습
    private Material replaceAfter;        // 수명 종료 후 치환(보통 AIR)
    private int lifetimeTicks;            // 감염 수명
    private int interval;                 // 스케줄 간격(틱)
    private int perTick;                  // 틱당 신규 감염 최대 수
    private double spreadChance;          // 이웃 감염 확률(0~1)
    private int maxActive;                // 동시 활성 상한(<=0 이면 무제한)
    private boolean unlimitedActive;      // 상한 해제 플래그

    // 내부 틱 카운터
    private int tickCounter = 0;

    private static final class Infected {
        final World world;
        final int x, y, z;
        int expireAt; // tickCounter 기준 만료틱

        Infected(Block b, int expireAt) {
            this.world = b.getWorld();
            this.x = b.getX();
            this.y = b.getY();
            this.z = b.getZ();
            this.expireAt = expireAt;
        }

        Block toBlock() { return world.getBlockAt(x, y, z); }

        @Override public int hashCode() { return Objects.hash(world.getUID(), x, y, z); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Infected i)) return false;
            return i.world.getUID().equals(world.getUID()) && i.x==x && i.y==y && i.z==z;
        }
    }

    private final Set<Infected> active = new HashSet<>();        // 살아있는 감염
    private final Queue<Infected> frontier = new ArrayDeque<>(); // 퍼짐 전선
    private final Set<String> visited = new HashSet<>();         // 좌표 방문 캐시

    public BacteriaManager(MineDisaster plugin) {
        this.plugin = plugin;
        reloadTypes();
    }

    private String key(Block b) {
        return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

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

    public void cancelAll() {
        if (task != null) { task.cancel(); task = null; }
        active.clear();
        frontier.clear();
        visited.clear();
        tickCounter = 0;
    }

    /**
     * origin에서 시작. startRadius 내 대상 블록들을 씨앗으로 심고 퍼뜨림 시작.
     *
     * @param origin      시작 지점
     * @param startRadius 씨앗 반경
     * @param perTickArg  틱당 신규 감염 override (0 이하면 config 값 사용)
     * @param ignored     호환용 자리(미사용)
     */
    public void startInfection(Location origin, int startRadius, int perTickArg, int ignored) {
        reloadTypes();
        if (perTickArg > 0) this.perTick = Math.min(perTickArg, 4000);

        World w = origin.getWorld();
        if (w == null) return;

        // 초기화
        cancelAll();
        tickCounter = 0;

        // 씨앗 심기
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
        if (b.getType() == infectionBlock) return false; // 이미 감염된 곳 제외
        return targetBlocks.contains(b.getType());
    }

    private void infect(Block b) {
        String k = key(b);
        if (visited.contains(k)) return;

        b.setType(infectionBlock, false);
        b.getWorld().spawnParticle(Particle.SCULK_CHARGE_POP, b.getLocation().add(0.5,0.5,0.5), 1, 0,0,0, 0);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_SCULK_SPREAD, 0.3f, 1.2f);

        int expire = tickCounter + lifetimeTicks;
        Infected node = new Infected(b, expire);
        active.add(node);
        frontier.add(node);
        visited.add(k);
    }

    private void tick() {
        tickCounter += interval;         // 내부 '현재 틱' 업데이트
        final int now = tickCounter;

        if (active.isEmpty() && frontier.isEmpty()) {
            cancelAll();
            return;
        }

        // 1) 수명 끝난 감염 제거
        for (Iterator<Infected> it = active.iterator(); it.hasNext(); ) {
            Infected inf = it.next();
            if (now >= inf.expireAt) {
                Block b = inf.toBlock();
                if (b.getType() == infectionBlock) {
                    b.setType(replaceAfter, false);
                    b.getWorld().spawnParticle(Particle.ASH, b.getLocation().add(0.5,0.6,0.5), 4, 0.2,0.2,0.2, 0);
                    b.getWorld().playSound(b.getLocation(), Sound.BLOCK_HONEY_BLOCK_BREAK, 0.15f, 0.8f);
                }
                it.remove();
            }
        }

        // 2) 전선에서 전파
        int spawned = 0;
        int guard = perTick * 4; // 안전 가드
        while (!frontier.isEmpty() && spawned < perTick && guard-- > 0) {
            Infected src = frontier.poll();
            if (src == null) break;

            Block s = src.toBlock();
            if (s.getType() != infectionBlock) continue; // 이미 사라졌거나 변형됨

            for (Block nb : BlockUtils.neighbors6(s)) {
                if (!unlimitedActive && active.size() >= maxActive) break;
                if (rnd.nextDouble() > spreadChance) continue;
                if (!nb.getChunk().isLoaded()) continue;
                if (!isTarget(nb)) continue;

                infect(nb);
                spawned++;
                if (spawned >= perTick) break;
            }

            // 여전히 감염 상태면 전선 유지(다음 틱에도 전파 시도)
            if (src.expireAt > now && s.getType() == infectionBlock) {
                frontier.add(src);
            }
        }
    }
}
