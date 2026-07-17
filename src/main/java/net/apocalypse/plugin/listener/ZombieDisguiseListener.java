package net.apocalypse.plugin.listener;

import net.apocalypse.plugin.disaster.impl.ZombieOutbreakDisaster;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 좀비 위장 퍼펫은 절대 피해를 입지 않으며(위장이 죽어서 들통나지 않도록), 대신 그 피해를
 * PersistentDataContainer에 기록된 실제 위장 중인 플레이어에게 그대로 전가한다.
 * 위장 중인 플레이어는 점프가 막혀 있는데, 벽(블록 한 칸 턱)에 막혀 오도가도 못 하는 경우
 * 감지해서 강제로 점프시켜준다.
 */
public class ZombieDisguiseListener implements Listener {

    /** 이 값보다 수평 이동량이 작으면 "벽에 막혀 멈췄다"고 판단한다. */
    private static final double STUCK_THRESHOLD = 0.02;
    /** 바닐라 점프 한 번의 수직 속도(대략). */
    private static final double JUMP_VELOCITY = 0.42;

    private final NamespacedKey key;

    public ZombieDisguiseListener(Plugin plugin) {
        this.key = new NamespacedKey(plugin, ZombieOutbreakDisaster.KEY_NAME);
    }

    @EventHandler
    public void onPuppetDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) {
            return;
        }
        PersistentDataContainer data = zombie.getPersistentDataContainer();
        String ownerId = data.get(key, PersistentDataType.STRING);
        if (ownerId == null) {
            return;
        }

        double damage = event.getFinalDamage();
        event.setCancelled(true);

        Player disguised = Bukkit.getPlayer(UUID.fromString(ownerId));
        if (disguised != null && damage > 0) {
            disguised.damage(damage);
        }
    }

    /** 위장 중(점프가 막힌) 플레이어가 벽에 막혀 거의 못 움직이면, 앞을 확인해서 한 칸 턱이면 강제로 점프시킨다. */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!ZombieOutbreakDisaster.DISGUISED_PLAYERS.contains(player.getUniqueId())) {
            return;
        }
        Location to = event.getTo();
        if (to == null || !player.isOnGround() || player.isSneaking()) {
            return;
        }

        Location from = event.getFrom();
        double horizontalMoved = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        if (horizontalMoved > STUCK_THRESHOLD) {
            return;
        }

        Vector facing = player.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 1.0E-4) {
            return;
        }
        facing.normalize();

        Location ahead = player.getLocation().add(facing.multiply(0.6));
        if (!ahead.getBlock().getType().isSolid()) {
            return;
        }
        if (ahead.clone().add(0, 1, 0).getBlock().getType().isSolid()) {
            // 두 칸 이상 막힌 벽이면 뛰어도 못 넘어가므로 그냥 둔다.
            return;
        }

        Vector velocity = player.getVelocity();
        player.setVelocity(new Vector(velocity.getX(), JUMP_VELOCITY, velocity.getZ()));
    }
}
