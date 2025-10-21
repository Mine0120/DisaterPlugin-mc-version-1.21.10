package com.kakamine.minedisaster.command;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.command.*;

import java.util.*;

public class DisasterTab implements TabCompleter {
    public DisasterTab(MineDisaster plugin) {}

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("disaster")) return List.of();

        if (args.length == 1)
            return prefix(List.of("meteor","bacteria","doomsday","earthquake","reload"), args[0]);

        if (args[0].equalsIgnoreCase("doomsday") && args.length == 2)
            return prefix(List.of("start","stop","set","auto"), args[1]);

        if (args[0].equalsIgnoreCase("earthquake")) {
            if (args.length == 2) return prefix(List.of("start","stop"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("start")) return prefix(List.of("3.0","5.0","7.0"), args[2]);
        }

        if (args[0].equalsIgnoreCase("meteor") && args.length == 2)
            return prefix(List.of("1","3","5","7","10"), args[1]);

        if (args[0].equalsIgnoreCase("bacteria") && args.length == 2)
            return prefix(List.of("4","6","8","10"), args[1]);

        return List.of();
    }

    private List<String> prefix(List<String> all, String start) {
        List<String> r = new ArrayList<>();
        for (String s : all)
            if (s.toLowerCase().startsWith(start.toLowerCase())) r.add(s);
        return r;
    }
}
