package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 지속 시간 동안 비를 내리게 하고, 그 비를 맞고 있는(하늘이 뚫린 곳에 있는) 플레이어에게
 * 주기적으로 시듦(Wither) 효과를 걸어 지속 데미지를 준다. 지붕 밑으로 피하면 효과가 더 이상
 * 갱신되지 않아 곧 사라지므로, 실내 대피가 유효한 대응 수단이다.
 */
public class AcidRainDisaster implements Disaster {

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

        world.setStorm(true);
        world.setThundering(false);
        world.setWeatherDuration((int) durationTicks);

        new BukkitRunnable() {
            long elapsed = 0;

            @Override
            public void run() {
                elapsed += checkIntervalTicks;
                applyAcidRain(world, witherAmplifier, effectDurationTicks);

                if (elapsed >= durationTicks) {
                    cancel();
                    world.setStorm(false);
                }
            }
        }.runTaskTimer(plugin, checkIntervalTicks, checkIntervalTicks);
    }

    /** 비를 맞고 있는 플레이어들에게 시듦 효과를 걸거나 갱신한다. */
    private void applyAcidRain(World world, int amplifier, int effectDurationTicks) {
        for (Player player : world.getPlayers()) {
            if (isExposedToRain(world, player)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, effectDurationTicks, amplifier, true, true));
            }
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
}
