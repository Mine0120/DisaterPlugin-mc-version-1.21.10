package com.kakamine.minedisaster.util;

import org.bukkit.*;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BlockUtils {

    private static final Set<Material> BREAKABLE = new HashSet<>();

    static {
        for (Material m : Material.values()) {
            if (!m.isAir() && m.isBlock()) {
                // 너무 단단한/시스템 블록 제외
                if (m.getHardness() >= 0 && m.getHardness() <= 50) {
                    BREAKABLE.add(m);
                }
            }
        }
        // 보호 대상 제거
        BREAKABLE.remove(Material.BEDROCK);
        BREAKABLE.remove(Material.END_PORTAL_FRAME);
        BREAKABLE.remove(Material.END_PORTAL);
        BREAKABLE.remove(Material.NETHER_PORTAL);
        BREAKABLE.remove(Material.COMMAND_BLOCK);
        BREAKABLE.remove(Material.STRUCTURE_BLOCK);
        BREAKABLE.remove(Material.BARRIER);
        BREAKABLE.remove(Material.REINFORCED_DEEPSLATE);
    }

    private BlockUtils() {}

    public static boolean canBreak(Material m) { return BREAKABLE.contains(m); }

    /**
     * (x,z) 칸에서 위로 훑어 가장 위의 단단/액체 블록을 찾음.
     * @param startY 탐색 시작 높이(보통 중심 Y+여유)
     */
    public static Block getTopSolidOrLiquidBlock(World w, int x, int z, double startY) {
        int y = Math.min(w.getMaxHeight()-1, (int)Math.round(startY));
        for (; y >= w.getMinHeight(); y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.getType().isSolid() || b.isLiquid()) return b;
        }
        return null;
    }

    public static List<Block> blocksInSphere(World w, Location center, int r) {
        List<Block> list = new ArrayList<>();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        int r2 = r*r;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x*x + y*y + z*z <= r2) {
                        list.add(w.getBlockAt(cx+x, cy+y, cz+z));
                    }
                }
            }
        }
        return list;
    }

    public static List<Block> neighbors6(Block b) {
        List<Block> list = new ArrayList<>(6);
        World w = b.getWorld();
        int x=b.getX(), y=b.getY(), z=b.getZ();
        list.add(w.getBlockAt(x+1,y,z));
        list.add(w.getBlockAt(x-1,y,z));
        list.add(w.getBlockAt(x,y+1,z));
        list.add(w.getBlockAt(x,y-1,z));
        list.add(w.getBlockAt(x,y,z+1));
        list.add(w.getBlockAt(x,y,z-1));
        return list;
    }
}
