package com.kakamine.minedisaster.command;

import com.kakamine.minedisaster.MineDisaster;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public class DisasterCommand implements CommandExecutor {

    private final MineDisaster plugin;

    public DisasterCommand(MineDisaster plugin) {
        this.plugin = plugin;
    }

    private boolean checkPerm(CommandSender sender, String node) {
        if (!(sender instanceof Player p)) return true; // 콘솔 허용
        if (p.hasPermission(node) || p.isOp()) return true;
        sender.sendMessage("§c권한이 없습니다: §7" + node);
        return false;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§6====== §eMineDisaster 명령어 도움말 §6======");
        sender.sendMessage("§e/" + label + " meteor [개수] [폭발력]§7 - §f바라보는 방향으로 유성 떨어뜨리기");
        sender.sendMessage("   §8예) §7/" + label + " meteor 3 12");
        sender.sendMessage("§e/" + label + " stop meteor§7 - §f메테오 관련 엔티티/작업 정리");
        sender.sendMessage("§e/" + label + " bacteria [반경] [틱당감염]§7 - §f현재 위치에서 박테리아 확산 시작");
        sender.sendMessage("   §8예) §7/" + label + " bacteria 6 120");
        sender.sendMessage("§e/" + label + " stop bacteria§7 - §f박테리아 중지 및 스컬크 흔적 제거");
        sender.sendMessage("§e/" + label + " doomsday <start|stop|set|auto|info> [값]§7 - §f지구 멸망 제어");
        sender.sendMessage("   §8예) §7/" + label + " doomsday set 1.0");
        sender.sendMessage("§e/" + label + " earthquake <start|stop> [규모] [반경] [초]§7 - §f지진 제어");
        sender.sendMessage("   §8예) §7/" + label + " earthquake start 5.0 24 15");
        sender.sendMessage("§e/" + label + " reload§7 - §f설정 리로드");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        // ─────────── 메테오 ───────────
        if (args[0].equalsIgnoreCase("meteor")) {
            if (!checkPerm(sender, "minedisaster.meteor")) return true;
            if (!(sender instanceof Player p)) {
                sender.sendMessage("§c플레이어만 사용할 수 있습니다.");
                return true;
            }

            int count = plugin.getConfig().getInt("meteor.count", 3);
            int power = plugin.getConfig().getInt("meteor.explosion-power", 12);
            int maxP  = Math.max(10, plugin.getConfig().getInt("meteor.explosion-max", 20));

            if (args.length >= 2) try { count = Math.max(1, Integer.parseInt(args[1])); } catch (NumberFormatException ignored) {}
            if (args.length >= 3) try { power = Math.max(1, Math.min(Integer.parseInt(args[2]), maxP)); } catch (NumberFormatException ignored) {}

            // 시선 조준(최대 96m), 너무 멀면 내 위치로 보정
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection().normalize();
            Location target = eye.clone().add(dir.multiply(96));
            for (double d = 2; d <= 96; d += 1.5) {
                Location probe = eye.clone().add(dir.clone().multiply(d));
                if (probe.getBlock().getType().isSolid()) { target = probe.getBlock().getLocation().add(0.5,1,0.5); break; }
            }
            double maxAim = 128.0;
            if (target.distanceSquared(p.getLocation()) > maxAim*maxAim) target = p.getLocation();

            plugin.getMeteorManager().spawnMeteorShower(p.getWorld(), target, count, power);
            sender.sendMessage("§c[MineDisaster] 메테오 강하: §f개수=" + count + " §7폭발력=" + power);
            return true;
        }
        if (args[0].equalsIgnoreCase("stop") && args.length >= 2 && args[1].equalsIgnoreCase("meteor")) {
            if (!checkPerm(sender, "minedisaster.meteor")) return true;
            plugin.getMeteorManager().cancelAll();
            sender.sendMessage("§a[MineDisaster] 메테오 정리 완료.");
            return true;
        }

        // ─────────── 박테리아 ───────────
        if (args[0].equalsIgnoreCase("bacteria")) {
            if (!checkPerm(sender, "minedisaster.bacteria")) return true;
            if (!(sender instanceof Player p)) {
                sender.sendMessage("§c플레이어만 사용할 수 있습니다.");
                return true;
            }

            int radius = 4;
            int perTick = 0; // 0이면 config 값 사용

            if (args.length >= 2) try { radius = Math.max(0, Integer.parseInt(args[1])); } catch (NumberFormatException ignored) {}
            if (args.length >= 3) try { perTick = Math.max(0, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) {}

            plugin.getBacteriaManager().startInfection(p.getLocation(), radius, (perTick>0?perTick:null), 0);
            sender.sendMessage("§2[MineDisaster] 박테리아 시작: §fradius=" + radius + " §7perTick=" + (perTick>0?perTick:"(config)"));
            return true;
        }
        if (args[0].equalsIgnoreCase("stop") && args.length >= 2 && args[1].equalsIgnoreCase("bacteria")) {
            if (!checkPerm(sender, "minedisaster.bacteria")) return true;
            plugin.getBacteriaManager().cancelAll();
            sender.sendMessage("§a[MineDisaster] 박테리아 정지 + 흔적 제거 완료.");
            return true;
        }

        // ─────────── 둠스데이 ───────────
        if (args[0].equalsIgnoreCase("doomsday")) {
            if (!checkPerm(sender, "minedisaster.doomsday.manage")) return true;

            if (args.length == 1) { sendHelp(sender, label); return true; }

            switch (args[1].toLowerCase()) {
                case "start" -> {
                    plugin.getDoomsdayManager().start();
                    sender.sendMessage("§c[MineDisaster] 지구 멸망 시작.");
                }
                case "stop" -> {
                    plugin.getDoomsdayManager().stop();
                    sender.sendMessage("§a[MineDisaster] 지구 멸망 중지.");
                }
                case "set" -> {
                    if (args.length < 3) { sender.sendMessage("§e사용법: /" + label + " doomsday set <0.0~1.0>"); return true; }
                    try {
                        double v = Double.parseDouble(args[2]);
                        if (v < 0 || v > 1) { sender.sendMessage("§c0.0~1.0 사이로 입력하세요."); return true; }
                        boolean ok = plugin.getDoomsdayManager().setManualSeverity(v);
                        sender.sendMessage(ok ? "§c수동 강도 고정: §f"+v : "§7수동 설정이 비활성화되어 있습니다.");
                    } catch (NumberFormatException ex) {
                        sender.sendMessage("§c숫자를 입력하세요. 예) /" + label + " doomsday set 0.75");
                    }
                }
                case "auto" -> {
                    plugin.getDoomsdayManager().unsetManual();
                    sender.sendMessage("§a자동 모드로 전환.");
                }
                case "info" -> {
                    World w = (sender instanceof Entity ent) ? ent.getWorld() : Bukkit.getWorlds().get(0);
                    double cur = plugin.getDoomsdayManager().getCurrentSeverity(w);
                    long day = (w.getFullTime() / 24000L);
                    sender.sendMessage("§6[MineDisaster] 정보: §7World day=" + day + " §7Severity=" + String.format("%.3f", cur) +
                            (plugin.getDoomsdayManager().isManualMode() ? " §c(MANUAL)" : " §a(AUTO)"));
                }
                default -> sendHelp(sender, label);
            }
            return true;
        }

        // ─────────── 지진 ───────────
        if (args[0].equalsIgnoreCase("earthquake")) {
            if (!checkPerm(sender, "minedisaster.earthquake")) return true;
            if (!(sender instanceof Player p)) { sender.sendMessage("§c플레이어만 가능합니다."); return true; }

            if (args.length < 2) { sender.sendMessage("§e사용법: /" + label + " earthquake <start|stop> [규모] [반경] [초]"); return true; }

            switch (args[1].toLowerCase()) {
                case "start" -> {
                    double mag = (args.length >= 3) ? parseDouble(args[2], 5.0) : 5.0;
                    int rad    = (args.length >= 4) ? parseInt(args[3], 24)  : 24;
                    int sec    = (args.length >= 5) ? parseInt(args[4], 15)  : 15;
                    plugin.getEarthquakeManager().start(p.getLocation(), mag, rad, sec);
                    sender.sendMessage("§6지진 시작: §fm=" + mag + " §7r=" + rad + " §7t=" + sec + "s");
                }
                case "stop" -> {
                    plugin.getEarthquakeManager().stopAll();
                    sender.sendMessage("§a모든 지진 종료");
                }
                default -> sender.sendMessage("§e사용법: /" + label + " earthquake <start|stop> [규모] [반경] [초]");
            }
            return true;
        }

        // ─────────── 리로드 ───────────
        if (args[0].equalsIgnoreCase("reload")) {
            if (!checkPerm(sender, "minedisaster.reload")) return true;
            plugin.reloadConfig();
            if (plugin.getBacteriaManager()!=null) plugin.getBacteriaManager().reloadTypes();
            if (plugin.getDoomsdayManager()!=null) plugin.getDoomsdayManager().reloadConfig();
            sender.sendMessage("§aconfig.yml 리로드 완료.");
            return true;
        }

        // 알려지지 않은 서브커맨드
        sendHelp(sender, label);
        return true;
    }

    private int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }
    private double parseDouble(String s, double def) { try { return Double.parseDouble(s); } catch (Exception e) { return def; } }
}
