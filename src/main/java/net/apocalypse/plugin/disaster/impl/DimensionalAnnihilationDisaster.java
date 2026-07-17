package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import net.apocalypse.plugin.util.ColorUtil;
import net.apocalypse.plugin.util.PlayerFilter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 최상위(6단계) 위험도의 초희귀 재앙. 타겟 플레이어를 정해두고 1분을 기다린 뒤,
 * "소멸 바로 직전 시점"의 타겟 위치를 기준으로 그 청크를 중심으로 한 3x3 청크 범위 안에서
 * 무작위로 청크 하나를 골라 통째로 지워버린다(위치는 발동 시점이 아니라 소멸 시점 기준).
 * 그 청크 안의 모든 블록과 엔티티를 완전히 소멸시킨다.
 * 플레이어 엔티티 자체를 강제로 제거하는 건 연결 처리 등에서 위험하므로 하지 않고,
 * 대신 그 청크 안에 있던 플레이어에게는 사실상 즉사에 해당하는 매우 큰 고정 데미지를 준다.
 * 한 번 소멸된 청크는 원래대로 돌아오지 않는 영구적인 공허로 남는다 — 근처로 다가가면 경고가 뜨고,
 * 그 안에 들어간 엔티티는 계속 처치되며, 블록을 새로 놓아도 즉시 거부된다(공허 유지는
 * {@link #watcherTick(Plugin)}이, 블록 설치 거부는 별도의
 * {@link net.apocalypse.plugin.listener.AnnihilationZoneListener}가 담당한다).
 */
public class DimensionalAnnihilationDisaster implements Disaster {

    /**
     * 지금까지 소멸된 모든 청크의 집합. 재트리거될 때마다 계속 늘어나기만 하는 누적 상태라
     * (한 번 지워진 청크는 절대 되살아나지 않으므로) 트리거별로 분리할 필요 없이 정적으로 공유해도 안전하다
     * — {@link DisasterContext}의 트리거별 state와 달리, 이건 여러 트리거가 서로 덮어쓸 일이 없는 순수 누적값이다.
     * 서버 재시작/플러그인 리로드 후에도 유지되도록 {@link VoidChunkStore}(void-chunks.yml)에서 불러온다
     * ({@link #loadPersistedVoidChunks(Plugin)}가 플러그인이 켜질 때 이 목록을 채운다).
     */
    private static final Set<VoidChunkPos> VOID_CHUNKS = ConcurrentHashMap.newKeySet();

    /** 공허 유지(엔티티 처치 + 접근 경고)를 도는 전역 감시 작업. 소멸된 청크가 하나라도 생기면 한 번만 시작된다. */
    private static volatile BukkitTask watcherTask;

    /** 새로 소멸되는 청크를 파일에 이어붙여 저장하는 저장소. loadPersistedVoidChunks()에서 초기화된다. */
    private static volatile VoidChunkStore store;

    /** 지금 접근 경고를 받은 상태인 플레이어들. 경고 반경에 머무는 동안 계속 스팸하지 않도록, 들어올 때 한 번만 보낸다. */
    private static final Set<UUID> WARNED_PLAYERS = ConcurrentHashMap.newKeySet();

    private final Random random = new Random();

    @Override
    public String getId() {
        return "dimensional-annihilation";
    }

    @Override
    public String getDisplayName() {
        return "차원 소멸";
    }

    @Override
    public DangerLevel getDangerLevel() {
        return DangerLevel.LEVEL_6;
    }

    /** 이 월드의 이 청크 좌표가 이미 소멸된 공허인지 확인한다. {@link net.apocalypse.plugin.listener.AnnihilationZoneListener}가 블록 설치를 막을 때 쓴다. */
    public static boolean isVoidChunk(World world, int chunkX, int chunkZ) {
        return VOID_CHUNKS.contains(new VoidChunkPos(world.getUID(), chunkX, chunkZ));
    }

    /**
     * 플러그인이 켜질 때(또는 리로드될 때) void-chunks.yml에서 지금까지 소멸된 청크 목록을 불러와,
     * 재시작/리로드 전에 이미 진행 중이던 공허 유지 효과(경고/즉사/블록 설치 거부)가 끊기지 않고 이어지게 한다.
     * {@link net.apocalypse.plugin.ApocalypsePlugin#onEnable()}에서 한 번 호출된다.
     */
    public static void loadPersistedVoidChunks(Plugin plugin) {
        VoidChunkStore loadedStore = new VoidChunkStore(plugin);
        store = loadedStore;
        VOID_CHUNKS.addAll(loadedStore.load());
        if (!VOID_CHUNKS.isEmpty()) {
            ensureWatcherRunning(plugin);
        }
    }

    @Override
    public void trigger(DisasterContext context) {
        List<Player> players = context.players();
        if (players.isEmpty()) {
            return;
        }

        Plugin plugin = context.plugin();
        World world = context.world();
        ConfigurationSection section = context.config();

        double playerDamage = section.getDouble("player-damage", 10000.0);
        // 명령어/아포칼립스 연쇄처럼 즉시 발동된 경우엔 이 "조용히 기다리는" 지연을 건너뛰고 바로 소멸시킨다.
        long annihilationDelayTicks = context.immediate() ? 0
                : Math.max(0, section.getLong("annihilation-delay-seconds", 60)) * 20L;
        Component annihilationMessage = ColorUtil.parse(
                section.getString("annihilation-message", "&4&l차원이 완전히 소멸했습니다!"));

        // 타겟만 지금 정해두고, 위치는 고정하지 않는다 — 소멸 직전(1분 뒤)의 위치를 기준으로 삼는다.
        Player initialTarget = players.get(random.nextInt(players.size()));

        context.track(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player target = initialTarget;
            if (!target.isOnline()) {
                // 그 사이 타겟이 접속을 종료했으면, 그 시점에 그 월드에 있는 플레이어 중 새로 하나를 다시 뽑는다.
                List<Player> onlineNow = PlayerFilter.targetable(world.getPlayers());
                if (onlineNow.isEmpty()) {
                    return;
                }
                target = onlineNow.get(random.nextInt(onlineNow.size()));
            }

            Chunk targetChunk = target.getLocation().getChunk();
            int centerChunkX = targetChunk.getX();
            int centerChunkZ = targetChunk.getZ();

            int offsetX = random.nextInt(3) - 1;
            int offsetZ = random.nextInt(3) - 1;
            Chunk chunk = world.getChunkAt(centerChunkX + offsetX, centerChunkZ + offsetZ);

            annihilateChunk(world, chunk, playerDamage);
            VoidChunkPos pos = new VoidChunkPos(world.getUID(), chunk.getX(), chunk.getZ());
            VOID_CHUNKS.add(pos);
            if (store != null) {
                store.append(pos);
            }
            ensureWatcherRunning(plugin);
            for (Player p : world.getPlayers()) {
                p.sendMessage(annihilationMessage);
            }
        }, annihilationDelayTicks));
    }

    /** 청크 안의 모든 엔티티를 지우고(플레이어는 대신 큰 데미지), 모든 블록을 공기로 만든다. */
    private void annihilateChunk(World world, Chunk chunk, double playerDamage) {
        killEntitiesIn(chunk, playerDamage);

        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minHeight; y < maxHeight; y++) {
                    Block block = world.getBlockAt(baseX + x, y, baseZ + z);
                    if (!block.getType().isAir()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /** 청크 안의 엔티티를 처치한다(플레이어는 대신 큰 데미지). 최초 소멸과 이후 감시 틱에서 공용으로 쓴다. */
    private static void killEntitiesIn(Chunk chunk, double playerDamage) {
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player player) {
                if (PlayerFilter.isTargetable(player)) {
                    player.damage(playerDamage);
                }
            } else {
                entity.remove();
            }
        }
    }

    /** 공허 유지 감시 작업을 처음 한 번만 시작한다. 이후로는 서버가 켜져 있는 한 계속 돈다(재앙의 지속시간과 무관한 영구 효과). */
    private static synchronized void ensureWatcherRunning(Plugin plugin) {
        if (watcherTask != null) {
            return;
        }
        long intervalTicks = Math.max(5, plugin.getConfig()
                .getLong("disasters.dimensional-annihilation.void-zone-check-interval-ticks", 20));
        watcherTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> watcherTick(plugin), intervalTicks, intervalTicks);
    }

    /**
     * 소멸된 청크가 있는 한 계속 도는 감시 틱. 청크 안에 새로 들어온 엔티티를 처치하고,
     * 청크 경계 warning-radius 안으로 다가온 플레이어에게 경고 메시지를 보낸다(같은 방문 동안은 한 번만).
     */
    private static void watcherTick(Plugin plugin) {
        if (VOID_CHUNKS.isEmpty()) {
            return;
        }

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("disasters.dimensional-annihilation");
        double playerDamage = section != null ? section.getDouble("player-damage", 10000.0) : 10000.0;
        double warningRadius = Math.max(0, section != null ? section.getDouble("void-zone-warning-radius", 10.0) : 10.0);
        Component warningMessage = ColorUtil.parse(section != null
                ? section.getString("void-zone-warning-message", "&8&l발밑의 세계가 사라진 것이 느껴집니다... 이 앞은 공허입니다.")
                : "&8&l발밑의 세계가 사라진 것이 느껴집니다... 이 앞은 공허입니다.");

        for (VoidChunkPos pos : VOID_CHUNKS) {
            World world = Bukkit.getWorld(pos.world());
            if (world == null || !world.isChunkLoaded(pos.x(), pos.z())) {
                continue;
            }
            killEntitiesIn(world.getChunkAt(pos.x(), pos.z()), playerDamage);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!PlayerFilter.isTargetable(player)) {
                continue;
            }
            boolean near = isNearAnyVoidChunk(player, warningRadius);
            if (near) {
                if (WARNED_PLAYERS.add(player.getUniqueId())) {
                    player.sendMessage(warningMessage);
                }
            } else {
                WARNED_PLAYERS.remove(player.getUniqueId());
            }
        }
    }

    /** 플레이어가 자기 월드의 어느 소멸된 청크로부터든 radius 블록 이내(수평 거리)에 있는지 확인한다. */
    private static boolean isNearAnyVoidChunk(Player player, double radius) {
        UUID worldId = player.getWorld().getUID();
        double px = player.getLocation().getX();
        double pz = player.getLocation().getZ();
        double radiusSquared = radius * radius;

        for (VoidChunkPos pos : VOID_CHUNKS) {
            if (!pos.world().equals(worldId)) {
                continue;
            }
            double minX = pos.x() << 4;
            double minZ = pos.z() << 4;
            double maxX = minX + 16;
            double maxZ = minZ + 16;
            double dx = Math.max(minX - px, Math.max(0, px - maxX));
            double dz = Math.max(minZ - pz, Math.max(0, pz - maxZ));
            if (dx * dx + dz * dz <= radiusSquared) {
                return true;
            }
        }
        return false;
    }
}
