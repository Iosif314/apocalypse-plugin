package net.apocalypse.plugin.listener;

import net.apocalypse.plugin.disaster.impl.DimensionalAnnihilationDisaster;
import net.apocalypse.plugin.util.ColorUtil;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

/**
 * 차원 소멸로 지워진 청크는 영구적인 공허로 남아, 그 안에는 블록을 새로 놓을 수 없다.
 * (엔티티 처치와 접근 경고는 DimensionalAnnihilationDisaster의 감시 작업이 담당한다.)
 */
public class AnnihilationZoneListener implements Listener {

    private final Plugin plugin;

    public AnnihilationZoneListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (!DimensionalAnnihilationDisaster.isVoidChunk(block.getWorld(), block.getX() >> 4, block.getZ() >> 4)) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        String message = plugin.getConfig().getString(
                "disasters.dimensional-annihilation.void-zone-block-deny-message",
                "&8&l이곳엔 아무것도 존재할 수 없습니다 — 차원이 소멸한 자리입니다.");
        player.sendMessage(ColorUtil.parse(message));
    }
}
