package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import net.apocalypse.plugin.util.ColorUtil;
import net.apocalypse.plugin.util.PlayerFilter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Random;

/**
 * 최상위(6단계) 위험도의 초희귀 재앙. 타겟 플레이어를 정해두고 1분을 기다린 뒤,
 * "소멸 바로 직전 시점"의 타겟 위치를 기준으로 그 청크를 중심으로 한 3x3 청크 범위 안에서
 * 무작위로 청크 하나를 골라 통째로 지워버린다(위치는 발동 시점이 아니라 소멸 시점 기준).
 * 그 청크 안의 모든 블록과 엔티티를 완전히 소멸시킨다.
 * 플레이어 엔티티 자체를 강제로 제거하는 건 연결 처리 등에서 위험하므로 하지 않고,
 * 대신 그 청크 안에 있던 플레이어에게는 사실상 즉사에 해당하는 매우 큰 고정 데미지를 준다.
 */
public class DimensionalAnnihilationDisaster implements Disaster {

    private final Random random = new Random();

    @Override
    public String getId() {
        return "dimensional-annihilation";
    }

    @Override
    public String getDisplayName() {
        return "차원 소멸";
    }

    @Override
    public DangerLevel getDangerLevel() {
        return DangerLevel.LEVEL_6;
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

        double playerDamage = section.getDouble("player-damage", 10000.0);
        // 명령어/아포칼립스 연쇄처럼 즉시 발동된 경우엔 이 "조용히 기다리는" 지연을 건너뛰고 바로 소멸시킨다.
        long annihilationDelayTicks = context.immediate() ? 0
                : Math.max(0, section.getLong("annihilation-delay-seconds", 60)) * 20L;
        Component annihilationMessage = ColorUtil.parse(
                section.getString("annihilation-message", "&4&l차원이 완전히 소멸했습니다!"));

        // 타겟만 지금 정해두고, 위치는 고정하지 않는다 — 소멸 직전(1분 뒤)의 위치를 기준으로 삼는다.
        Player initialTarget = players.get(random.nextInt(players.size()));

        context.track(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player target = initialTarget;
            if (!target.isOnline()) {
                // 그 사이 타겟이 접속을 종료했으면, 그 시점에 그 월드에 있는 플레이어 중 새로 하나를 다시 뽑는다.
                List<Player> onlineNow = PlayerFilter.targetable(world.getPlayers());
                if (onlineNow.isEmpty()) {
                    return;
                }
                target = onlineNow.get(random.nextInt(onlineNow.size()));
            }

            Chunk targetChunk = target.getLocation().getChunk();
            int centerChunkX = targetChunk.getX();
            int centerChunkZ = targetChunk.getZ();

            int offsetX = random.nextInt(3) - 1;
            int offsetZ = random.nextInt(3) - 1;
            Chunk chunk = world.getChunkAt(centerChunkX + offsetX, centerChunkZ + offsetZ);

            annihilateChunk(world, chunk, playerDamage);
            Bukkit.broadcast(annihilationMessage);
        }, annihilationDelayTicks));
    }

    /** 청크 안의 모든 엔티티를 지우고(플레이어는 대신 큰 데미지), 모든 블록을 공기로 만든다. */
    private void annihilateChunk(World world, Chunk chunk, double playerDamage) {
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player player) {
                if (PlayerFilter.isTargetable(player)) {
                    player.damage(playerDamage);
                }
            } else {
                entity.remove();
            }
        }

        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minHeight; y < maxHeight; y++) {
                    Block block = world.getBlockAt(baseX + x, y, baseZ + z);
                    if (!block.getType().isAir()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }
}
