package net.apocalypse.plugin.disaster;

/**
 * 새로운 재앙을 추가하려면 이 인터페이스를 구현하고
 * DisasterManager#register 로 등록한 뒤 config.yml의 disasters 섹션에
 * 같은 id로 설정 블록을 추가하면 됩니다.
 */
public interface Disaster {

    /** config.yml의 disasters 섹션 키와 일치해야 하는 고유 id */
    String getId();

    /** 명령어 안내 등에 표시되는 사람이 읽기 좋은 이름 */
    String getDisplayName();

    /** 이 재앙의 위험도. 아포칼립스 룰렛 등에서 색상/사운드를 결정하는 데 쓰인다. */
    DangerLevel getDangerLevel();

    /** 실제 재앙 효과를 실행합니다. */
    void trigger(DisasterContext context);

    /**
     * /apoc stop 등으로 이 재앙이 진행 중에 강제로 멈춰질 때 호출됩니다. 예약된 작업(BukkitTask)들은
     * DisasterManager가 이미 전부 취소한 뒤이므로, 여기서는 날씨/게임규칙/위장처럼 재앙 시작 시 바꿔놓고
     * 자기 자신의 마지막 틱에서만 되돌리던 "영구적인" 상태를 즉시 원래대로 복구하는 역할만 하면 됩니다.
     * 예약된 작업 취소만으로 충분히 안전하게 끝나는 재앙(순간적인 파괴 등)은 굳이 구현하지 않아도 됩니다.
     */
    default void onStop(DisasterContext context) {
    }
}
