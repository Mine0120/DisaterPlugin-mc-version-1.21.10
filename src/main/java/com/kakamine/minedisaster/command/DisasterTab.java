package com.kakamine.minedisaster.command;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.command.*;
import java.util.*;

public class DisasterTab implements TabCompleter {
    public DisasterTab(MineDisaster plugin) {}

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("disaster")) return List.of();

        // 1번째 인자
        if (args.length == 1)
            return prefix(List.of("meteor", "bacteria", "doomsday", "earthquake", "stop", "reload"), args[0]);

        // ─────────── stop ───────────
        if (args[0].equalsIgnoreCase("stop")) {
            if (args.length == 2)
                return prefix(List.of("meteor", "bacteria", "doomsday", "earthquake"), args[1]);
        }

        // ─────────── doomsday ───────────
        if (args[0].equalsIgnoreCase("doomsday")) {
            if (args.length == 2)
                return prefix(List.of("start", "stop", "set", "auto", "info"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("set"))
                return prefix(List.of("0.0", "0.5", "1.0"), args[2]);
        }

        // ─────────── meteor ───────────
        if (args[0].equalsIgnoreCase("meteor")) {
            if (args.length == 2)
                return prefix(List.of("1", "3", "5", "7", "10"), args[1]);
            if (args.length == 3)
                return prefix(List.of("5", "8", "10", "12", "15", "20"), args[2]);
        }

        // ─────────── bacteria ───────────
        if (args[0].equalsIgnoreCase("bacteria")) {
            if (args.length == 2)
                return prefix(List.of("3", "5", "8", "10"), args[1]);
            if (args.length == 3)
                return prefix(List.of("80", "100", "120", "150"), args[2]);
        }

        // ─────────── earthquake ───────────
        if (args[0].equalsIgnoreCase("earthquake")) {
            if (args.length == 2)
                return prefix(List.of("start", "stop"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("start"))
                return prefix(List.of("3.0", "5.0", "7.0"), args[2]);
            if (args.length == 4 && args[1].equalsIgnoreCase("start"))
                return prefix(List.of("16", "24", "32", "40"), args[3]);
            if (args.length == 5 && args[1].equalsIgnoreCase("start"))
                return prefix(List.of("10", "15", "20", "30"), args[4]);
        }

        return List.of();
    }

    private List<String> prefix(List<String> all, String start) {
        List<String> r = new ArrayList<>();
        for (String s : all)
            if (s.toLowerCase().startsWith(start.toLowerCase()))
                r.add(s);
        return r;
    }
}
