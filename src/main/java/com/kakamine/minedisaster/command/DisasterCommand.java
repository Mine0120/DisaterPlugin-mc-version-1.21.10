package com.kakamine.minedisaster.command;

import com.kakamine.minedisaster.MineDisaster;
import com.kakamine.minedisaster.disaster.BacteriaManager;
import com.kakamine.minedisaster.disaster.DoomsdayManager;
import com.kakamine.minedisaster.disaster.MeteorManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public class DisasterCommand implements CommandExecutor {

    private final MineDisaster plugin;

    public DisasterCommand(MineDisaster plugin) {
        this.plugin = plugin;
    }

    private boolean checkPerm(CommandSender sender, String node) {
        if (!(sender instanceof Player p)) return true; // 콘솔은 허용
        if (p.hasPermission(node) || p.isOp()) return true;
        sender.sendMessage("§c권한이 없습니다: §7" + node);
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {
            sender.sendMessage("§6[MineDisaster] 사용법:");
            sender.sendMessage("  §e/" + label + " meteor [count] [power]§7  - 메테오 강하");
            sender.sendMessage("  §e/" + label + " stop meteor§7       - 메테오 중지/정리");
            sender.sendMessage("  §e/" + label + " bacteria [radius] [perTick]§7 - 박테리아 시작");
            sender.sendMessage("  §e/" + label + " stop bacteria§7    - 박테리아 중지/정리");
            sender.sendMessage("  §e/" + label + " doomsday <start|stop|set|auto|info> [val]§7 - 지구 멸망 제어");
            sender.sendMessage("  §e/" + label + " reload§7            - 설정 리로드");
            return true;
        }

        // ─────────────────────────────────────
        // /disaster meteor [count] [power]
        // ─────────────────────────────────────
        if (args[0].equalsIgnoreCase("meteor")) {
            if (!checkPerm(sender, "minedisaster.meteor")) return true;

            int count = plugin.getConfig().getInt("meteor.count", 3);
            int power = plugin.getConfig().getInt("meteor.explosion-power", 7);
            int maxP  = Math.max(10, plugin.getConfig().getInt("meteor.explosion-max", 20));

            if (args.length >= 2) {
                try { count = Math.max(1, Integer.parseInt(args[1])); } catch (NumberFormatException ignored) {}
            }
            if (args.length >= 3) {
                try { power = Math.max(1, Math.min(Integer.parseInt(args[2]), maxP)); } catch (NumberFormatException ignored) {}
            }

            Location target = getSenderTargetLocation(sender);
            if (target == null) {
                sender.sendMessage("§c플레이어가 아니면 타겟 위치를 알 수 없습니다. 게임 내에서 실행하세요.");
                return true;
            }

            MeteorManager mm = plugin.getMeteorManager();
            mm.spawnMeteorShower(target.getWorld(), target, count, power);
            sender.sendMessage("§c[MineDisaster] 메테오 강하: §fcount=" + count + "§7, §fpower=" + power);
            return true;
        }

        // /disaster stop meteor
        if (args[0].equalsIgnoreCase("stop") && args.length >= 2 && args[1].equalsIgnoreCase("meteor")) {
            if (!checkPerm(sender, "minedisaster.meteor")) return true;
            plugin.getMeteorManager().cancelAll();
            sender.sendMessage("§a[MineDisaster] 메테오 관련 엔티티/태스크 정리 완료.");
            return true;
        }

        // ─────────────────────────────────────
        // /disaster bacteria [radius] [perTick]
        // ─────────────────────────────────────
        if (args[0].equalsIgnoreCase("bacteria")) {
            if (!checkPerm(sender, "minedisaster.bacteria")) return true;

            int radius = 2;
            int perTick = 0; // 0이면 config 값 사용

            if (args.length >= 2) {
                try { radius = Math.max(0, Integer.parseInt(args[1])); } catch (NumberFormatException ignored) {}
            }
            if (args.length >= 3) {
                try { perTick = Math.max(0, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) {}
            }

            Location target = getSenderTargetLocation(sender);
            if (target == null) {
                sender.sendMessage("§c플레이어가 아니면 타겟 위치를 알 수 없습니다. 게임 내에서 실행하세요.");
                return true;
            }

            BacteriaManager bm = plugin.getBacteriaManager();
            bm.startInfection(target, radius, perTick, 0);
            sender.sendMessage("§2[MineDisaster] 박테리아 시작: §fradius=" + radius + "§7, §fperTick=" + (perTick>0?perTick:"(config)"));
            return true;
        }

        // /disaster stop bacteria
        if (args[0].equalsIgnoreCase("stop") && args.length >= 2 && args[1].equalsIgnoreCase("bacteria")) {
            if (!checkPerm(sender, "minedisaster.bacteria")) return true;
            plugin.getBacteriaManager().cancelAll(); // 감염 블록 즉시 정리(코드에 구현됨)
            sender.sendMessage("§a[MineDisaster] 박테리아 정지 및 감염 흔적 정리 완료.");
            return true;
        }

        // ─────────────────────────────────────
        // /disaster doomsday <start|stop|set|auto|info>
        // ─────────────────────────────────────
        if (args[0].equalsIgnoreCase("doomsday")) {
            if (!checkPerm(sender, "minedisaster.doomsday.manage")) return true;

            if (args.length == 1) {
                sender.sendMessage("§e사용법: /" + label + " doomsday <start|stop|set|auto|info> [값]");
                return true;
            }

            DoomsdayManager dm = plugin.getDoomsdayManager();
            switch (args[1].toLowerCase()) {
                case "start" -> {
                    dm.start();
                    sender.sendMessage("§c[MineDisaster] 지구 멸망 시작.");
                }
                case "stop" -> {
                    dm.stop();
                    sender.sendMessage("§a[MineDisaster] 지구 멸망 중지.");
                }
                case "set" -> {
                    if (args.length < 3) {
                        sender.sendMessage("§e사용법: /" + label + " doomsday set <0.0~1.0>");
                        return true;
                    }
                    try {
                        double v = Double.parseDouble(args[2]);
                        if (v < 0.0 || v > 1.0) {
                            sender.sendMessage("§c0.0~1.0 사이로 입력하세요.");
                            return true;
                        }
                        boolean ok = dm.setManualSeverity(v);
                        if (ok) sender.sendMessage("§c[MineDisaster] 강도 수동 고정: §f" + v);
                        else    sender.sendMessage("§7수동 설정이 비활성화되어 있습니다 (config: doomsday.allow-manual=false).");
                    } catch (NumberFormatException ex) {
                        sender.sendMessage("§c숫자를 입력하세요. 예) /" + label + " doomsday set 0.75");
                    }
                }
                case "auto" -> {
                    dm.unsetManual();
                    sender.sendMessage("§a[MineDisaster] 자동 모드로 전환.");
                }
                case "info" -> {
                    World w = (sender instanceof Entity ent) ? ent.getWorld() : Bukkit.getWorlds().get(0);
                    double cur = dm.getCurrentSeverity(w);
                    boolean manual = dm.isManualMode();
                    long day = (w.getFullTime() / 24000L);
                    sender.sendMessage("§6[MineDisaster] 지구 멸망 정보");
                    sender.sendMessage("  §7World day: §f" + day);
                    sender.sendMessage("  §7Mode: " + (manual ? "§cMANUAL" : "§aAUTO"));
                    sender.sendMessage("  §7Severity: §f" + String.format("%.3f", cur));
                }
                default -> sender.sendMessage("§e사용법: /" + label + " doomsday <start|stop|set|auto|info> [값]");
            }
            return true;
        }

        // ─────────────────────────────────────
        // /disaster reload
        // ─────────────────────────────────────
        if (args[0].equalsIgnoreCase("reload")) {
            if (!checkPerm(sender, "minedisaster.reload")) return true;
            plugin.reloadConfig();
            if (plugin.getBacteriaManager() != null) plugin.getBacteriaManager().reloadTypes();
            if (plugin.getDoomsdayManager() != null) plugin.getDoomsdayManager().reloadConfig();
            sender.sendMessage("§a[MineDisaster] config.yml 리로드 완료.");
            return true;
        }

        sender.sendMessage("§e사용법: /" + label + " (meteor|bacteria|doomsday|stop|reload) ...");
        return true;
    }

    private Location getSenderTargetLocation(CommandSender sender) {
        if (sender instanceof Player p) {
            return p.getLocation();
        }
        return null;
    }
}
