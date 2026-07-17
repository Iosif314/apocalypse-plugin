package net.apocalypse.plugin.listener;

import net.apocalypse.plugin.disaster.impl.ZombieOutbreakDisaster;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * 좀비 위장 도중 접속을 끊었다가, 재앙이 끝난 뒤 다시 접속하는 경우 정리 루틴이 오프라인
 * 플레이어를 건드릴 수 없어 점프력 감소 수정자가 그대로 남아있을 수 있다. 접속할 때마다
 * 무조건 한 번씩 지워서 이런 경우에도 원래 상태로 돌아오게 하는 안전장치.
 */
public class DisasterCleanupListener implements Listener {

    private final Plugin plugin;

    public DisasterCleanupListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        AttributeInstance jumpAttribute = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jumpAttribute != null) {
            jumpAttribute.removeModifier(new NamespacedKey(plugin, ZombieOutbreakDisaster.JUMP_MODIFIER_KEY_NAME));
        }
    }
}
