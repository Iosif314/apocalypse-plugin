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
}
