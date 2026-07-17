package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import net.apocalypse.plugin.util.PlayerFilter;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 발동 시점에 플레이어마다 한 번에 좀비 무리를 소환하고, 이후 지속 시간 동안은
 * 플레이어 두 명 이상이 가까이 모이면 서로에게(그리고 다른 모든 플레이어에게) 좀비로 보이게 위장시킨다.
 * 위장은 진짜 디스가이즈 패킷 조작이 아니라, 실제 플레이어를 hidePlayer로 숨기고
 * 그 자리를 매 틱 따라다니는 진짜 좀비 엔티티(퍼펫)로 대신 보여주는 방식으로 구현한다.
 */
public class ZombieOutbreakDisaster implements Disaster {

    /** 퍼펫 좀비의 PersistentDataContainer에 위장 중인 플레이어의 UUID를 저장할 때 쓰는 키 이름. */
    public static final String KEY_NAME = "zombie-puppet-owner";

    /** 위장 중 점프력을 낮추는 JUMP_STRENGTH 속성 수정자의 키 이름. */
    public static final String JUMP_MODIFIER_KEY_NAME = "zombie-disguise-jump-weaken";

    /**
     * 현재 위장 중(=점프가 막힌) 플레이어 UUID 집합. {@link net.apocalypse.plugin.listener.ZombieDisguiseListener}가
     * 이 목록을 보고, 벽에 막혀 못 움직이는 위장 플레이어를 감지해 강제로 점프시켜 준다.
     */
    public static final Set<UUID> DISGUISED_PLAYERS = ConcurrentHashMap.newKeySet();

    // /apoc stop으로 강제 중단됐을 때도 위장을 전부 풀 수 있도록, trigger()의 지역 변수가 아니라 필드로 둔다.
    private final Map<UUID, Zombie> disguisePuppets = new HashMap<>();

    private final Random random = new Random();

    @Override
    public String getId() {
        return "zombie-outbreak";
    }

    @Override
    public String getDisplayName() {
        return "좀비 창궐";
    }

    @Override
    public DangerLevel getDangerLevel() {
        // 뭉치지만 않으면 상대적으로 안전해서 한 단계 낮춤
        return DangerLevel.LEVEL_2;
    }

    @Override
    public void trigger(DisasterContext context) {
        Plugin plugin = context.plugin();
        World world = context.world();
        ConfigurationSection section = context.config();

        long durationTicks = section.getLong("duration-seconds", 120) * 20L;
        int minZombies = section.getInt("zombie-count.min", 5);
        int maxZombies = section.getInt("zombie-count.max", 10);
        double spawnRadius = Math.max(3, section.getDouble("spawn-radius", 12));
        double groupRadius = section.getDouble("group-radius", 8);
        long disguiseCheckTicks = Math.max(1, section.getLong("disguise-check-interval-ticks", 4));
        int slownessAmplifier = section.getInt("disguise-slowness-amplifier", 2);
        double jumpStrengthReduction = Math.max(0, section.getDouble("disguise-jump-strength-reduction", 0.5));

        NamespacedKey key = new NamespacedKey(plugin, KEY_NAME);
        NamespacedKey jumpModifierKey = new NamespacedKey(plugin, JUMP_MODIFIER_KEY_NAME);

        // 좀비는 주기적인 웨이브가 아니라, 발동 시점에 플레이어마다 한 번에 몰아서 소환한다.
        for (Player player : PlayerFilter.targetable(world.getPlayers())) {
            spawnZombieWave(world, player.getLocation(), minZombies, maxZombies, spawnRadius);
        }

        context.track(new BukkitRunnable() {
            long elapsed = 0;

            @Override
            public void run() {
                elapsed += disguiseCheckTicks;
                updateDisguises(plugin, world, key, groupRadius, disguisePuppets, disguiseCheckTicks,
                        slownessAmplifier, jumpModifierKey, jumpStrengthReduction);

                if (elapsed >= durationTicks) {
                    cancel();
                    clearAllDisguises(plugin, disguisePuppets, jumpModifierKey);
                }
            }
        }.runTaskTimer(plugin, disguiseCheckTicks, disguiseCheckTicks));
    }

    /** /apoc stop으로 강제 중단됐을 때, 자기 자신의 마지막 틱에서만 풀어주던 위장을 즉시 전부 해제한다. */
    @Override
    public void onStop(DisasterContext context) {
        Plugin plugin = context.plugin();
        NamespacedKey jumpModifierKey = new NamespacedKey(plugin, JUMP_MODIFIER_KEY_NAME);
        clearAllDisguises(plugin, disguisePuppets, jumpModifierKey);
    }

    private void spawnZombieWave(World world, Location center, int minZombies, int maxZombies, double radius) {
        int count = minZombies + (maxZombies > minZombies ? random.nextInt(maxZombies - minZombies + 1) : 0);
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double distance = 3 + random.nextDouble() * (radius - 3);
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z);
            world.spawnEntity(new Location(world, x + 0.5, y + 1, z + 0.5), EntityType.ZOMBIE);
        }
    }

    /** 이번 틱 기준으로 그룹(반경 안에 다른 플레이어가 있는지)을 다시 계산해서 위장 상태를 갱신한다. */
    private void updateDisguises(Plugin plugin, World world, NamespacedKey key, double groupRadius,
                                  Map<UUID, Zombie> disguisePuppets, long checkIntervalTicks, int slownessAmplifier,
                                  NamespacedKey jumpModifierKey, double jumpStrengthReduction) {
        List<Player> players = PlayerFilter.targetable(world.getPlayers());
        Set<UUID> stillPresent = new HashSet<>();
        // 효과가 다음 갱신 전에 끊기지 않도록 체크 주기보다 살짝 여유를 둔다.
        int effectDuration = (int) (checkIntervalTicks * 3);

        for (Player player : players) {
            stillPresent.add(player.getUniqueId());
            boolean grouped = isNearOtherPlayer(player, players, groupRadius);
            Zombie puppet = disguisePuppets.get(player.getUniqueId());

            if (grouped) {
                if (puppet == null || puppet.isDead()) {
                    disguisePuppets.put(player.getUniqueId(), spawnPuppet(plugin, world, player, key));
                    hideFromEveryone(plugin, player);
                } else {
                    puppet.teleport(player.getLocation());
                }
                applyZombieMovement(player, effectDuration, slownessAmplifier, jumpModifierKey, jumpStrengthReduction);
            } else if (puppet != null) {
                despawnPuppet(puppet);
                showToEveryone(plugin, player);
                clearZombieMovement(player, jumpModifierKey);
                disguisePuppets.remove(player.getUniqueId());
            }
        }

        // 퇴장했거나 다른 월드로 이동한 플레이어의 위장은 강제로 정리한다.
        disguisePuppets.entrySet().removeIf(entry -> {
            if (stillPresent.contains(entry.getKey())) {
                return false;
            }
            despawnPuppet(entry.getValue());
            Player offlineRef = plugin.getServer().getPlayer(entry.getKey());
            if (offlineRef != null) {
                showToEveryone(plugin, offlineRef);
                clearZombieMovement(offlineRef, jumpModifierKey);
            }
            return true;
        });
    }

    /**
     * 실제 좀비처럼 느릿느릿 걷게(달리기 금지, 점프 금지, 이동속도 감소) 만든다. 위장 중엔 매 갱신 주기마다 다시 걸어준다.
     * 점프 자체는 JUMP_STRENGTH 속성을 깎아 평소엔 막고, 벽에 막혀 오도가도 못 할 때는
     * {@link net.apocalypse.plugin.listener.ZombieDisguiseListener}가 {@link #DISGUISED_PLAYERS}를 보고 강제로 점프시켜 준다.
     */
    private void applyZombieMovement(Player player, int durationTicks, int slownessAmplifier,
                                      NamespacedKey jumpModifierKey, double jumpStrengthReduction) {
        player.setSprinting(false);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, slownessAmplifier, true, false));
        DISGUISED_PLAYERS.add(player.getUniqueId());

        AttributeInstance jumpAttribute = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jumpAttribute != null && jumpAttribute.getModifier(jumpModifierKey) == null) {
            jumpAttribute.addModifier(new AttributeModifier(jumpModifierKey, -jumpStrengthReduction, AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    private void clearZombieMovement(Player player, NamespacedKey jumpModifierKey) {
        player.removePotionEffect(PotionEffectType.SLOWNESS);
        DISGUISED_PLAYERS.remove(player.getUniqueId());
        AttributeInstance jumpAttribute = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jumpAttribute != null) {
            jumpAttribute.removeModifier(jumpModifierKey);
        }
    }

    private boolean isNearOtherPlayer(Player player, List<Player> players, double radius) {
        for (Player other : players) {
            if (other.equals(player)) {
                continue;
            }
            if (other.getLocation().distance(player.getLocation()) <= radius) {
                return true;
            }
        }
        return false;
    }

    private Zombie spawnPuppet(Plugin plugin, World world, Player player, NamespacedKey key) {
        Zombie zombie = world.spawn(player.getLocation(), Zombie.class, entity -> {
            entity.setAI(false);
            entity.setShouldBurnInDay(false);
            entity.setCustomNameVisible(false);
            entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, player.getUniqueId().toString());
        });
        player.hideEntity(plugin, zombie);
        return zombie;
    }

    private void despawnPuppet(Zombie puppet) {
        if (puppet != null && !puppet.isDead()) {
            puppet.remove();
        }
    }

    private void hideFromEveryone(Plugin plugin, Player player) {
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.equals(player)) {
                other.hidePlayer(plugin, player);
            }
        }
    }

    private void showToEveryone(Plugin plugin, Player player) {
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.equals(player)) {
                other.showPlayer(plugin, player);
            }
        }
    }

    private void clearAllDisguises(Plugin plugin, Map<UUID, Zombie> disguisePuppets, NamespacedKey jumpModifierKey) {
        for (Map.Entry<UUID, Zombie> entry : disguisePuppets.entrySet()) {
            despawnPuppet(entry.getValue());
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                showToEveryone(plugin, player);
                clearZombieMovement(player, jumpModifierKey);
            }
        }
        disguisePuppets.clear();
    }
}
