package net.apocalypse.plugin.disaster;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;

/**
 * 재앙 자동 발생 켜기/끄기 상태만 별도로 저장하는 작은 파일(toggles.yml)이다.
 * config.yml은 플러그인이 켜질 때마다 JAR 안의 최신 기본값으로 통째로 덮어써지기 때문에,
 * 서버를 재시작해도 남아있어야 하는 유일한 값(토글)만 이 파일에 따로 보관한다.
 */
public class ToggleStore {

    private final Plugin plugin;
    private final File file;
    private YamlConfiguration data;

    public ToggleStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "toggles.yml");
        load();
    }

    private void load() {
        data = YamlConfiguration.loadConfiguration(file);
    }

    public boolean isGlobalEnabled() {
        return data.getBoolean("global-enabled", true);
    }

    public void setGlobalEnabled(boolean enabled) {
        data.set("global-enabled", enabled);
        save();
    }

    public boolean isDisasterEnabled(String id) {
        return data.getBoolean("disasters." + id, true);
    }

    public void setDisasterEnabled(String id, boolean enabled) {
        data.set("disasters." + id, enabled);
        save();
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("토글 상태를 저장하지 못했습니다: " + e.getMessage());
        }
    }
}
