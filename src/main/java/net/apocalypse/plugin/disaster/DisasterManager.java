package net.apocalypse.plugin.disaster;

import net.apocalypse.plugin.util.ColorUtil;
import net.apocalypse.plugin.util.PlayerFilter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 등록된 재앙들을 관리한다. 각 재앙은 자신의 config 섹션에 있는
 * min-interval-seconds ~ max-interval-seconds 범위로 독립적인 쿨타임을 돌며 자동 발생한다.
 * 새 재앙을 추가하려면 register()로 등록하고 config.yml에 같은 id로 섹션을 추가하면 된다.
 * config.yml은 플러그인이 켜질 때마다 JAR 기본값으로 덮어써지므로, 서버를 재시작해도 남아있어야 하는
 * 켜기/끄기 상태만 ToggleStore(별도의 toggles.yml)에 저장한다.
 */
public class DisasterManager {

    private final Plugin plugin;
    private final ToggleStore toggleStore;
    private final Random random = new Random();
    private final Map<String, Disaster> disasters = new LinkedHashMap<>();
    private final Map<String, BukkitTask> scheduledTasks = new HashMap<>();

    // /apoc stop이 취소할 수 있도록, 아직 발동 전인(경고 대기 중인) 예약과 이미 발동해서 진행 중인 재앙들을 추적한다.
    // 특정 재앙 id만 골라서 멈출 수 있어야 하므로, 어떤 Disaster에 속하는 작업인지도 같이 기억해둔다.
    private final List<PendingRun> pendingRuns = new ArrayList<>();
    private final List<ActiveRun> activeRuns = new ArrayList<>();

    private record PendingRun(Disaster disaster, BukkitTask task) {
    }

    private record ActiveRun(Disaster disaster, DisasterContext context) {
    }

    private boolean running = false;

    public DisasterManager(Plugin plugin, ToggleStore toggleStore) {
        this.plugin = plugin;
        this.toggleStore = toggleStore;
    }

    public void register(Disaster disaster) {
        disasters.put(disaster.getId(), disaster);
    }

    public Map<String, Disaster> getDisasters() {
        return disasters;
    }

    public boolean isRunning() {
        return running;
    }

    /** 등록된 모든 재앙의 자동 쿨타임 스케줄링을 시작한다. 재앙마다 자기 자신의 쿨타임으로 독립적으로 돈다. */
    public void start() {
        if (running) {
            return;
        }
        if (!toggleStore.isGlobalEnabled()) {
            return;
        }
        running = true;
        for (Disaster disaster : disasters.values()) {
            scheduleNext(disaster);
        }
    }

    /** 모든 재앙의 자동 쿨타임 스케줄링을 중지한다. */
    public void stop() {
        running = false;
        scheduledTasks.values().forEach(BukkitTask::cancel);
        scheduledTasks.clear();
    }

    /**
     * 전체 자동 발생 시스템을 켜고 끈다. toggles.yml에 저장되어 서버를 재시작해도 유지된다
     * (config.yml과 달리 이 파일은 플러그인이 켜질 때 덮어써지지 않는다).
     * 반환값은 토글 후의 상태(true=켜짐)이다.
     */
    public boolean toggleGlobal() {
        boolean newState = !toggleStore.isGlobalEnabled();
        toggleStore.setGlobalEnabled(newState);

        if (newState) {
            start();
        } else {
            stop();
        }
        return newState;
    }

    /**
     * 특정 재앙 하나의 자동 발생만 켜고 끈다. toggles.yml에 저장되어 서버를 재시작해도 유지된다.
     * 등록되지 않은 id면 null을 반환하고, 그 외에는 토글 후의 상태(true=켜짐)를 반환한다.
     */
    public Boolean toggleDisaster(String id) {
        Disaster disaster = disasters.get(id);
        if (disaster == null) {
            return null;
        }

        boolean newState = !toggleStore.isDisasterEnabled(id);
        toggleStore.setDisasterEnabled(id, newState);

        BukkitTask existing = scheduledTasks.remove(id);
        if (existing != null) {
            existing.cancel();
        }
        if (newState && running) {
            scheduleNext(disaster);
        }
        return newState;
    }

    /** 이 재앙의 자동 발생이 현재 켜져 있는지(toggles.yml 기준). */
    public boolean isDisasterEnabled(String id) {
        return toggleStore.isDisasterEnabled(id);
    }

    /**
     * 이 재앙의 쿨타임을 예약한다. 쿨타임 범위와 실제 발동 확률은 위험도(DangerLevel)의 기본값을 따르되,
     * config에 min-interval-seconds/max-interval-seconds/auto-trigger-chance가 있으면 그 값으로 덮어쓴다.
     * 쿨타임이 돌아도 확률에 걸리지 않으면 발동하지 않고 쿨타임만 다시 돈다.
     */
    private void scheduleNext(Disaster disaster) {
        if (!running) {
            return;
        }
        if (!toggleStore.isDisasterEnabled(disaster.getId())) {
            return;
        }
        String base = "disasters." + disaster.getId() + ".";
        DangerLevel dangerLevel = disaster.getDangerLevel();
        int min = plugin.getConfig().getInt(base + "min-interval-seconds", dangerLevel.getDefaultMinIntervalSeconds());
        int max = plugin.getConfig().getInt(base + "max-interval-seconds", dangerLevel.getDefaultMaxIntervalSeconds());
        int delaySeconds = min + (max > min ? random.nextInt(max - min + 1) : 0);

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            double chancePercent = plugin.getConfig().getDouble(base + "auto-trigger-chance", dangerLevel.getDefaultTriggerChancePercent());
            if (random.nextDouble() * 100.0 < chancePercent) {
                List<World> candidateWorlds = getConfiguredWorlds();
                // 날씨/낮밤 등 바닐라 시스템에 의존해 이 재앙이 사실상 무력화되는 차원(네더/엔드 등)은 후보에서 뺀다.
                candidateWorlds.removeIf(w -> !disaster.getSupportedEnvironments().contains(w.getEnvironment()));
                candidateWorlds.removeIf(w -> PlayerFilter.targetable(w.getPlayers()).isEmpty());
                if (!candidateWorlds.isEmpty()) {
                    World world = candidateWorlds.get(random.nextInt(candidateWorlds.size()));
                    executeDisaster(disaster, world, false, true);
                }
            }
            scheduleNext(disaster);
        }, delaySeconds * 20L);

        scheduledTasks.put(disaster.getId(), task);
    }

    /** 설정된 월드 중 플레이어가 있는 월드를 골라 가중치 기반으로 재앙 하나를 무작위로 발생시킨다 (경고 후 대기). */
    public boolean triggerRandomDisaster() {
        return triggerRandomDisaster(false);
    }

    /**
     * 설정된 월드 중 플레이어가 있는 월드를 골라 가중치 기반으로 재앙 하나를 무작위로 발생시킨다.
     * immediate가 true면 경고 메시지와 함께 대기 없이 바로 소환된다 (예: 명령어로 직접 발동).
     */
    public boolean triggerRandomDisaster(boolean immediate) {
        List<World> candidateWorlds = getConfiguredWorlds();
        candidateWorlds.removeIf(w -> PlayerFilter.targetable(w.getPlayers()).isEmpty());
        if (candidateWorlds.isEmpty()) {
            return false;
        }
        World world = candidateWorlds.get(random.nextInt(candidateWorlds.size()));

        // 이 월드(차원)에서 정상 작동하는 재앙만 후보로 삼는다 — 네더/엔드에서 폭풍우 같은 날씨 의존 재앙이 뽑히지 않도록.
        Disaster disaster = pickWeightedDisaster(null, null, world.getEnvironment());
        if (disaster == null) {
            return false;
        }
        executeDisaster(disaster, world, immediate, true);
        return true;
    }

    /** 명령어 등을 통해 특정 재앙을 특정 월드에 즉시 발생시킨다. 경고 메시지와 함께 대기 없이 바로 소환된다. */
    public boolean triggerDisaster(String id, World world) {
        return triggerDisaster(id, world, true);
    }

    /**
     * 특정 재앙을 특정 월드에 즉시 발생시킨다. 대기 없이 바로 소환되며,
     * showWarning이 false면 이 재앙 자신의 warning-message는 생략하고 appear-message(시작 알림)만 뜬다.
     * 아포칼립스처럼 이미 한 번 큰 경고를 띄운 뒤 시간차를 두고 여러 재앙을 잇달아 터뜨릴 때,
     * 매번 "곧 온다"는 식의 경고가 중복으로 뜨는 것을 막는 용도로 쓴다.
     */
    public boolean triggerDisaster(String id, World world, boolean showWarning) {
        Disaster disaster = disasters.get(id);
        if (disaster == null) {
            return false;
        }
        executeDisaster(disaster, world, true, showWarning);
        return true;
    }

    private void executeDisaster(Disaster disaster, World world, boolean immediate, boolean showWarning) {
        // 실제 config.yml에 이 재앙의 섹션이 없어도(토글 외엔 손댄 적 없어도) 빈 섹션을 임시로 만들어 쓴다.
        // createSection과 달리 plugin.getConfig()에 반영되지 않으므로 다음 saveConfig() 때 같이 저장되지 않는다.
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("disasters." + disaster.getId());
        if (section == null) {
            section = new MemoryConfiguration();
        }
        final ConfigurationSection finalSection = section;

        Component appearMessage = ColorUtil.parse(section.getString("appear-message", "&4&l재앙이 발생했습니다!"));

        if (showWarning) {
            String rawWarning = section.getString("warning-message", "&c&l재앙이 다가옵니다!");
            broadcast(world, ColorUtil.parse("&c&l[경고] " + rawWarning));
        }

        Runnable spawn = () -> {
            List<Player> players = PlayerFilter.targetable(world.getPlayers());
            if (players.isEmpty()) {
                return;
            }
            DisasterContext context = new DisasterContext(plugin, world, players, finalSection, immediate, new ArrayList<>(), new HashMap<>());
            pruneFinishedRuns();
            activeRuns.add(new ActiveRun(disaster, context));
            disaster.trigger(context);
            broadcast(world, appearMessage);
        };

        if (immediate) {
            spawn.run();
        } else {
            int warningSeconds = plugin.getConfig().getInt("settings.warning-seconds", 10);
            pendingRuns.removeIf(pending -> pending.task().isCancelled());
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, spawn, warningSeconds * 20L);
            pendingRuns.add(new PendingRun(disaster, task));
        }
    }

    /** 이미 모든 작업이 끝난(취소된) 지난 재앙 기록을 목록에서 정리한다. */
    private void pruneFinishedRuns() {
        activeRuns.removeIf(run -> run.context().activeTasks().stream().allMatch(BukkitTask::isCancelled));
    }

    /**
     * 경고 대기 중인 예약과 현재 진행 중인 재앙을 전부 강제로 멈춘다. 예약된 작업(BukkitTask)을 모두 취소하고,
     * 날씨/게임규칙/위장처럼 재앙이 스스로 끝날 때만 되돌리던 영구적인 상태는 각 재앙의 onStop()으로 즉시 복구시킨다.
     * 실제로 멈춘 개수(대기 중이던 예약 + 진행 중이던 재앙)를 반환한다.
     */
    public int stopCurrent() {
        int stoppedCount = 0;

        for (PendingRun pending : pendingRuns) {
            if (!pending.task().isCancelled()) {
                pending.task().cancel();
                stoppedCount++;
            }
        }
        pendingRuns.clear();

        for (ActiveRun run : activeRuns) {
            for (BukkitTask task : run.context().activeTasks()) {
                if (!task.isCancelled()) {
                    task.cancel();
                }
            }
            run.disaster().onStop(run.context());
            stoppedCount++;
        }
        activeRuns.clear();

        return stoppedCount;
    }

    /**
     * id가 일치하는 재앙만 골라서 멈춘다(경고 대기 중이었던 예약 + 이미 진행 중이던 재앙 둘 다 대상).
     * stopCurrent()와 동일하게 예약된 작업을 취소하고 onStop()으로 영구 상태를 복구한다.
     * 실제로 멈춘 개수를 반환한다(0이면 그 id로 대기/진행 중인 게 없었다는 뜻).
     */
    public int stopDisaster(String id) {
        int stoppedCount = 0;

        Iterator<PendingRun> pendingIterator = pendingRuns.iterator();
        while (pendingIterator.hasNext()) {
            PendingRun pending = pendingIterator.next();
            if (!pending.disaster().getId().equals(id)) {
                continue;
            }
            if (!pending.task().isCancelled()) {
                pending.task().cancel();
                stoppedCount++;
            }
            pendingIterator.remove();
        }

        Iterator<ActiveRun> activeIterator = activeRuns.iterator();
        while (activeIterator.hasNext()) {
            ActiveRun run = activeIterator.next();
            if (!run.disaster().getId().equals(id)) {
                continue;
            }
            for (BukkitTask task : run.context().activeTasks()) {
                if (!task.isCancelled()) {
                    task.cancel();
                }
            }
            run.disaster().onStop(run.context());
            stoppedCount++;
            activeIterator.remove();
        }

        return stoppedCount;
    }

    /** 지금 경고 대기 중이거나 진행 중인 재앙들의 id를 중복 없이 모아 반환한다. /apoc stop 탭완성에 쓰인다. */
    public Set<String> getStoppableDisasterIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (PendingRun pending : pendingRuns) {
            if (!pending.task().isCancelled()) {
                ids.add(pending.disaster().getId());
            }
        }
        for (ActiveRun run : activeRuns) {
            if (run.context().activeTasks().stream().anyMatch(task -> !task.isCancelled())) {
                ids.add(run.disaster().getId());
            }
        }
        return ids;
    }

    /**
     * 가중치 기반으로 재앙 하나를 무작위로 고른다. excludeId와 id가 같은 재앙은 후보에서 제외된다.
     * "아포칼립스"처럼 다른 재앙을 연쇄로 골라야 하는 재앙이 자기 자신을 다시 고르지 않도록 쓸 수 있다.
     */
    public Disaster pickWeightedDisaster(String excludeId) {
        return pickWeightedDisaster(excludeId, null, (Predicate<Disaster>) null);
    }

    /**
     * 가중치 기반으로 재앙 하나를 무작위로 고른다. excludeId와 id가 같은 재앙, excludeLevel과 위험도가 같은 재앙은
     * 후보에서 제외된다(둘 다 null이면 아무것도 제외하지 않음). 아포칼립스가 연쇄 재앙을 고를 때
     * 자기 자신뿐 아니라 "차원 소멸" 같은 6단계 초희귀 재앙까지 후보에서 빼는 용도로 쓸 수 있다.
     */
    public Disaster pickWeightedDisaster(String excludeId, DangerLevel excludeLevel) {
        return pickWeightedDisaster(excludeId, excludeLevel, (Predicate<Disaster>) null);
    }

    /**
     * 가중치 기반으로 재앙 하나를 무작위로 고른다. excludeId/excludeLevel은 위 오버로드와 같고,
     * environment가 주어지면 그 차원에서 {@link Disaster#getSupportedEnvironments()}가 지원하지 않는
     * 재앙도 후보에서 제외된다(null이면 차원 제한 없음). 네더/엔드에서 날씨 의존 재앙이 뽑히지 않게 하는 용도.
     */
    public Disaster pickWeightedDisaster(String excludeId, DangerLevel excludeLevel, World.Environment environment) {
        return pickWeightedDisaster(excludeId, excludeLevel,
                environment == null ? null : d -> d.getSupportedEnvironments().contains(environment));
    }

    /**
     * 가중치 기반으로 재앙 하나를 무작위로 고른다. excludeId/excludeLevel은 위 오버로드와 같고,
     * disasterFilter가 주어지면 그 조건(test)을 만족하지 않는 재앙도 후보에서 제외된다(null이면 제한 없음).
     * 아포칼립스가 연쇄 재앙을 고를 때, "지금 재앙이 발동한 차원"이 아니라 "플레이어가 있는 다른 차원에서라도
     * 실행 가능한지"까지 넓게 봐야 하는 경우처럼 단순 차원 일치보다 복잡한 조건이 필요할 때 쓴다.
     */
    public Disaster pickWeightedDisaster(String excludeId, DangerLevel excludeLevel, Predicate<Disaster> disasterFilter) {
        List<Disaster> pool = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int totalWeight = 0;

        for (Disaster disaster : disasters.values()) {
            if (excludeId != null && excludeId.equals(disaster.getId())) {
                continue;
            }
            if (excludeLevel != null && disaster.getDangerLevel() == excludeLevel) {
                continue;
            }
            if (disasterFilter != null && !disasterFilter.test(disaster)) {
                continue;
            }
            if (!toggleStore.isDisasterEnabled(disaster.getId())) {
                continue;
            }
            String base = "disasters." + disaster.getId() + ".";
            int weight = Math.max(0, plugin.getConfig().getInt(base + "weight", 1));
            if (weight == 0) {
                continue;
            }
            pool.add(disaster);
            weights.add(weight);
            totalWeight += weight;
        }

        if (pool.isEmpty() || totalWeight <= 0) {
            return null;
        }

        int roll = random.nextInt(totalWeight);
        int cursor = 0;
        for (int i = 0; i < pool.size(); i++) {
            cursor += weights.get(i);
            if (roll < cursor) {
                return pool.get(i);
            }
        }
        return pool.get(pool.size() - 1);
    }

    /**
     * disaster를 실제로 실행할 수 있는 월드를 찾는다. preferredWorld가 이 재앙을 지원하고 플레이어도 있으면
     * 그대로 반환하고, 아니면 settings.worlds 중 이 재앙을 지원하면서 플레이어가 있는 다른 월드를 무작위로 고른다.
     * 그런 월드가 하나도 없으면 null. 아포칼립스가 네더/엔드에서 발동했을 때, 그 차원에서 무력화되는 재앙이라도
     * 오버월드처럼 플레이어가 있는 다른 지원 차원이 있으면 거기서 대신 실행할 수 있게 하는 데 쓴다.
     */
    public World findWorldFor(Disaster disaster, World preferredWorld) {
        if (preferredWorld != null
                && disaster.getSupportedEnvironments().contains(preferredWorld.getEnvironment())
                && !PlayerFilter.targetable(preferredWorld.getPlayers()).isEmpty()) {
            return preferredWorld;
        }
        List<World> candidates = getConfiguredWorlds();
        candidates.removeIf(w -> !disaster.getSupportedEnvironments().contains(w.getEnvironment()));
        candidates.removeIf(w -> PlayerFilter.targetable(w.getPlayers()).isEmpty());
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    private List<World> getConfiguredWorlds() {
        List<String> names = plugin.getConfig().getStringList("settings.worlds");
        List<World> worlds = new ArrayList<>();
        for (String name : names) {
            World world = Bukkit.getWorld(name);
            if (world != null) {
                worlds.add(world);
            }
        }
        return worlds;
    }

    /** 이 재앙이 실제로 발생 중인 월드의 플레이어에게만 메시지를 보낸다. 다른 월드 플레이어는 받지 않는다. */
    private void broadcast(World world, Component message) {
        for (Player player : world.getPlayers()) {
            player.sendMessage(message);
        }
    }
}
