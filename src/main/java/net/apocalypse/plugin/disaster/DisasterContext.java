package net.apocalypse.plugin.disaster;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * 재앙 하나가 실행될 때 필요한 정보를 담아 전달하는 컨텍스트.
 * immediate가 true면 명령어나 아포칼립스 연쇄처럼 "바로 발동"된 경우다 — 폭풍우의 토네이도 생성 지연,
 * 차원 소멸의 소멸 지연처럼 재앙 내부에 있는 "조용히 기다리는" 지연 단계를 건너뛰어야 한다는 뜻으로 쓰인다.
 * (연출용 시간 흐름, 예: 운석의 낙하 비행이나 아포칼립스의 타이틀 연출은 이 값과 무관하게 그대로 유지된다.)
 * activeTasks는 이 재앙이 예약한 BukkitTask를 전부 등록해두는 목록으로, /apoc stop이 실행되면
 * DisasterManager가 여기 담긴 작업을 전부 취소한다 — 재앙 안에서 새로 예약하는 작업(runTaskTimer/runTaskLater)은
 * 반드시 track()으로 등록해야 정상적으로 멈출 수 있다.
 */
public record DisasterContext(
        Plugin plugin,
        World world,
        List<Player> players,
        ConfigurationSection config,
        boolean immediate,
        List<BukkitTask> activeTasks
) {
    /** 이 재앙이 예약한 작업을 등록해, /apoc stop으로 취소될 수 있게 한다. */
    public void track(BukkitTask task) {
        activeTasks.add(task);
    }
}
