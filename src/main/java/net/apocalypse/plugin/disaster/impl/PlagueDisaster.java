package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import net.apocalypse.plugin.util.ColorUtil;
import net.apocalypse.plugin.util.PlayerFilter;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 좀비 창궐과 달리 몹 소환 없이, 처음 감염된 몇 명으로부터 시작해 가까이 있는 다른 플레이어에게
 * 전염되며 퍼져나가는 상태이상 확산형 재앙. 감염된 플레이어는 주기적으로 구속(느려짐)/쇠약/중독
 * 증세를 겪고, 감염자 근처에 있으면 확률적으로 옮는다. 사람이 사람에게 옮긴다는 점에서
 * "위협이 곧 다른 플레이어"가 되는 재앙이다.
 */
public class PlagueDisaster implements Disaster {

    /** 현재 감염 중인 플레이어 UUID 집합. */
    public static final Set<UUID> INFECTED_PLAYERS = ConcurrentHashMap.newKeySet();

    private final Random random = new Random();

    @Override
    public String getId() {
        return "plague";
    }

    @Override
    public String getDisplayName() {
        return "역병";
    }

    @Override
    public DangerLevel getDangerLevel() {
        return DangerLevel.LEVEL_2;
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

        long durationTicks = section.getLong("duration-seconds", 180) * 20L;
        long checkIntervalTicks = Math.max(20, section.getLong("infection-check-interval-ticks", 40));
        int minInitial = Math.max(1, section.getInt("initial-infected-count.min", 1));
        int maxInitial = Math.max(minInitial, section.getInt("initial-infected-count.max", 1));
        double contagionRadius = Math.max(0, section.getDouble("contagion-radius", 6));
        int infectionChancePercent = section.getInt("infection-chance-percent", 40);
        int symptomDurationTicks = (int) (Math.max(1, section.getLong("symptom-duration-seconds", 6)) * 20L);
        int slownessAmplifier = Math.max(0, section.getInt("slowness-amplifier", 1));
        int weaknessAmplifier = Math.max(0, section.getInt("weakness-amplifier", 1));
        int poisonAmplifier = Math.max(0, section.getInt("poison-amplifier", 0));
        int poisonDurationTicks = (int) (Math.max(0, section.getLong("poison-duration-seconds", 3)) * 20L);
        String infectedMessage = section.getString("infected-message", "&2&l기침이 심해집니다... 역병에 감염되었습니다.");

        List<Player> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled, random);
        int initialCount = Math.min(shuffled.size(),
                minInitial + (maxInitial > minInitial ? random.nextInt(maxInitial - minInitial + 1) : 0));

        for (int i = 0; i < initialCount; i++) {
            infectPlayer(shuffled.get(i), infectedMessage);
        }

        new BukkitRunnable() {
            long elapsed = 0;

            @Override
            public void run() {
                elapsed += checkIntervalTicks;

                for (UUID id : new ArrayList<>(INFECTED_PLAYERS)) {
                    Player carrier = Bukkit.getPlayer(id);
                    if (carrier == null || !carrier.isOnline() || !PlayerFilter.isTargetable(carrier)) {
                        continue;
                    }
                    applySymptoms(carrier, symptomDurationTicks, slownessAmplifier, weaknessAmplifier,
                            poisonDurationTicks, poisonAmplifier);
                    spreadToNearbyPlayers(world, carrier, contagionRadius, infectionChancePercent, infectedMessage);
                }

                if (elapsed >= durationTicks) {
                    cancel();
                    INFECTED_PLAYERS.clear();
                }
            }
        }.runTaskTimer(plugin, checkIntervalTicks, checkIntervalTicks);
    }

    /** 플레이어를 감염 목록에 추가하고 감염 메시지를 보낸다. */
    private void infectPlayer(Player player, String infectedMessage) {
        INFECTED_PLAYERS.add(player.getUniqueId());
        player.sendMessage(ColorUtil.parse(infectedMessage));
    }

    /** 감염된 플레이어에게 구속(느려짐)/쇠약/중독 증세를 걸거나 갱신한다. */
    private void applySymptoms(Player carrier, int symptomDurationTicks, int slownessAmplifier,
                                int weaknessAmplifier, int poisonDurationTicks, int poisonAmplifier) {
        carrier.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, symptomDurationTicks, slownessAmplifier, true, true));
        carrier.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, symptomDurationTicks, weaknessAmplifier, true, true));
        if (poisonDurationTicks > 0) {
            carrier.addPotionEffect(new PotionEffect(PotionEffectType.POISON, poisonDurationTicks, poisonAmplifier, true, true));
        }
    }

    /** 감염자 주변 반경 안의 아직 감염되지 않은 플레이어에게 확률적으로 전염시킨다. */
    private void spreadToNearbyPlayers(World world, Player carrier, double radius, int chancePercent, String infectedMessage) {
        for (Player nearby : world.getPlayers()) {
            if (!PlayerFilter.isTargetable(nearby)) {
                continue;
            }
            if (INFECTED_PLAYERS.contains(nearby.getUniqueId())) {
                continue;
            }
            if (nearby.getLocation().distance(carrier.getLocation()) > radius) {
                continue;
            }
            if (random.nextInt(100) >= chancePercent) {
                continue;
            }
            infectPlayer(nearby, infectedMessage);
            nearby.playSound(nearby.getLocation(), Sound.ENTITY_VILLAGER_HURT, 1.0f, 0.7f);
        }
    }
}
