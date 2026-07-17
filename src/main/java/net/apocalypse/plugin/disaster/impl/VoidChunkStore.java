package net.apocalypse.plugin.disaster.impl;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 차원 소멸로 지워진 청크 목록을 저장하는 작은 파일(void-chunks.yml)이다.
 * config.yml은 플러그인이 켜질 때마다 JAR 기본값으로 통째로 덮어써지지만, 소멸된 청크는 영구적인 공허로
 * 남아야 하므로 별도 파일에 저장해서 서버 재시작이나 플러그인 리로드(자동 배포 스크립트가 plugman으로
 * 주기적으로 리로드함) 후에도 공허 유지 효과(접근 경고/진입 즉사/블록 설치 거부)가 이어지게 한다.
 */
public class VoidChunkStore {

    private final Plugin plugin;
    private final File file;

    public VoidChunkStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "void-chunks.yml");
    }

    /** 지금까지 저장된 소멸된 청크 목록을 전부 불러온다. */
    public Set<VoidChunkPos> load() {
        Set<VoidChunkPos> result = new HashSet<>();
        if (!file.exists()) {
            return result;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        for (String entry : data.getStringList("void-chunks")) {
            try {
                result.add(VoidChunkPos.deserialize(entry));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("void-chunks.yml의 항목을 읽지 못했습니다: " + entry);
            }
        }
        return result;
    }

    /** 새로 소멸된 청크 하나를 기존 목록에 이어붙여 저장한다. */
    public void append(VoidChunkPos pos) {
        YamlConfiguration data = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        List<String> raw = new ArrayList<>(data.getStringList("void-chunks"));
        raw.add(pos.serialize());
        data.set("void-chunks", raw);
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("void-chunks.yml에 저장하지 못했습니다: " + e.getMessage());
        }
    }
}
