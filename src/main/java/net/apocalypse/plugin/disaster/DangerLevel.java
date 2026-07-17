package net.apocalypse.plugin.disaster;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Sound;

/**
 * 재앙의 위험도. 1단계(초록, 가장 안전)부터 6단계(검정, 가장 위험)까지
 * 초록 -> 노랑 -> 주황 -> 빨강 -> 어두운 빨강 -> 검정 순으로 이어지며,
 * 각 재앙의 위험도는 개발자가 재앙의 실제 파괴력/치명도를 보고 판단해 부여한다.
 * 위험도가 높을수록 자동 발생 쿨타임 기본값이 길어지고, 쿨타임이 돌 때 실제로 발동할 기본 확률은 낮아진다.
 * 이 기본값들은 각 재앙의 config 섹션에서 min-interval-seconds/max-interval-seconds/auto-trigger-chance로 얼마든지 덮어쓸 수 있다.
 * 6단계는 "차원 소멸"처럼 극히 희귀하고 회피 불가능한 최상위 재앙 전용으로, 쿨타임이 시간 단위로 매우 길고 확률도 희박하다.
 */
public enum DangerLevel {
    LEVEL_1(Sound.ENTITY_PHANTOM_AMBIENT, 1.0f, 1.3f, 300, 600, 100, NamedTextColor.GREEN),
    LEVEL_2(Sound.ENTITY_RAVAGER_ROAR, 1.0f, 1.1f, 600, 1200, 84, NamedTextColor.YELLOW),
    LEVEL_3(Sound.ENTITY_WITHER_HURT, 1.0f, 1.0f, 720, 1320, 75, NamedTextColor.GOLD),
    LEVEL_4(Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.85f, 840, 1440, 60, NamedTextColor.RED),
    LEVEL_5(Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.7f, 960, 1560, 48, NamedTextColor.DARK_RED),
    LEVEL_6(Sound.ENTITY_WARDEN_ROAR, 1.0f, 0.6f, 7200, 14400, 8, NamedTextColor.BLACK);

    private final Sound sound;
    private final float volume;
    private final float pitch;
    private final int defaultMinIntervalSeconds;
    private final int defaultMaxIntervalSeconds;
    private final int defaultTriggerChancePercent;
    private final TextColor color;

    DangerLevel(Sound sound, float volume, float pitch,
                int defaultMinIntervalSeconds, int defaultMaxIntervalSeconds, int defaultTriggerChancePercent,
                TextColor color) {
        this.sound = sound;
        this.volume = volume;
        this.pitch = pitch;
        this.defaultMinIntervalSeconds = defaultMinIntervalSeconds;
        this.defaultMaxIntervalSeconds = defaultMaxIntervalSeconds;
        this.defaultTriggerChancePercent = defaultTriggerChancePercent;
        this.color = color;
    }

    public Sound getSound() {
        return sound;
    }

    public float getVolume() {
        return volume;
    }

    public float getPitch() {
        return pitch;
    }

    /** 이 위험도의 기본 자동 발생 쿨타임 최소값(초). config에 min-interval-seconds가 없을 때 쓰인다. */
    public int getDefaultMinIntervalSeconds() {
        return defaultMinIntervalSeconds;
    }

    /** 이 위험도의 기본 자동 발생 쿨타임 최대값(초). config에 max-interval-seconds가 없을 때 쓰인다. */
    public int getDefaultMaxIntervalSeconds() {
        return defaultMaxIntervalSeconds;
    }

    /** 쿨타임이 돌 때 실제로 발동할 기본 확률(%). config에 auto-trigger-chance가 없을 때 쓰인다. */
    public int getDefaultTriggerChancePercent() {
        return defaultTriggerChancePercent;
    }

    /** 이 위험도의 색상 (초록 -> 노랑 -> 주황 -> 빨강 -> 어두운 빨강 -> 검정). */
    public TextColor getColor() {
        return color;
    }
}
