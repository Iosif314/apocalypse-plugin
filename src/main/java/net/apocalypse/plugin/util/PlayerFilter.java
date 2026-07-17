package net.apocalypse.plugin.util;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 크리에이티브/관전 모드 플레이어는 어떤 재앙의 대상(타겟 선정, 데미지, 위장, 파티클 등)도 되지 않는다. */
public final class PlayerFilter {

    private PlayerFilter() {
    }

    public static boolean isTargetable(Player player) {
        GameMode mode = player.getGameMode();
        return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR;
    }

    /** 크리에이티브/관전 모드를 제외한 플레이어만 담은 새 리스트를 반환한다. */
    public static List<Player> targetable(Collection<Player> players) {
        List<Player> result = new ArrayList<>();
        for (Player player : players) {
            if (isTargetable(player)) {
                result.add(player);
            }
        }
        return result;
    }
}
