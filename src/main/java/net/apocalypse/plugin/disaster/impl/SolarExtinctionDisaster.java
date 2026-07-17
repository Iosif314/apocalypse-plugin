package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import net.apocalypse.plugin.util.PlayerFilter;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.EnumSet;
import java.util.Set;

/**
 * 지속 시간 동안 태양이 사라진 것처럼 낮/밤 주기를 멈추고 강제로 밤으로 고정한다.
 * 하늘이 뚫린 곳(지붕이 없는 곳)에 있는 플레이어에게는 주기적으로 어둠(Darkness) 효과를 걸어 시야를 가린다.
 * 직접적인 데미지 소스는 없고, 시야 차단과 야간 자연 몹 스폰 증가만으로 은은한 불안감을 주는
 * 가장 약한(1단계) 재앙이다.
 */
public class SolarExtinctionDisaster implements Disaster {

    private static final long NIGHT_TIME = 18000L;
    private static final String STATE_ORIGINAL_TIME = "originalTime";

    @Override
    public String getId() {
        return "solar-extinction";
    }

    @Override
    public String getDisplayName() {
        return "일시적 태양 소멸";
    }

    @Override
    public DangerLevel getDangerLevel() {
        return DangerLevel.LEVEL_1;
    }

    /** 낮/밤 주기와 시간을 조작하는 재앙인데, 네더/엔드는 낮/밤 주기 자체가 없어 효과가 체감되지 않는다. */
    @Override
    public Set<World.Environment> getSupportedEnvironments() {
        return EnumSet.of(World.Environment.NORMAL);
    }

    @Override
    public void trigger(DisasterContext context) {
        Plugin plugin = context.plugin();
        World world = context.world();
        ConfigurationSection section = context.config();

        long durationTicks = section.getLong("duration-seconds", 90) * 20L;
        long darknessIntervalTicks = Math.max(20, section.getLong("darkness-interval-ticks", 60));
        int darknessDurationTicks = (int) (Math.max(1, section.getLong("darkness-duration-seconds", 5)) * 20L);

        // 태양이 돌아왔을 때 원래 흐름대로 이어지도록, 소멸시키기 전의 시간을 이 트리거 전용 state에 기억해둔다.
        // 인스턴스 필드(월드별 Map)에 담으면 같은 월드에서 두 번 이상 겹쳐 발동할 때 나중 트리거가 이미
        // 밤으로 바뀐 시간을 "원래 시간"으로 덮어써버려, 복구해도 낮이 돌아오지 않게 된다.
        context.putState(STATE_ORIGINAL_TIME, world.getTime());

        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setTime(NIGHT_TIME);

        context.track(new BukkitRunnable() {
            long elapsed = 0;

            @Override
            public void run() {
                elapsed += darknessIntervalTicks;
                applyDarknessToExposedPlayers(world, darknessDurationTicks);

                if (elapsed >= durationTicks) {
                    cancel();
                    restoreSun(context);
                }
            }
        }.runTaskTimer(plugin, darknessIntervalTicks, darknessIntervalTicks));
    }

    /** /apoc stop으로 강제 중단됐을 때, 자기 자신의 마지막 틱에서만 되돌리던 낮/밤 주기와 시간을 즉시 복구한다. */
    @Override
    public void onStop(DisasterContext context) {
        restoreSun(context);
    }

    private void restoreSun(DisasterContext context) {
        context.world().setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
        Long originalTime = context.getState(STATE_ORIGINAL_TIME);
        if (originalTime != null) {
            context.world().setTime(originalTime);
        }
    }

    /** 하늘이 뚫린 곳에 있는 플레이어들에게 어둠 효과를 걸거나 갱신한다. */
    private void applyDarknessToExposedPlayers(World world, int durationTicks) {
        for (Player player : world.getPlayers()) {
            if (PlayerFilter.isTargetable(player) && isExposedToSky(world, player)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, durationTicks, 0, true, false));
            }
        }
    }

    /** 플레이어 머리 위에 하늘을 가리는 블록이 없는지 확인한다. */
    private boolean isExposedToSky(World world, Player player) {
        Location loc = player.getLocation();
        int highestY = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        return loc.getY() > highestY;
    }
}
