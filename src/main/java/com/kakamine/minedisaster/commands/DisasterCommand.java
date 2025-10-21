package com.kakamine.minedisaster.commands;

import com.kakamine.minedisaster.MineDisaster;
import com.kakamine.minedisaster.disaster.*;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DisasterCommand implements CommandExecutor, TabCompleter {

    private final MineDisaster plugin;
    private final MeteorManager meteor;
    private final EarthquakeManager quake;
    private final BacteriaManager bacteria;
    private final DoomsdayManager doom;

    public DisasterCommand(MineDisaster plugin, MeteorManager meteor, EarthquakeManager quake,
                           BacteriaManager bacteria, DoomsdayManager doom) {
        this.plugin = plugin;
        this.meteor = meteor;
        this.quake = quake;
        this.bacteria = bacteria;
        this.doom = doom;
    }

    private int clamp(int val, int max) { return Math.min(val, max); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!sender.hasPermission("minedisasters.use")) {
            sender.sendMessage(ChatColor.RED + "권한이 없습니다: minedisasters.use");
            return true;
        }
        if (a.length == 0) {
            help(sender, label);
            return true;
        }

        String sub = a[0].toLowerCase();
        switch (sub) {
            case "meteor" -> {
                int count = plugin.getConfig().getInt("meteor.count", 5);
                int power = plugin.getConfig().getInt("meteor.explosion-power", 4);
                if (a.length >= 2) try { count = Integer.parseInt(a[1]); } catch (NumberFormatException ignored) {}
                if (a.length >= 3) try { power = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) {}

                count = Math.max(1, Math.min(count, 100));
                power = Math.max(1, Math.min(power, 10));

                Location target = p.getTargetBlockExact(100) != null
                        ? p.getTargetBlockExact(100).getLocation().add(0.5, 0, 0.5)
                        : p.getLocation();

                meteor.spawnMeteorShower(p.getWorld(), target, count, power);
                sender.sendMessage(ChatColor.GOLD + "메테오 " + count + "개, 폭발력 " + power + "!");
            }
            case "quake", "earthquake" -> {
                int radius = plugin.getConfig().getInt("earthquake.radius", 20);
                int seconds = plugin.getConfig().getInt("earthquake.seconds", 10);
                if (a.length >= 2) try { radius = Integer.parseInt(a[1]); } catch (NumberFormatException ignored) {}
                if (a.length >= 3) try { seconds = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) {}

                radius = Math.max(5, clamp(radius, plugin.getConfig().getInt("max-radius", 64)));
                seconds = Math.max(1, Math.min(seconds, plugin.getConfig().getInt("max-duration-seconds", 60)));

                quake.startEarthquake(p.getLocation(), radius, seconds);
                sender.sendMessage(ChatColor.AQUA + "지진 시작! 반경 " + radius + ", " + seconds + "초");
            }
            case "bacteria", "bac" -> {
                int startRadius = plugin.getConfig().getInt("bacteria.start-radius", 1);
                int perTick = plugin.getConfig().getInt("bacteria.per-tick", 80);
                int maxTotal = plugin.getConfig().getInt("max-blocks-total", 5000);
                if (a.length >= 2) try { startRadius = Integer.parseInt(a[1]); } catch (NumberFormatException ignored) {}
                if (a.length >= 3) try { perTick = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) {}
                if (a.length >= 4) try { maxTotal = Integer.parseInt(a[3]); } catch (NumberFormatException ignored) {}

                startRadius = Math.max(0, clamp(startRadius, plugin.getConfig().getInt("max-radius", 64)));
                perTick = Math.max(1, Math.min(perTick, 2000));
                maxTotal = Math.max(100, Math.min(maxTotal, 20000));

                Location origin = p.getTargetBlockExact(100) != null
                        ? p.getTargetBlockExact(100).getLocation()
                        : p.getLocation();

                bacteria.startInfection(origin, startRadius, perTick, maxTotal);
                sender.sendMessage(ChatColor.DARK_GREEN + "박테리아 확산 시작! 중심 " +
                        origin.getBlockX() + "," + origin.getBlockY() + "," + origin.getBlockZ());
            }
            case "doomsday", "doom" -> {
                if (a.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "사용법: /" + label + " doomsday <start|stop|status>");
                    return true;
                }
                String op = a[1].toLowerCase();
                switch (op) {
                    case "start" -> {
                        if (!doom.isRunning()) {
                            doom.start();
                            sender.sendMessage(ChatColor.RED + "지구 멸망(태양 과열) 시작!");
                        } else {
                            sender.sendMessage(ChatColor.GRAY + "이미 진행 중입니다.");
                        }
                    }
                    case "stop" -> {
                        if (doom.isRunning()) {
                            doom.stop();
                            sender.sendMessage(ChatColor.GREEN + "지구 멸망 중지.");
                        } else {
                            sender.sendMessage(ChatColor.GRAY + "진행 중이 아닙니다.");
                        }
                    }
                    case "status" -> {
                        sender.sendMessage(ChatColor.YELLOW + "지구 멸망 상태: " +
                                (doom.isRunning() ? ChatColor.RED + "진행 중" : ChatColor.GRAY + "중지됨"));
                    }
                    default -> sender.sendMessage(ChatColor.YELLOW + "알 수 없는 옵션: " + op);
                }
            }
            case "stop" -> {
                if (a.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "사용법: /" + label + " stop <meteor|quake|bacteria|doomsday|all>");
                    return true;
                }
                String which = a[1].toLowerCase();
                switch (which) {
                    case "meteor" -> { meteor.cancelAll(); sender.sendMessage(ChatColor.GREEN + "메테오 중지."); }
                    case "quake", "earthquake" -> { quake.cancelAll(); sender.sendMessage(ChatColor.GREEN + "지진 중지."); }
                    case "bacteria", "bac" -> { bacteria.cancelAll(); sender.sendMessage(ChatColor.GREEN + "박테리아 중지."); }
                    case "doomsday", "doom" -> { doom.stop(); sender.sendMessage(ChatColor.GREEN + "지구 멸망 중지."); }
                    case "all" -> {
                        meteor.cancelAll(); quake.cancelAll(); bacteria.cancelAll(); doom.stop();
                        sender.sendMessage(ChatColor.GREEN + "모든 재앙 중지.");
                    }
                    default -> sender.sendMessage(ChatColor.YELLOW + "알 수 없는 대상: " + which);
                }
            }
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "[MineDisaster 상태]");
                sender.sendMessage(ChatColor.YELLOW + " - 메테오: " + ChatColor.WHITE + "수동 이벤트 (활성 태스크 수는 로그로 확인)");
                sender.sendMessage(ChatColor.YELLOW + " - 지진  : " + ChatColor.WHITE + "수동 이벤트 (활성 태스크 수는 로그로 확인)");
                sender.sendMessage(ChatColor.YELLOW + " - 박테리아: " + ChatColor.WHITE + "수동 이벤트 (활성 태스크 수는 로그로 확인)");
                sender.sendMessage(ChatColor.YELLOW + " - 지구멸망: " + (doom.isRunning() ? ChatColor.RED + "진행 중" : ChatColor.GRAY + "중지됨"));
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        s.sendMessage(ChatColor.YELLOW + "사용법:");
        s.sendMessage(ChatColor.GRAY + "/" + label + " meteor [count] [power]");
        s.sendMessage(ChatColor.GRAY + "/" + label + " quake [radius] [seconds]");
        s.sendMessage(ChatColor.GRAY + "/" + label + " bacteria [startRadius] [perTick] [maxBlocks]");
        s.sendMessage(ChatColor.GRAY + "/" + label + " doomsday <start|stop|status>");
        s.sendMessage(ChatColor.GRAY + "/" + label + " stop <meteor|quake|bacteria|doomsday|all>");
        s.sendMessage(ChatColor.GRAY + "/" + label + " status");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return Arrays.asList("meteor","quake","bacteria","doomsday","stop","status");
        if (a.length == 2) {
            return switch (a[0].toLowerCase()) {
                case "meteor" -> List.of("count","power");
                case "quake" -> List.of("radius","seconds");
                case "bacteria" -> List.of("startRadius","perTick","maxBlocks");
                case "doomsday" -> List.of("start","stop","status");
                case "stop" -> List.of("meteor","quake","bacteria","doomsday","all");
                default -> List.of();
            };
        }
        return new ArrayList<>();
    }
}
