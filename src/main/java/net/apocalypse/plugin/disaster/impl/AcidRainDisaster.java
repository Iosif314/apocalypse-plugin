package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import net.apocalypse.plugin.util.PlayerFilter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

/**
 * 지속 시간 동안 비를 내리게 하고, 그 비를 맞고 있는(하늘이 뚫린 곳에 있는) 플레이어에게
 * 주기적으로 시듦(Wither) 효과를 걸어 지속 데미지를 준다. 지붕 밑으로 피하면 효과가 더 이상
 * 갱신되지 않아 곧 사라지므로, 실내 대피가 유효한 대응 수단이다.
 * 다만 노출된 동안에는 장비 내구도가 부식되고 주변 농작물/경작지도 서서히 상하므로,
 * 실내로 피해도 이미 입은 피해(장비 손상, 농사 피해)는 그대로 남는다.
 */
public class AcidRainDisaster implements Disaster {

    /** 산성비에 부식되는 농작물 종류. */
    private static final Set<Material> CROP_TYPES = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.MELON_STEM, Material.PUMPKIN_STEM, Material.SWEET_BERRY_BUSH
    );

    private final Random random = new Random();

    @Override
    public String getId() {
        return "acid-rain";
    }

    @Override
    public String getDisplayName() {
        return "산성비";
    }

    @Override
    public DangerLevel getDangerLevel() {
        return DangerLevel.LEVEL_2;
    }

    /** 비를 바닐라 날씨 API(setStorm)로 구현하고 시듦 판정도 world.hasStorm()에 의존하는데, 네더/엔드는 날씨를 지원하지 않는다. */
    @Override
    public Set<World.Environment> getSupportedEnvironments() {
        return EnumSet.of(World.Environment.NORMAL);
    }

    @Override
    public void trigger(DisasterContext context) {
        Plugin plugin = context.plugin();
        World world = context.world();
        ConfigurationSection section = context.config();

        long durationTicks = section.getLong("duration-seconds", 120) * 20L;
        long checkIntervalTicks = Math.max(1, section.getLong("rain-check-interval-ticks", 20));
        int witherAmplifier = Math.max(0, section.getInt("wither-amplifier", 4));
        // 효과 한 번 걸릴 때 유지되는 시간. 다음 판정 전에 끊기지 않도록 체크 주기보다는 길게 잡는 게 좋다.
        int effectDurationTicks = (int) Math.max(checkIntervalTicks * 2, section.getLong("wither-duration-seconds", 5) * 20L);
        int armorDurabilityDamage = Math.max(0, section.getInt("armor-durability-damage", 2));
        double cropCorrosionRadius = Math.max(0, section.getDouble("crop-corrosion-radius", 6));
        double cropCorrosionChancePercent = section.getDouble("crop-corrosion-chance-percent", 20.0);

        world.setStorm(true);
        world.setThundering(false);
        world.setWeatherDuration((int) durationTicks);

        context.track(new BukkitRunnable() {
            long elapsed = 0;

            @Override
            public void run() {
                elapsed += checkIntervalTicks;
                applyAcidRain(world, witherAmplifier, effectDurationTicks, armorDurabilityDamage,
                        cropCorrosionRadius, cropCorrosionChancePercent);

                if (elapsed >= durationTicks) {
                    cancel();
                    world.setStorm(false);
                }
            }
        }.runTaskTimer(plugin, checkIntervalTicks, checkIntervalTicks));
    }

    /** /apoc stop으로 강제 중단됐을 때, 자기 자신의 마지막 틱에서만 되돌리던 날씨 상태를 즉시 복구한다. */
    @Override
    public void onStop(DisasterContext context) {
        context.world().setStorm(false);
    }

    /** 비를 맞고 있는 플레이어들에게 시듦 효과를 걸고, 장비를 부식시키고, 주변 농작물/경작지도 부식시킨다. */
    private void applyAcidRain(World world, int amplifier, int effectDurationTicks, int armorDurabilityDamage,
                                double cropRadius, double cropChancePercent) {
        for (Player player : world.getPlayers()) {
            if (!PlayerFilter.isTargetable(player) || !isExposedToRain(world, player)) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, effectDurationTicks, amplifier, true, true));
            corrodeArmor(player, armorDurabilityDamage);
            corrodeCropsNear(world, player.getLocation(), cropRadius, cropChancePercent);
        }
    }

    /** 이 월드에 비가 오는 중이고, 플레이어 머리 위에 지붕(하늘을 가리는 블록)이 없는지 확인한다. */
    private boolean isExposedToRain(World world, Player player) {
        if (!world.hasStorm()) {
            return false;
        }
        Location loc = player.getLocation();
        int highestY = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        return loc.getY() > highestY;
    }

    /**
     * 착용 중인 방어구의 내구도를 조금씩 깎는다. 실내로 피해도 이미 깎인 내구도는 되돌아오지 않아,
     * 산성비가 지나간 뒤에도 장비를 수리해야 하는 여파가 남는다. 파괴 불가 아이템은 건드리지 않는다.
     */
    private void corrodeArmor(Player player, int durabilityDamage) {
        if (durabilityDamage <= 0) {
            return;
        }
        EntityEquipment equipment = player.getEquipment();
        if (equipment == null) {
            return;
        }
        for (ItemStack armor : equipment.getArmorContents()) {
            damageItem(armor, durabilityDamage);
        }
    }

    /** 아이템의 내구도를 amount만큼 깎는다. 다 닳으면(최대 내구도 도달) 아이템 자체를 없앤다. */
    private void damageItem(ItemStack item, int amount) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable) || meta.isUnbreakable()) {
            return;
        }
        int newDamage = damageable.getDamage() + amount;
        if (newDamage >= item.getType().getMaxDurability()) {
            item.setAmount(0);
            return;
        }
        damageable.setDamage(newDamage);
        item.setItemMeta(meta);
    }

    /**
     * 플레이어 주변의 농작물/경작지를 확률적으로 부식시킨다. 이미 다 자란 농작물이나 씨앗을 심은 지
     * 얼마 안 된 농작물이나 상관없이, 자라는 중이면 성장 단계를 한 칸 되돌리고 더 되돌릴 수 없으면 시들어 사라진다.
     * 경작지(흙갈이 된 땅)는 부식되어 일반 흙으로 돌아간다.
     */
    private void corrodeCropsNear(World world, Location center, double radius, double chancePercent) {
        if (radius <= 0 || chancePercent <= 0) {
            return;
        }
        int r = (int) Math.ceil(radius);
        double radiusSquared = radius * radius;
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    Block block = world.getBlockAt(centerX + dx, centerY + dy, centerZ + dz);
                    Material type = block.getType();
                    if (type != Material.FARMLAND && !CROP_TYPES.contains(type)) {
                        continue;
                    }
                    if (random.nextDouble() * 100.0 >= chancePercent) {
                        continue;
                    }
                    corrodeBlock(block);
                }
            }
        }
    }

    private void corrodeBlock(Block block) {
        if (block.getType() == Material.FARMLAND) {
            block.setType(Material.DIRT, false);
            return;
        }
        if (block.getBlockData() instanceof Ageable ageable) {
            // Ageable의 나이는 항상 0부터 시작한다(별도의 "최소 나이" API는 없음).
            if (ageable.getAge() > 0) {
                ageable.setAge(ageable.getAge() - 1);
                block.setBlockData(ageable, false);
            } else {
                block.setType(Material.AIR, false);
            }
        }
    }
}
