package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import net.apocalypse.plugin.util.ColorUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Random;

/**
 * 타겟 플레이어를 정해 그 위치를 그 자리에서 고정하고, 주변 반경 안 플레이어들에게만
 * 국지적인 경고 메시지를 보낸 뒤, 몇 초 후 그 고정된 위치를 중심으로 원기둥 모양 구덩이가 무너져 내린다.
 * 지금까지의 재앙과 달리 "서서히 진행"이 아니라 순간적으로 벌어지는 반응 속도 테스트형 재앙이다.
 */
public class SinkholeDisaster implements Disaster {

    private final Random random = new Random();

    @Override
    public String getId() {
        return "sinkhole";
    }

    @Override
    public String getDisplayName() {
        return "싱크홀";
    }

    @Override
    public DangerLevel getDangerLevel() {
        return DangerLevel.LEVEL_3;
    }

    @Override
    public void trigger(DisasterContext context) {
        List<Player> players = context.players();
        if (players.isEmpty()) {
            return;
        }

        Plugin plugin = context.plugin();
        World world = context.world();
        ConfigurationSection section = context.config();

        double warningRadius = Math.max(0, section.getDouble("warning-radius", 15));
        // 명령어/아포칼립스 연쇄처럼 즉시 발동된 경우엔 "조용히 기다리는" 지연을 건너뛰고 바로 무너뜨린다.
        long collapseDelayTicks = context.immediate() ? 0
                : Math.max(0, section.getLong("collapse-delay-seconds", 5)) * 20L;
        double sinkholeRadius = Math.max(1, section.getDouble("sinkhole-radius", 4));
        int sinkholeDepth = Math.max(1, section.getInt("sinkhole-depth", 20));
        Component localWarning = ColorUtil.parse(
                section.getString("local-warning-message", "&c&l발 밑에 미세한 진동이 느껴집니다..."));

        // 타겟 위치를 지금 이 순간 고정한다. 이후 플레이어가 움직여도 붕괴 지점은 바뀌지 않는다.
        Player target = players.get(random.nextInt(players.size()));
        Location center = target.getLocation().clone();

        // 타겟과 그 주변 반경 안에 있는 플레이어들에게만(전체 채팅이 아니라) 국지적인 경고를 보낸다.
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distance(center) <= warningRadius) {
                player.sendMessage(localWarning);
            }
        }

        context.track(new BukkitRunnable() {
            @Override
            public void run() {
                collapseSinkhole(world, center, sinkholeRadius, sinkholeDepth);
            }
        }.runTaskLater(plugin, collapseDelayTicks));
    }

    /** 고정된 위치를 중심으로 원기둥 모양 구덩이를 만들며 지면 아래로 파고든다. */
    private void collapseSinkhole(World world, Location center, double radius, int depth) {
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int surfaceY = world.getHighestBlockYAt(cx, cz);
        int r = (int) Math.ceil(radius);
        double radiusSquared = radius * radius;
        int bottomY = Math.max(world.getMinHeight(), surfaceY - depth);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                for (int y = surfaceY; y >= bottomY; y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().isAir()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }

        world.playSound(center, Sound.ENTITY_WARDEN_DIG, 5.0f, 0.8f);
    }
}
