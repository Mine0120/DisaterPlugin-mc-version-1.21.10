package com.kakamine.minedisaster.command;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.List;

public class DisasterCommand implements CommandExecutor {
    private final MineDisaster plugin;

    public DisasterCommand(MineDisaster plugin) {
        this.plugin = plugin;
    }

    private boolean checkPerm(CommandSender s, String perm) {
        if (!s.hasPermission(perm)) {
            s.sendMessage("§c권한이 없습니다.");
            return false;
        }
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e사용법:");
            sender.sendMessage("  §e/" + label + " meteor <count> <power>");
            sender.sendMessage("  §e/" + label + " bacteria <radius> <perTick>");
            sender.sendMessage("  §e/" + label + " doomsday <start|stop|set|auto>");
            sender.sendMessage("  §e/" + label + " earthquake <start|stop>");
            sender.sendMessage("  §e/" + label + " reload");
            return true;
        }

        // ▷ 메테오
        if (args[0].equalsIgnoreCase("meteor")) {
            if (!(sender instanceof Player p)) return true;
            if (!checkPerm(sender, "minedisaster.meteor")) return true;

            int count = (args.length >= 2) ? Integer.parseInt(args[1]) : 3;
            int power = (args.length >= 3) ? Integer.parseInt(args[2]) : 7;

            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection().normalize();
            Location target = eye.clone().add(dir.multiply(96));

            plugin.getMeteorManager().spawnMeteorShower(p.getWorld(), target, count, power);
            sender.sendMessage("§c[MineDisaster] 메테오: count=" + count + ", power=" + power);
            return true;
        }

        // ▷ 박테리아
        if (args[0].equalsIgnoreCase("bacteria")) {
            if (!(sender instanceof Player p)) return true;
            if (!checkPerm(sender, "minedisaster.bacteria")) return true;

            int radius = (args.length >= 2) ? Integer.parseInt(args[1]) : 5;
            int perTick = (args.length >= 3) ? Integer.parseInt(args[2]) : 100;

            plugin.getBacteriaManager().startInfection(p.getLocation(), radius, perTick, 0);
            sender.sendMessage("§a[MineDisaster] 박테리아 감염 시작");
            return true;
        }

        // ▷ 둠스데이
        if (args[0].equalsIgnoreCase("doomsday")) {
            if (!checkPerm(sender, "minedisaster.doomsday")) return true;
            if (args.length < 2) {
                sender.sendMessage("§e사용법: /" + label + " doomsday <start|stop|set|auto>");
                return true;
            }

            switch (args[1].toLowerCase()) {
                case "start" -> {
                    plugin.getDoomsdayManager().start();
                    sender.sendMessage("§c[MineDisaster] 지구멸망 시작!");
                }
                case "stop" -> {
                    plugin.getDoomsdayManager().stop();
                    sender.sendMessage("§a[MineDisaster] 지구멸망 정지");
                }
                case "set" -> {
                    if (args.length < 3) return true;
                    double sev = Double.parseDouble(args[2]);
                    plugin.getDoomsdayManager().setManualSeverity(sev);
                    sender.sendMessage("§c강도 수동 설정: §f" + sev);
                }
                case "auto" -> {
                    plugin.getDoomsdayManager().unsetManual();
                    sender.sendMessage("§7자동 모드로 복귀");
                }
            }
            return true;
        }

        // ▷ 지진
        if (args[0].equalsIgnoreCase("earthquake")) {
            if (!checkPerm(sender, "minedisaster.earthquake")) return true;
            if (!(sender instanceof Player p)) return true;

            if (args.length < 2) {
                sender.sendMessage("§e사용법: /" + label + " earthquake <start|stop> [magnitude] [radius] [durationSec]");
                return true;
            }

            switch (args[1].toLowerCase()) {
                case "start" -> {
                    double mag = (args.length >= 3) ? Double.parseDouble(args[2]) : 5.0;
                    int rad = (args.length >= 4) ? Integer.parseInt(args[3]) : 24;
                    int dur = (args.length >= 5) ? Integer.parseInt(args[4]) : 15;
                    plugin.getEarthquakeManager().start(p.getLocation(), mag, rad, dur);
                    sender.sendMessage("§6지진 시작: m=" + mag + " r=" + rad + " t=" + dur + "s");
                }
                case "stop" -> {
                    plugin.getEarthquakeManager().stopAll();
                    sender.sendMessage("§a모든 지진 종료");
                }
            }
            return true;
        }

        // ▷ 리로드
        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            sender.sendMessage("§aMineDisaster 설정 리로드 완료");
            return true;
        }

        return true;
    }
}
