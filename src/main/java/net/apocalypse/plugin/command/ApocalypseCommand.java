package net.apocalypse.plugin.command;

import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterManager;
import net.apocalypse.plugin.util.ColorUtil;
import net.apocalypse.plugin.util.GitHubUpdateNote;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ApocalypseCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("trigger", "toggle", "reload", "list", "stop", "update-note");

    private final Plugin plugin;
    private final DisasterManager disasterManager;

    public ApocalypseCommand(Plugin plugin, DisasterManager disasterManager) {
        this.plugin = plugin;
        this.disasterManager = disasterManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "trigger" -> handleTrigger(sender, args);
            case "toggle" -> handleToggle(sender, args);
            case "reload" -> handleReload(sender);
            case "list" -> handleList(sender);
            case "stop" -> handleStop(sender, args);
            case "update-note" -> handleUpdateNote(sender);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void handleTrigger(CommandSender sender, String[] args) {
        World world = (sender instanceof Player player) ? player.getWorld() : firstConfiguredWorld();
        if (world == null) {
            sender.sendMessage(ColorUtil.parse("&c대상 월드를 찾을 수 없습니다."));
            return;
        }

        if (args.length >= 2) {
            String id = args[1].toLowerCase();
            Disaster disaster = disasterManager.getDisasters().get(id);
            if (disaster == null) {
                sender.sendMessage(ColorUtil.parse("&c알 수 없는 재앙 id입니다: " + id + " (/apocalypse list 로 확인하세요)"));
                return;
            }
            disasterManager.triggerDisaster(id, world);
            sender.sendMessage(ColorUtil.parse("&a[" + world.getName() + "] " + disaster.getDisplayName() + " 재앙을 발생시켰습니다."));
        } else {
            boolean triggered = disasterManager.triggerRandomDisaster(true);
            if (triggered) {
                sender.sendMessage(ColorUtil.parse("&a무작위 재앙을 발생시켰습니다."));
            } else {
                sender.sendMessage(ColorUtil.parse("&c재앙을 발생시킬 수 없습니다. (활성화된 재앙이 없거나 대상 월드에 플레이어가 없습니다)"));
            }
        }
    }

    private void handleToggle(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            String id = args[1].toLowerCase();
            Disaster disaster = disasterManager.getDisasters().get(id);
            if (disaster == null) {
                sender.sendMessage(ColorUtil.parse("&c알 수 없는 재앙 id입니다: " + id + " (/apocalypse list 로 확인하세요)"));
                return;
            }
            boolean newState = disasterManager.toggleDisaster(id);
            if (newState) {
                sender.sendMessage(ColorUtil.parse("&a" + disaster.getDisplayName() + "의 자동 발생을 활성화했습니다. (저장됨)"));
            } else {
                sender.sendMessage(ColorUtil.parse("&e" + disaster.getDisplayName() + "의 자동 발생을 비활성화했습니다. (저장됨)"));
            }
        } else {
            boolean newState = disasterManager.toggleGlobal();
            if (newState) {
                sender.sendMessage(ColorUtil.parse("&a전체 재앙 자동 발생을 활성화했습니다. (저장됨)"));
            } else {
                sender.sendMessage(ColorUtil.parse("&e전체 재앙 자동 발생을 비활성화했습니다. (저장됨)"));
            }
        }
    }

    private void handleStop(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            String id = args[1].toLowerCase();
            Disaster disaster = disasterManager.getDisasters().get(id);
            if (disaster == null) {
                sender.sendMessage(ColorUtil.parse("&c알 수 없는 재앙 id입니다: " + id + " (/apocalypse list 로 확인하세요)"));
                return;
            }
            int stopped = disasterManager.stopDisaster(id);
            if (stopped > 0) {
                sender.sendMessage(ColorUtil.parse("&a" + disaster.getDisplayName() + " 재앙을 멈췄습니다."));
            } else {
                sender.sendMessage(ColorUtil.parse("&e지금 진행 중이거나 대기 중인 " + disaster.getDisplayName() + " 재앙이 없습니다."));
            }
            return;
        }

        int stopped = disasterManager.stopCurrent();
        if (stopped > 0) {
            sender.sendMessage(ColorUtil.parse("&a진행 중이거나 대기 중이던 재앙을 멈췄습니다."));
        } else {
            sender.sendMessage(ColorUtil.parse("&e지금 멈출 재앙이 없습니다."));
        }
    }

    private void handleUpdateNote(CommandSender sender) {
        String repo = plugin.getConfig().getString("settings.github-repo", "");
        if (repo.isBlank()) {
            sender.sendMessage(ColorUtil.parse("&csettings.github-repo가 설정되어 있지 않습니다."));
            return;
        }
        GitHubUpdateNote.fetchLatestCommit(plugin, sender, repo);
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        sender.sendMessage(ColorUtil.parse("&a설정을 다시 불러왔습니다."));
    }

    private void handleList(CommandSender sender) {
        sender.sendMessage(ColorUtil.parse("&6사용 가능한 재앙 목록:"));
        for (Disaster disaster : disasterManager.getDisasters().values()) {
            sender.sendMessage(ColorUtil.parse("&7 - &f" + disaster.getId() + " &7(" + disaster.getDisplayName() + ")"));
        }
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ColorUtil.parse("&6/" + label + " trigger [종류] &7- 재앙을 즉시 발생시킵니다."));
        sender.sendMessage(ColorUtil.parse("&6/" + label + " toggle &7- 전체 재앙 자동 발생을 켜거나 끕니다. (저장됨)"));
        sender.sendMessage(ColorUtil.parse("&6/" + label + " toggle [종류] &7- 특정 재앙만 자동 발생을 켜거나 끕니다. (저장됨)"));
        sender.sendMessage(ColorUtil.parse("&6/" + label + " reload &7- 설정을 다시 불러옵니다."));
        sender.sendMessage(ColorUtil.parse("&6/" + label + " list &7- 사용 가능한 재앙 종류를 확인합니다."));
        sender.sendMessage(ColorUtil.parse("&6/" + label + " stop &7- 진행 중이거나 대기 중인 재앙을 전부 멈춥니다."));
        sender.sendMessage(ColorUtil.parse("&6/" + label + " stop [종류] &7- 특정 재앙만 멈춥니다."));
        sender.sendMessage(ColorUtil.parse("&6/" + label + " update-note &7- GitHub 저장소의 가장 최근 커밋 정보를 보여줍니다."));
    }

    private World firstConfiguredWorld() {
        List<String> names = plugin.getConfig().getStringList("settings.worlds");
        for (String name : names) {
            World world = plugin.getServer().getWorld(name);
            if (world != null) {
                return world;
            }
        }
        return plugin.getServer().getWorlds().isEmpty() ? null : plugin.getServer().getWorlds().get(0);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("trigger") || args[0].equalsIgnoreCase("toggle"))) {
            String prefix = args[1].toLowerCase();
            return disasterManager.getDisasters().keySet().stream()
                    .filter(id -> id.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
            // stop은 아무 재앙 id나 다 받는 게 아니라, 지금 실제로 멈출 수 있는(대기/진행 중인) 것만 추천한다.
            String prefix = args[1].toLowerCase();
            return disasterManager.getStoppableDisasterIds().stream()
                    .filter(id -> id.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
