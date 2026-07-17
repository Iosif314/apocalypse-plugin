package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import net.apocalypse.plugin.util.PlayerFilter;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Random;

/**
 * 지속 시간 동안 플레이어들의 식량이 서서히 상해간다. 주기적으로 인벤토리 안의 먹을 것 일부가
 * 확률적으로 썩은 살덩이로 변하고(이미 썩은 것 자체는 대상에서 제외), 동시에 허기가 자연 소모보다
 * 빠르게 줄어든다 — 비축한 식량이 줄어드는데 배는 더 빨리 고파지는, "굶주림 그 자체"가 위협인 재앙이다.
 */
public class FamineDisaster implements Disaster {

    private final Random random = new Random();

    @Override
    public String getId() {
        return "famine";
    }

    @Override
    public String getDisplayName() {
        return "기근";
    }

    @Override
    public DangerLevel getDangerLevel() {
        return DangerLevel.LEVEL_2;
    }

    @Override
    public void trigger(DisasterContext context) {
        Plugin plugin = context.plugin();
        World world = context.world();
        ConfigurationSection section = context.config();

        long durationTicks = section.getLong("duration-seconds", 150) * 20L;
        long checkIntervalTicks = Math.max(1, section.getLong("check-interval-ticks", 100));
        double spoilChancePercent = section.getDouble("food-spoil-chance-percent", 25.0);
        int hungerDrainAmount = Math.max(0, section.getInt("hunger-drain-amount", 1));

        context.track(new BukkitRunnable() {
            long elapsed = 0;

            @Override
            public void run() {
                elapsed += checkIntervalTicks;

                for (Player player : world.getPlayers()) {
                    if (!PlayerFilter.isTargetable(player)) {
                        continue;
                    }
                    spoilFood(player, spoilChancePercent);
                    drainHunger(player, hungerDrainAmount);
                }

                if (elapsed >= durationTicks) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, checkIntervalTicks, checkIntervalTicks));
    }

    /**
     * 인벤토리(저장 칸) 각 슬롯마다 확률을 굴려서, 통과하면 그 칸에서 하나가 썩은 살덩이로 변한다.
     * 슬롯을 직접 읽고 쓰는 방식으로 처리한다 — 스냅샷 배열을 따로 만들어뒀다가 마지막에 통째로
     * 되써버리면, 도중에 addItem()이 실제 인벤토리에 넣어준 썩은 살덩이가 스냅샷엔 없어서 지워진다.
     */
    private void spoilFood(Player player, double chancePercent) {
        PlayerInventory inventory = player.getInventory();
        int size = inventory.getStorageContents().length;
        boolean spoiledAny = false;

        for (int slot = 0; slot < size; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !isSpoilable(item.getType())) {
                continue;
            }
            if (random.nextDouble() * 100.0 >= chancePercent) {
                continue;
            }

            item.setAmount(item.getAmount() - 1);
            inventory.setItem(slot, item.getAmount() <= 0 ? null : item);
            spoiledAny = true;

            var leftover = inventory.addItem(new ItemStack(Material.ROTTEN_FLESH, 1));
            if (!leftover.isEmpty()) {
                player.getWorld().dropItem(player.getLocation(), new ItemStack(Material.ROTTEN_FLESH, 1));
            }
        }

        if (spoiledAny) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 0.7f);
        }
    }

    /** 썩은 살덩이 자체는 이미 상한 것이므로 다시 상하지 않는다. */
    private boolean isSpoilable(Material type) {
        return type.isEdible() && type != Material.ROTTEN_FLESH;
    }

    /** 자연 소모보다 빠르게 허기를 줄인다. 포만도가 바닥나면 이후엔 바닐라 굶주림 데미지가 알아서 들어간다. */
    private void drainHunger(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        player.setFoodLevel(Math.max(0, player.getFoodLevel() - amount));
    }
}
