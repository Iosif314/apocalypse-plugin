package net.apocalypse.plugin;

import net.apocalypse.plugin.command.ApocalypseCommand;
import net.apocalypse.plugin.disaster.DisasterManager;
import net.apocalypse.plugin.disaster.ToggleStore;
import net.apocalypse.plugin.disaster.impl.AcidRainDisaster;
import net.apocalypse.plugin.disaster.impl.ApocalypseDisaster;
import net.apocalypse.plugin.disaster.impl.BlizzardDisaster;
import net.apocalypse.plugin.disaster.impl.DimensionalAnnihilationDisaster;
import net.apocalypse.plugin.disaster.impl.MeteorStrikeDisaster;
import net.apocalypse.plugin.disaster.impl.PlagueDisaster;
import net.apocalypse.plugin.disaster.impl.SinkholeDisaster;
import net.apocalypse.plugin.disaster.impl.SolarExtinctionDisaster;
import net.apocalypse.plugin.disaster.impl.StormDisaster;
import net.apocalypse.plugin.disaster.impl.ZombieOutbreakDisaster;
import net.apocalypse.plugin.listener.DisasterCleanupListener;
import net.apocalypse.plugin.listener.ZombieDisguiseListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ApocalypsePlugin extends JavaPlugin {

    private DisasterManager disasterManager;

    @Override
    public void onEnable() {
        // config.yml은 켜기/끄기 상태 외엔 서버에서 손댈 일이 없으므로, 매번 JAR 안의 최신 기본값으로 통째로 덮어쓴다.
        // 켜기/끄기 상태는 이 파일과 별개인 toggles.yml(ToggleStore)에 저장되어 재시작해도 유지된다.
        saveResource("config.yml", true);
        reloadConfig();

        ToggleStore toggleStore = new ToggleStore(this);
        disasterManager = new DisasterManager(this, toggleStore);
        disasterManager.register(new MeteorStrikeDisaster());
        disasterManager.register(new SolarExtinctionDisaster());
        disasterManager.register(new ZombieOutbreakDisaster());
        disasterManager.register(new StormDisaster());
        disasterManager.register(new AcidRainDisaster());
        disasterManager.register(new PlagueDisaster());
        disasterManager.register(new BlizzardDisaster());
        disasterManager.register(new DimensionalAnnihilationDisaster());
        disasterManager.register(new SinkholeDisaster());
        disasterManager.register(new ApocalypseDisaster(disasterManager));
        // 새 재앙을 추가할 때는 여기에 disasterManager.register(new XxxDisaster()); 한 줄만 추가하면 됩니다.

        getServer().getPluginManager().registerEvents(new ZombieDisguiseListener(this), this);
        getServer().getPluginManager().registerEvents(new DisasterCleanupListener(this), this);

        ApocalypseCommand commandHandler = new ApocalypseCommand(this, disasterManager);
        PluginCommand command = getCommand("apocalypse");
        if (command != null) {
            command.setExecutor(commandHandler);
            command.setTabCompleter(commandHandler);
        }

        disasterManager.start();
        getLogger().info("Apocalypse 플러그인이 활성화되었습니다.");
    }

    @Override
    public void onDisable() {
        if (disasterManager != null) {
            disasterManager.stop();
        }
        getLogger().info("Apocalypse 플러그인이 비활성화되었습니다.");
    }

    public DisasterManager getDisasterManager() {
        return disasterManager;
    }
}
