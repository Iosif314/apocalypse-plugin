package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import net.apocalypse.plugin.util.ColorUtil;
import net.apocalypse.plugin.util.PlayerFilter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 타겟 플레이어 한 명을 정해 그 위치(고정)로 운석을 떨어뜨리는 재앙.
 * 폭발은 바닐라 폭발 이벤트를 쓰지 않고 구체 형태로 블록을 직접 제거하며,
 * 파괴된 구체 바로 바깥쪽 한 겹은 네더랙으로 채운다.
 */
public class MeteorStrikeDisaster implements Disaster {

    private final Random random = new Random();

    // /apoc stop으로 비행 중에 강제로 멈추면 예약된 작업만 취소해선 이 비주얼(블록 디스플레이)이 공중에 남으므로,
    // onStop에서 같이 지울 수 있도록 현재 날아가고 있는 운석의 비주얼을 기억해둔다.
    private List<BlockDisplay> currentVisuals = new ArrayList<>();

    @Override
    public String getId() {
        return "meteor-strike";
    }

    @Override
    public String getDisplayName() {
        return "운석 낙하";
    }

    @Override
    public DangerLevel getDangerLevel() {
        // 직격 1500 데미지(사실상 즉사), 직경 175블록 크레이터급 파괴력이라 최고 등급으로 매김
        return DangerLevel.LEVEL_5;
    }

    @Override
    public void trigger(DisasterContext context) {
        List<Player> players = context.players();
        if (players.isEmpty()) {
            return;
        }

        ConfigurationSection section = context.config();
        Plugin plugin = context.plugin();
        World world = context.world();

        Player target = players.get(random.nextInt(players.size()));

        // 타겟 위치를 지금 이 순간 고정한다. 이후 플레이어가 움직여도 착탄 지점은 바뀌지 않는다.
        Location targetLoc = target.getLocation();
        int impactX = targetLoc.getBlockX();
        int impactZ = targetLoc.getBlockZ();
        int impactY = world.getHighestBlockYAt(impactX, impactZ);
        Location impactLoc = new Location(world, impactX + 0.5, impactY, impactZ + 0.5);

        double spawnHeight = section.getDouble("spawn-height", 300);
        double spawnOffsetRadius = section.getDouble("spawn-offset-radius", 100);
        double spawnX = impactX + 0.5 + (random.nextDouble() * 2 - 1) * spawnOffsetRadius;
        double spawnZ = impactZ + 0.5 + (random.nextDouble() * 2 - 1) * spawnOffsetRadius;
        Location spawnLoc = new Location(world, spawnX, spawnHeight, spawnZ);

        double travelSpeed = Math.max(0.5, section.getDouble("travel-speed", 4.0));
        double visualDiameter = Math.max(1, section.getDouble("visual-diameter", 10.0));
        double craterRadius = Math.max(1, section.getDouble("crater-diameter", 175) / 2.0);
        boolean netherrackShell = section.getBoolean("netherrack-shell", true);
        int cellsPerTick = Math.max(1000, section.getInt("excavation-cells-per-tick", 200000));
        int minOres = section.getInt("ore-count.min", 10);
        int maxOres = section.getInt("ore-count.max", 100);
        double oreLaunchSpeedMin = section.getDouble("ore-launch-speed.min", 1.5);
        double oreLaunchSpeedMax = section.getDouble("ore-launch-speed.max", 4.0);
        List<Material> oreBlocks = readOreBlocks(section);

        float soundVolume = (float) section.getDouble("impact-sound-volume", 8.0);
        float soundPitch = (float) section.getDouble("impact-sound-pitch", 0.5);
        double directHitDamage = section.getDouble("direct-hit-damage", 1500.0);
        double splashDamageMax = section.getDouble("splash-damage-max", 750.0);
        double splashDamageMin = section.getDouble("splash-damage-min", 250.0);

        String revealMessage = section.getString("target-reveal-message", "&c&l경고! %player%님을 향해 운석이 낙하하고 있습니다!")
                .replace("%player%", target.getName());

        List<BlockDisplay> visuals = spawnMeteorVisual(world, spawnLoc, visualDiameter);
        currentVisuals = visuals;

        flyMeteor(context, plugin, world, spawnLoc, impactLoc, visuals, travelSpeed, visualDiameter, ColorUtil.parse(revealMessage),
                craterRadius, netherrackShell, cellsPerTick, minOres, maxOres, oreBlocks,
                oreLaunchSpeedMin, oreLaunchSpeedMax,
                soundVolume, soundPitch, directHitDamage, splashDamageMax, splashDamageMin);
    }

    /** /apoc stop으로 강제 중단됐을 때, 비행 중이던 운석 비주얼이 공중에 남지 않도록 지운다. */
    @Override
    public void onStop(DisasterContext context) {
        for (BlockDisplay display : currentVisuals) {
            if (!display.isDead()) {
                display.remove();
            }
        }
        currentVisuals = new ArrayList<>();
    }

    private List<Material> readOreBlocks(ConfigurationSection section) {
        List<Material> ores = new ArrayList<>();
        for (String name : section.getStringList("ore-blocks")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                ores.add(material);
            }
        }
        if (ores.isEmpty()) {
            ores.add(Material.IRON_ORE);
        }
        return ores;
    }

    /** 마그마 블록 여러 개를 뭉친 모양의 운석 비주얼(블록 디스플레이 클럼프)을 생성한다. 대략 visualDiameter 블록 크기로 뭉친다. */
    private List<BlockDisplay> spawnMeteorVisual(World world, Location center, double visualDiameter) {
        List<BlockDisplay> displays = new ArrayList<>();
        int clumpSize = 5 + random.nextInt(4);
        float offsetRadius = (float) (visualDiameter * 0.3);
        float minScale = (float) (visualDiameter * 0.25);
        float maxScale = (float) (visualDiameter * 0.35);

        for (int i = 0; i < clumpSize; i++) {
            float scale = minScale + random.nextFloat() * (maxScale - minScale);
            Vector3f offset = new Vector3f(
                    (random.nextFloat() * 2 - 1) * offsetRadius,
                    (random.nextFloat() * 2 - 1) * offsetRadius,
                    (random.nextFloat() * 2 - 1) * offsetRadius
            );
            Transformation transformation = new Transformation(
                    offset,
                    new Quaternionf(),
                    new Vector3f(scale, scale, scale),
                    new Quaternionf()
            );

            BlockDisplay display = world.spawn(center, BlockDisplay.class, entity -> {
                entity.setBlock(Material.MAGMA_BLOCK.createBlockData());
                entity.setTransformation(transformation);
            });
            displays.add(display);
        }
        return displays;
    }

    private void flyMeteor(DisasterContext context, Plugin plugin, World world, Location spawnLoc, Location impactLoc,
                            List<BlockDisplay> visuals, double speed, double visualDiameter, Component revealMessage,
                            double craterRadius, boolean netherrackShell, int cellsPerTick,
                            int minOres, int maxOres, List<Material> oreBlocks,
                            double oreLaunchSpeedMin, double oreLaunchSpeedMax,
                            float soundVolume, float soundPitch, double directHitDamage,
                            double splashDamageMax, double splashDamageMin) {
        Vector fullPath = impactLoc.toVector().subtract(spawnLoc.toVector());
        double totalDistance = fullPath.length();
        Vector step = totalDistance > 0 ? fullPath.clone().normalize().multiply(speed) : new Vector(0, 0, 0);
        double midY = (spawnLoc.getY() + impactLoc.getY()) / 2.0;
        double hitRadius = visualDiameter / 2.0;

        context.track(new BukkitRunnable() {
            double traveled = 0;
            boolean announced = false;
            final Location current = spawnLoc.clone();
            final Set<UUID> hitEntities = new HashSet<>();

            @Override
            public void run() {
                traveled += speed;
                boolean arrived = traveled >= totalDistance;
                if (arrived) {
                    current.setX(impactLoc.getX());
                    current.setY(impactLoc.getY());
                    current.setZ(impactLoc.getZ());
                } else {
                    current.add(step);
                }

                for (BlockDisplay display : visuals) {
                    if (!display.isDead()) {
                        display.teleport(current);
                    }
                }

                applyDirectHitDamage(world, current, hitRadius, directHitDamage, hitEntities);

                if (!announced && current.getY() <= midY) {
                    announced = true;
                    Bukkit.broadcast(revealMessage);
                }

                if (arrived) {
                    cancel();
                    visuals.forEach(Entity::remove);
                    world.playSound(impactLoc, Sound.ENTITY_GENERIC_EXPLODE, soundVolume, soundPitch);
                    applySplashDamage(world, impactLoc, craterRadius, splashDamageMax, splashDamageMin, hitEntities);
                    excavateCrater(context, plugin, world, impactLoc, craterRadius, netherrackShell, cellsPerTick,
                            () -> spawnOreDebris(world, impactLoc, minOres, maxOres, oreBlocks,
                                    oreLaunchSpeedMin, oreLaunchSpeedMax));
                }
            }
        }.runTaskTimer(plugin, 0L, 1L));
    }

    /** 운석 비주얼 반경 안에 들어온 엔티티에게 직격 데미지를 준다 (한 번만). */
    private void applyDirectHitDamage(World world, Location current, double hitRadius, double damage,
                                       Set<UUID> hitEntities) {
        for (Entity entity : world.getNearbyEntities(current, hitRadius, hitRadius, hitRadius)) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (entity instanceof Player player && !PlayerFilter.isTargetable(player)) {
                continue;
            }
            if (!hitEntities.add(entity.getUniqueId())) {
                continue;
            }
            if (entity.getLocation().distance(current) > hitRadius) {
                hitEntities.remove(entity.getUniqueId());
                continue;
            }
            living.damage(damage);
        }
    }

    /** 직격되지 않은 엔티티에게 착탄 지점 기준 거리에 따라 선형으로 감소하는 범위 데미지를 준다. */
    private void applySplashDamage(World world, Location center, double radius, double maxDamage, double minDamage,
                                    Set<UUID> alreadyHit) {
        if (radius <= 0) {
            return;
        }
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (entity instanceof Player player && !PlayerFilter.isTargetable(player)) {
                continue;
            }
            if (alreadyHit.contains(entity.getUniqueId())) {
                continue;
            }
            double distance = entity.getLocation().distance(center);
            if (distance > radius) {
                continue;
            }
            double ratio = distance / radius;
            double damage = maxDamage - (maxDamage - minDamage) * ratio;
            living.damage(damage);
        }
    }

    /**
     * 착탄 지점을 중심으로 구체 형태로 블록을 직접 제거하고, 그 바로 바깥 한 겹을 네더랙으로 채운다.
     * 실제 폭발파처럼 중심에서 가까운 셀부터 바깥으로 퍼져나가는 순서로 처리되며, 서버 랙 방지를 위해 여러 틱에 나눠 처리한다.
     */
    private void excavateCrater(DisasterContext context, Plugin plugin, World world, Location center, double radius,
                                 boolean netherrackShell, int cellsPerTick, Runnable onComplete) {
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        // 오프셋을 int 하나에 (dx,dy,dz) 각 1바이트로 압축하므로 r은 127을 넘을 수 없다(그 이상은 안전하게 잘라낸다).
        int r = Math.min(127, (int) Math.ceil(radius) + 1);
        double radiusSquared = radius * radius;
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();

        // 중심에서 가까운 셀부터 처리되도록, 거리 기준으로 미리 정렬된 순서를 계산해둔다.
        int[] order = buildDistanceSortedOffsets(r);

        context.track(new BukkitRunnable() {
            long index = 0;

            @Override
            public void run() {
                long processed = 0;
                while (processed < cellsPerTick && index < order.length) {
                    int packed = order[(int) index++];
                    int dx = ((packed >> 16) & 0xFF) - 128;
                    int dy = ((packed >> 8) & 0xFF) - 128;
                    int dz = (packed & 0xFF) - 128;

                    int y = cy + dy;
                    if (y >= minHeight && y < maxHeight) {
                        double distSq = distanceSquared(dx, dy, dz);
                        if (distSq <= radiusSquared) {
                            world.getBlockAt(cx + dx, y, cz + dz).setType(Material.AIR, false);
                        } else if (netherrackShell && isShell(dx, dy, dz, radiusSquared)) {
                            Block shellBlock = world.getBlockAt(cx + dx, y, cz + dz);
                            if (!shellBlock.getType().isAir()) {
                                shellBlock.setType(Material.NETHERRACK, false);
                            }
                        }
                    }
                    processed++;
                }

                if (index >= order.length) {
                    cancel();
                    onComplete.run();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L));
    }

    /**
     * -r..r 범위 세제곱 안의 모든 (dx,dy,dz) 오프셋을, 중심으로부터의 거리가 가까운 순서대로 정렬해 반환한다.
     * 각 오프셋은 (dx+128)&0xFF를 상위/중간/하위 바이트에 담아 하나의 int로 압축해서 메모리를 아낀다(반경 127 이하에서 유효).
     * 정렬은 카운팅 정렬(거리 정수값 기준 버킷)을 써서 대량의 셀도 한 번에 O(n)으로 처리한다.
     */
    private int[] buildDistanceSortedOffsets(int r) {
        int side = 2 * r + 1;
        int total = side * side * side;
        int maxBucket = (int) Math.ceil(Math.sqrt(3.0) * r) + 1;
        int[] bucketCounts = new int[maxBucket + 1];

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int bucket = (int) Math.sqrt(distanceSquared(dx, dy, dz));
                    bucketCounts[bucket]++;
                }
            }
        }

        int[] bucketOffsets = new int[maxBucket + 1];
        int running = 0;
        for (int b = 0; b <= maxBucket; b++) {
            bucketOffsets[b] = running;
            running += bucketCounts[b];
        }

        int[] cursor = bucketOffsets.clone();
        int[] sorted = new int[total];
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int bucket = (int) Math.sqrt(distanceSquared(dx, dy, dz));
                    int packed = ((dx + 128) << 16) | ((dy + 128) << 8) | (dz + 128);
                    sorted[cursor[bucket]++] = packed;
                }
            }
        }
        return sorted;
    }

    private boolean isShell(int dx, int dy, int dz, double radiusSquared) {
        return distanceSquared(dx - 1, dy, dz) <= radiusSquared
                || distanceSquared(dx + 1, dy, dz) <= radiusSquared
                || distanceSquared(dx, dy - 1, dz) <= radiusSquared
                || distanceSquared(dx, dy + 1, dz) <= radiusSquared
                || distanceSquared(dx, dy, dz - 1) <= radiusSquared
                || distanceSquared(dx, dy, dz + 1) <= radiusSquared;
    }

    private double distanceSquared(int dx, int dy, int dz) {
        return (double) dx * dx + (double) dy * dy + (double) dz * dz;
    }

    /** 착탄 지점에서 광석 블록을 폴링 블록으로 소환해 무작위 방향/힘으로 튕겨낸다. */
    private void spawnOreDebris(World world, Location center, int minOres, int maxOres, List<Material> oreBlocks,
                                 double minSpeed, double maxSpeed) {
        int count = minOres + (maxOres > minOres ? random.nextInt(maxOres - minOres + 1) : 0);

        for (int i = 0; i < count; i++) {
            Material ore = oreBlocks.get(random.nextInt(oreBlocks.size()));
            FallingBlock fallingBlock = world.spawn(center, FallingBlock.class, entity -> {
                entity.setBlockData(ore.createBlockData());
                entity.setDropItem(true);
            });

            double yaw = random.nextDouble() * 2 * Math.PI;
            double pitch = Math.toRadians(30 + random.nextDouble() * 50);
            double speed = minSpeed + random.nextDouble() * (maxSpeed - minSpeed);

            double horizontal = Math.cos(pitch) * speed;
            double vx = horizontal * Math.cos(yaw);
            double vy = Math.sin(pitch) * speed;
            double vz = horizontal * Math.sin(yaw);

            fallingBlock.setVelocity(new Vector(vx, vy, vz));
        }
    }
}
