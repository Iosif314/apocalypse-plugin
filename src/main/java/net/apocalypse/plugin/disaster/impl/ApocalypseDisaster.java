package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import net.apocalypse.plugin.disaster.DisasterManager;
import net.apocalypse.plugin.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 여러 재앙을 한 번에 몰아서 터뜨리는 초대형 이벤트.
 * "종말은 영어로" 글자 공개 -> "아포칼립스" 타이틀 -> 연속 발생 횟수 룰렛 -> 재앙 룰렛을 그 횟수만큼 반복하며
 * 매번 실제로 다른 재앙을 하나씩 골라 발동시킨다.
 */
public class ApocalypseDisaster implements Disaster {

    private static final String REVEAL_TEXT = "종말은 영어로";

    private final DisasterManager disasterManager;
    private final Random random = new Random();

    public ApocalypseDisaster(DisasterManager disasterManager) {
        this.disasterManager = disasterManager;
    }

    @Override
    public String getId() {
        return "apocalypse";
    }

    @Override
    public String getDisplayName() {
        return "아포칼립스";
    }

    @Override
    public DangerLevel getDangerLevel() {
        return DangerLevel.LEVEL_6;
    }

    @Override
    public void trigger(DisasterContext context) {
        Plugin plugin = context.plugin();
        World world = context.world();
        ConfigurationSection section = context.config();

        long letterFrameTicks = section.getLong("letter-frame-ticks", 4);
        long suspenseFrameTicks = section.getLong("suspense-frame-ticks", 20);
        long titleHoldTicks = section.getLong("title-hold-ticks", 30);
        long rouletteSpinTicks = section.getLong("roulette-spin-ticks", 3);
        int rouletteSpinCount = section.getInt("roulette-spin-count", 12);
        long chainAnnounceHoldTicks = section.getLong("chain-announce-hold-ticks", 70);
        long disasterLandHoldTicks = section.getLong("disaster-land-hold-ticks", 25);
        long chainStepGapTicks = section.getLong("chain-step-gap-ticks", 20);
        long disasterSpinTicks = section.getLong("disaster-spin-ticks", 3);
        int disasterSpinCount = section.getInt("disaster-spin-count", 10);
        int minChain = Math.max(1, section.getInt("chain-count.min", 2));
        int maxChain = Math.max(minChain, section.getInt("chain-count.max", 5));

        List<Disaster> chainPool = getEligibleChainDisasters(plugin, world);

        long offset = 0;

        // 1) "종말은 영어로" 한 글자씩 공개. 각 프레임은 다음 프레임이 뜨기 직전까지 화면에 남아있어 빈 화면(깜빡임) 없이 이어진다.
        String[] chars = REVEAL_TEXT.split("");
        for (int i = 1; i <= chars.length; i++) {
            String revealed = String.join(" ", Arrays.copyOfRange(chars, 0, i));
            Component titleComponent = Component.text(revealed, NamedTextColor.RED);
            offset += letterFrameTicks;
            // 마지막 글자는 다음 단계(말줄임표)가 더 느린 템포로 뜨므로, 그때까지 화면에 남아있도록 유지 시간을 늘린다.
            long stay = (i == chars.length) ? suspenseFrameTicks + 2 : letterFrameTicks + 2;
            schedule(context, offset, () -> {
                showTitle(world, titleComponent, Component.empty(), stay);
                playRouletteTick(world);
            });
        }
        // 글자가 다 공개된 뒤, 말줄임표(.)도 깜빡임 없이 한 개씩 차례로 붙여서 보여준다.
        String fullyRevealed = String.join(" ", chars);
        for (int dotCount = 1; dotCount <= 3; dotCount++) {
            String dots = String.join(" ", Collections.nCopies(dotCount, "."));
            Component dotsTitle = Component.text(fullyRevealed + " " + dots, NamedTextColor.RED);
            offset += suspenseFrameTicks;
            // 마지막 점은 "아포칼립스" 타이틀이 뜨기 직전까지 화면에 남아있도록 유지 시간을 늘린다.
            long stay = (dotCount == 3) ? titleHoldTicks + 2 : suspenseFrameTicks + 2;
            schedule(context, offset, () -> {
                showTitle(world, dotsTitle, Component.empty(), stay);
                playRouletteTick(world);
            });
        }

        // 2) "아포칼립스" 타이틀로 전환. 숫자 룰렛이 돌기 시작하기 직전까지 화면에 남아있도록 유지 시간을 이어붙인다.
        Component apocalypseTitle = Component.text(getDisplayName(), NamedTextColor.DARK_RED);
        offset += titleHoldTicks;
        schedule(context, offset, () -> {
            showTitle(world, apocalypseTitle, Component.empty(), rouletteSpinTicks + 2);
            playApocalypseRoar(world);
        });

        if (chainPool.isEmpty()) {
            // 연쇄로 발동시킬 다른 재앙이 없으면 여기서 마무리한다.
            return;
        }

        // 3) 연속 발생 횟수 룰렛. 숫자가 계속 바뀌며 돌아가되(빈 화면 없이 바로바로 이어짐) 마지막에 실제 값에 멈춘다.
        int chainCount = minChain + (maxChain > minChain ? random.nextInt(maxChain - minChain + 1) : 0);
        for (int i = 0; i < rouletteSpinCount; i++) {
            int spinValue = minChain + random.nextInt(maxChain - minChain + 1);
            Component subtitle = Component.text(String.valueOf(spinValue), NamedTextColor.RED);
            offset += rouletteSpinTicks;
            schedule(context, offset, () -> {
                showTitle(world, apocalypseTitle, subtitle, rouletteSpinTicks + 2);
                playRouletteTick(world);
            });
        }
        Component landedNumber = Component.text(String.valueOf(chainCount), NamedTextColor.RED);
        offset += rouletteSpinTicks;
        // 룰렛이 멈춘 뒤(실제 값이 뜬 채로) "연속 N회 재앙 발생" 문구가 뜨기 직전까지 화면에 남아있는다.
        schedule(context, offset, () -> {
            showTitle(world, apocalypseTitle, landedNumber, chainAnnounceHoldTicks + 2);
            playRouletteTick(world);
        });

        // 4) "연속 N회 재앙 발생"
        Component chainAnnounce = Component.text("연속 " + chainCount + "회 재앙 발생", NamedTextColor.RED);
        Component chainChatMessage = ColorUtil.parse("&4&l\"아포칼립스\" 재앙 발생! 재앙이 " + chainCount + "회 발생합니다!");
        offset += chainAnnounceHoldTicks;
        // "연속 N회 재앙 발생" 문구는 첫 재앙 이름이 뜨기 직전까지 화면에 남아있는다.
        schedule(context, offset, () -> {
            showTitle(world, apocalypseTitle, chainAnnounce, chainAnnounceHoldTicks + 2);
            broadcastToWorld(world, chainChatMessage);
        });
        // "연속 N회 재앙 발생" 문구가 chainAnnounceHoldTicks만큼 다 유지된 뒤에야 재앙 룰렛을 시작한다.
        offset += chainAnnounceHoldTicks;

        // 5) 재앙 룰렛을 chainCount번 반복. 매 단계마다 재앙 이름이 빙글빙글 돌다가(빈 화면 없이 바로바로 이어짐) 실제로 발동할 재앙에 멈춘다.
        for (int step = 0; step < chainCount; step++) {
            for (int i = 0; i < disasterSpinCount; i++) {
                Disaster spinDisaster = chainPool.get(random.nextInt(chainPool.size()));
                Component subtitle = Component.text(spinDisaster.getDisplayName(), spinDisaster.getDangerLevel().getColor());
                // 이 단계의 첫 프레임은 이전 프레임(공지 문구 또는 이전 단계의 재앙 이름)이 이미 지금 이 시점까지 유지되도록 잡혀있으므로 추가로 기다리지 않는다.
                if (i > 0) {
                    offset += disasterSpinTicks;
                }
                schedule(context, offset, () -> {
                    showTitle(world, apocalypseTitle, subtitle, disasterSpinTicks + 2);
                    playRouletteTick(world);
                });
            }

            // 지금 재앙이 발동한 차원(world)에서 무력화되는 재앙(폭풍우 등)이라도, 플레이어가 있는
            // 다른 지원 차원(예: 오버월드)이 있으면 거기서 대신 실행할 수 있도록 폭넓게 후보를 고른다.
            Disaster chosen = disasterManager.pickWeightedDisaster(getId(), DangerLevel.LEVEL_6,
                    (Disaster d) -> disasterManager.findWorldFor(d, world) != null);
            if (chosen == null) {
                break;
            }
            // 실제로 이 재앙을 실행할 차원. world 자신이 지원 안 하면(그리고 플레이어도 없으면) 다른 차원으로 넘어간다.
            World executeWorld = disasterManager.findWorldFor(chosen, world);
            if (executeWorld == null) {
                break;
            }
            Component landed = Component.text(chosen.getDisplayName(), chosen.getDangerLevel().getColor());
            Component landedChatMessage = ColorUtil.parse("&4&l" + chosen.getDisplayName() + " 재앙이 발생합니다!");
            // 마지막 단계가 아니라면 다음 재앙 이름이 뜨기 직전까지, 마지막 단계라면 자기 자신의 유지 시간만큼만 화면에 남아있는다.
            boolean hasNextStep = step < chainCount - 1;
            long stay = hasNextStep ? chainStepGapTicks + 2 : disasterLandHoldTicks;
            offset += disasterSpinTicks;
            schedule(context, offset, () -> {
                // 룰렛 연출 자체는 아포칼립스가 실제로 발동한 차원(world)에서 계속 보여준다.
                showTitle(world, apocalypseTitle, landed, stay);
                playDangerSound(world, chosen.getDangerLevel());
                broadcastToWorld(world, landedChatMessage);
                // 이 재앙을 다른 차원(executeWorld)에서 실행하는 경우, 그 차원 플레이어들에게도 별도로 알린다.
                if (executeWorld != world) {
                    broadcastToWorld(executeWorld, landedChatMessage);
                }
                // 아포칼립스 자체의 경고를 이미 띄웠으니, 연쇄로 터지는 각 재앙은 자기 자신의 "곧 온다" 경고 없이 바로 시작한다.
                disasterManager.triggerDisaster(chosen.getId(), executeWorld, false);
            });

            offset += chainStepGapTicks;
        }
    }

    /**
     * 연쇄 대상이 될 수 있는(자기 자신 제외, 6단계 제외, 활성화됨, 가중치 0 초과) 재앙 목록.
     * 지금 재앙이 발동한 차원(world) 자신이 지원 안 해도, 플레이어가 있는 다른 지원 차원이 있으면 후보에 남는다
     * (실제로 그 재앙을 어느 차원에서 실행할지는 {@link DisasterManager#findWorldFor}가 매 단계마다 다시 정한다).
     */
    private List<Disaster> getEligibleChainDisasters(Plugin plugin, World world) {
        List<Disaster> pool = new ArrayList<>();
        for (Disaster disaster : disasterManager.getDisasters().values()) {
            if (disaster.getId().equals(getId())) {
                continue;
            }
            // "차원 소멸" 같은 6단계 초희귀 재앙은 아포칼립스 연쇄로도 나오지 않게 제외한다.
            if (disaster.getDangerLevel() == DangerLevel.LEVEL_6) {
                continue;
            }
            // 이 재앙을 실행할 수 있는 차원이 하나도 없으면(플레이어가 있는 지원 차원이 전혀 없으면) 후보에서 뺀다.
            if (disasterManager.findWorldFor(disaster, world) == null) {
                continue;
            }
            if (!disasterManager.isDisasterEnabled(disaster.getId())) {
                continue;
            }
            String base = "disasters." + disaster.getId() + ".";
            if (Math.max(0, plugin.getConfig().getInt(base + "weight", 1)) == 0) {
                continue;
            }
            pool.add(disaster);
        }
        return pool;
    }

    private void schedule(DisasterContext context, long delayTicks, Runnable action) {
        context.track(Bukkit.getScheduler().runTaskLater(context.plugin(), action, delayTicks));
    }

    /** 재앙이 실제로 발생 중인 월드의 플레이어에게만 메시지를 보낸다. 다른 월드 플레이어는 받지 않는다. */
    private void broadcastToWorld(World world, Component message) {
        for (Player player : world.getPlayers()) {
            player.sendMessage(message);
        }
    }

    private void showTitle(World world, Component title, Component subtitle, long stayTicks) {
        Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(stayTicks * 50L), Duration.ZERO);
        Title titleObj = Title.title(title, subtitle, times);
        for (Player player : world.getPlayers()) {
            player.showTitle(titleObj);
        }
    }

    private void playDangerSound(World world, DangerLevel level) {
        for (Player player : world.getPlayers()) {
            player.playSound(player.getLocation(), level.getSound(), level.getVolume(), level.getPitch());
        }
    }

    /** 룰렛이 깜빡일 때, 그리고 글자가 한 자씩 공개될 때마다 나는 틱 소리. */
    private void playRouletteTick(World world) {
        for (Player player : world.getPlayers()) {
            player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_DISPENSE, 1.0f, 1.0f);
        }
    }

    /** "아포칼립스" 타이틀이 뜨는 순간 나는 포효 소리. */
    private void playApocalypseRoar(World world) {
        for (Player player : world.getPlayers()) {
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);
        }
    }
}
