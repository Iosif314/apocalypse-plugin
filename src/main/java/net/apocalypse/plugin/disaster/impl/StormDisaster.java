package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import net.apocalypse.plugin.util.PlayerFilter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;

/**
 * 지속 시간 동안 월드 날씨를 뇌우로 바꾸고, 주기적으로 플레이어 근처에 낙뢰를 떨어뜨리고 돌풍으로 넉백시킨다.
 * 여기에 더해 지연 시간 뒤 회오리(토네이도) 몇 개가 생성되어 지형을 떠돌며 블록을 부수고, 반경 안 엔티티/플레이어를
 * 원형으로 끌어당겨 돌리면서 위로 띄워 올린다.
 * 토네이도 반경(기본 75블록, 운석 크레이터급)·높이(200블록)·이동속도가 커서 실내로 피하는 것만으로는
 * 안전을 장담하기 어렵다.
 */
public class StormDisaster implements Disaster {

    private final Random random = new Random();

    @Override
    public String getId() {
        return "storm";
    }

    @Override
    public String getDisplayName() {
        return "폭풍우";
    }

    @Override
    public DangerLevel getDangerLevel() {
        // 토네이도 규모가 운석 크레이터급(반경 75)까지 커져서 회피가 어려워진 만큼 상향 조정
        return DangerLevel.LEVEL_4;
    }

    @Override
    public void trigger(DisasterContext context) {
        Plugin plugin = context.plugin();
        World world = context.world();
        ConfigurationSection section = context.config();

        // 폭풍우 시작 후 이만큼 지나야 토네이도가 생성된다.
        // 명령어/아포칼립스 연쇄처럼 즉시 발동된 경우엔 이 "조용히 기다리는" 지연을 건너뛰고 바로 생성한다.
        long tornadoDelayTicks = context.immediate() ? 0
                : Math.max(0, section.getLong("tornado-delay-seconds", 60)) * 20L;
        // 토네이도가 생성된 뒤 유지되는 시간(초). 폭풍우 전체는 tornado-delay-seconds + duration-seconds만큼 지속된다.
        long durationTicks = section.getLong("duration-seconds", 60) * 20L;
        long totalTicks = tornadoDelayTicks + durationTicks;
        long lightningIntervalTicks = Math.max(20, section.getLong("lightning-interval-ticks", 60));
        double lightningChance = section.getDouble("lightning-chance", 35.0);
        double lightningRadius = Math.max(0, section.getDouble("lightning-radius", 6));
        long windIntervalTicks = Math.max(20, section.getLong("wind-interval-ticks", 100));
        double windForce = section.getDouble("wind-force", 1.4);
        float windSoundVolume = (float) Math.max(0, section.getDouble("wind-sound-volume", 1.0));
        float windSoundPitch = (float) section.getDouble("wind-sound-pitch", 0.6);

        int tornadoCount = Math.max(0, section.getInt("tornado-count", 2));
        double tornadoSpawnOffsetRadius = Math.max(0, section.getDouble("tornado-spawn-offset-radius", 75));
        long tornadoTickInterval = Math.max(1, section.getLong("tornado-tick-interval", 2));
        double tornadoDriftSpeed = section.getDouble("tornado-drift-speed", 0.75);
        double tornadoPullRadius = Math.max(1, section.getDouble("tornado-pull-radius", 75));
        double tornadoPullStrength = section.getDouble("tornado-pull-strength", 0.1);
        double tornadoSpinStrength = section.getDouble("tornado-spin-strength", 0.5);
        double tornadoLiftStrength = section.getDouble("tornado-lift-strength", 0.35);
        double tornadoMaxLiftHeight = section.getDouble("tornado-max-lift-height", 150);
        double tornadoDestructionRadius = Math.max(0, section.getDouble("tornado-destruction-radius", 75));
        int tornadoDestructionHeight = Math.max(1, section.getInt("tornado-destruction-height", 200));
        double tornadoDestructionChance = section.getDouble("tornado-destruction-chance", 0.5);
        double tornadoDebrisChance = section.getDouble("tornado-debris-chance", 0.3);
        int tornadoDestructionCellsPerTick = Math.max(1000, section.getInt("tornado-destruction-cells-per-tick", 50000));
        int tornadoVisualHeight = Math.max(1, section.getInt("tornado-visual-height", 200));

        world.setStorm(true);
        world.setThundering(true);
        world.setWeatherDuration((int) totalTicks);
        world.setThunderDuration((int) totalTicks);

        new BukkitRunnable() {
            long elapsed = 0;

            @Override
            public void run() {
                elapsed += lightningIntervalTicks;
                strikeLightningNearPlayers(world, lightningChance, lightningRadius);
                if (elapsed >= totalTicks) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, lightningIntervalTicks, lightningIntervalTicks);

        new BukkitRunnable() {
            long elapsed = 0;

            @Override
            public void run() {
                elapsed += windIntervalTicks;
                applyWindGust(world, windForce);
                playWindSoundToExposedPlayers(world, windSoundVolume, windSoundPitch);
                if (elapsed >= totalTicks) {
                    cancel();
                    world.setStorm(false);
                    world.setThundering(false);
                }
            }
        }.runTaskTimer(plugin, windIntervalTicks, windIntervalTicks);

        // 폭풍우가 시작되고 tornado-delay-seconds가 지난 뒤에야 토네이도가 생성된다.
        new BukkitRunnable() {
            @Override
            public void run() {
                List<Player> players = PlayerFilter.targetable(world.getPlayers());
                for (int i = 0; i < tornadoCount && !players.isEmpty(); i++) {
                    Player target = players.get(random.nextInt(players.size()));
                    Location targetLoc = target.getLocation();
                    double x = targetLoc.getX() + (random.nextDouble() * 2 - 1) * tornadoSpawnOffsetRadius;
                    double z = targetLoc.getZ() + (random.nextDouble() * 2 - 1) * tornadoSpawnOffsetRadius;
                    int y = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
                    Location start = new Location(world, x, y, z);

                    spawnTornado(plugin, world, start, durationTicks, tornadoTickInterval, tornadoDriftSpeed,
                            tornadoPullRadius, tornadoPullStrength, tornadoSpinStrength, tornadoLiftStrength,
                            tornadoMaxLiftHeight, tornadoDestructionRadius, tornadoDestructionHeight,
                            tornadoDestructionChance, tornadoDebrisChance, tornadoDestructionCellsPerTick, tornadoVisualHeight);
                }
            }
        }.runTaskLater(plugin, tornadoDelayTicks);
    }

    /**
     * 회오리 하나를 생성한다. 지형 위를 서서히 떠돌면서(랜덤워크) 매 갱신마다 파티클을 뿌리고 주변 엔티티를
     * 원형으로 끌어올린다. 블록 파괴는 반경/높이가 매우 커서(수백만 셀) 한 번에 처리하면 랙이 나므로,
     * 운석 크레이터처럼 틱당 정해진 셀 수만큼만 처리하고 이어서 다음 틱에 계속하는 방식으로 나눠 돌린다.
     */
    private void spawnTornado(Plugin plugin, World world, Location start, long durationTicks, long tickInterval,
                               double driftSpeed, double pullRadius, double pullStrength, double spinStrength,
                               double liftStrength, double maxLiftHeight, double destructionRadius,
                               int destructionHeight, double destructionChance, double debrisChance,
                               int destructionCellsPerTick, int visualHeight) {
        new BukkitRunnable() {
            final Location center = start.clone();
            Vector drift = randomHorizontalUnit().multiply(driftSpeed);
            long elapsed = 0;
            double particlePhase = 0;
            DestructionCursor destructionCursor;

            @Override
            public void run() {
                elapsed += tickInterval;
                particlePhase += 0.3;

                if (random.nextInt(20) == 0) {
                    drift = randomHorizontalUnit().multiply(driftSpeed);
                }
                center.add(drift);
                center.setY(world.getHighestBlockYAt(center.getBlockX(), center.getBlockZ()));

                spawnVortexParticles(world, center, pullRadius, visualHeight, particlePhase);
                applyVortexForces(world, center, pullRadius, pullStrength, spinStrength, liftStrength, maxLiftHeight);

                // 진행 중인 파괴 배치가 없으면 지금 중심 위치를 기준으로 새로 하나 시작한다.
                if (destructionCursor == null) {
                    destructionCursor = new DestructionCursor(world, center, destructionRadius, destructionHeight,
                            destructionChance, debrisChance, pullStrength, spinStrength, liftStrength);
                }
                if (destructionCursor.processBudget(destructionCellsPerTick)) {
                    destructionCursor = null;
                }

                if (elapsed >= durationTicks) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, tickInterval);
    }

    /** 회오리 기둥을 따라 여러 층에 원형으로 회전하는 구름/전기 파티클을 뿌려서 눈에 보이는 소용돌이 모양을 만든다. */
    private void spawnVortexParticles(World world, Location center, double radius, int visualHeight, double phase) {
        int pointsPerRing = 16;
        int yStep = Math.max(2, visualHeight / 20);
        for (int y = 0; y < visualHeight; y += yStep) {
            double layerRadius = radius * (0.25 + 0.75 * y / (double) visualHeight);
            for (int i = 0; i < pointsPerRing; i++) {
                double angle = phase + (2 * Math.PI * i / pointsPerRing);
                double px = center.getX() + Math.cos(angle) * layerRadius;
                double pz = center.getZ() + Math.sin(angle) * layerRadius;
                double py = center.getY() + y;
                world.spawnParticle(Particle.CLOUD, px, py, pz, 0, 0, 0, 0, 0);
                if (y % (yStep * 3) == 0) {
                    world.spawnParticle(Particle.ELECTRIC_SPARK, px, py, pz, 0, 0, 0, 0, 0);
                }
            }
        }
    }

    /**
     * 회오리 파괴 범위(원기둥)를 셀 단위로 순회하는 진행 커서. 실제 회오리처럼 각도 순으로 회전하며
     * 한 각도에서 세로 기둥 전체를 처리한 뒤 다음 각도로 넘어간다. processBudget()이 호출될 때마다
     * 정해진 개수만큼만 처리하고, 다 끝나면 true를 반환한다.
     */
    private final class DestructionCursor {
        private final World world;
        private final int cx;
        private final int cz;
        private final int baseY;
        private final int height;
        private final int[] footprint;
        private final long totalCells;
        private final double destructionChance;
        private final double debrisChance;
        private final double pullStrength;
        private final double spinStrength;
        private final double liftStrength;
        private final int minWorldHeight;
        private final int maxWorldHeight;
        private long index = 0;

        DestructionCursor(World world, Location center, double radius, int height, double destructionChance,
                           double debrisChance, double pullStrength, double spinStrength, double liftStrength) {
            this.world = world;
            this.cx = center.getBlockX();
            this.cz = center.getBlockZ();
            this.baseY = center.getBlockY();
            this.height = height;
            int r = Math.min(127, (int) Math.ceil(radius));
            this.footprint = buildAngleSortedFootprint(r, radius * radius);
            this.totalCells = (long) footprint.length * height;
            this.destructionChance = destructionChance;
            this.debrisChance = debrisChance;
            this.pullStrength = pullStrength;
            this.spinStrength = spinStrength;
            this.liftStrength = liftStrength;
            this.minWorldHeight = world.getMinHeight();
            this.maxWorldHeight = world.getMaxHeight();
        }

        /** 최대 cellsPerTick개 셀을 처리한다. 이번 배치가 전부 끝났으면 true를 반환한다. */
        boolean processBudget(int cellsPerTick) {
            long processed = 0;
            while (processed < cellsPerTick && index < totalCells) {
                long i = index++;
                int footprintIndex = (int) (i / height);
                int dy = (int) (i % height);

                int packed = footprint[footprintIndex];
                int dx = ((packed >> 8) & 0xFF) - 128;
                int dz = (packed & 0xFF) - 128;

                int x = cx + dx;
                int z = cz + dz;
                int y = baseY + dy;
                if (y >= minWorldHeight && y < maxWorldHeight) {
                    processCell(x, y, z);
                }
                processed++;
            }
            return index >= totalCells;
        }

        private void processCell(int x, int y, int z) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            if (type.isAir() || type == Material.BEDROCK) {
                return;
            }
            if (random.nextDouble() >= destructionChance) {
                return;
            }

            block.setType(Material.AIR, false);
            if (random.nextDouble() < debrisChance) {
                Vector velocity = vortexVelocity(x + 0.5, z + 0.5,
                        new Location(world, cx, baseY, cz), pullStrength, spinStrength, liftStrength);
                launchDebris(world, new Location(world, x, y, z), type, velocity);
            }
        }
    }

    /**
     * 반경 r 안에서 실제 원(radiusSquared) 안에 드는 (dx,dz) 좌표들을, 중심 기준 각도(atan2) 오름차순으로 정렬해 반환한다.
     * 각 좌표는 (dx+128), (dz+128)을 상위/하위 바이트에 담아 하나의 int로 압축한다(반경 127 이하에서 유효).
     */
    private int[] buildAngleSortedFootprint(int r, double radiusSquared) {
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if ((double) dx * dx + (double) dz * dz <= radiusSquared) {
                    count++;
                }
            }
        }

        long[] sortKeys = new long[count];
        int idx = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if ((double) dx * dx + (double) dz * dz <= radiusSquared) {
                    double angle = Math.atan2(dz, dx) + Math.PI;
                    long angleKey = (long) (angle * 1_000_000);
                    int packed = ((dx + 128) << 8) | (dz + 128);
                    sortKeys[idx++] = (angleKey << 16) | (packed & 0xFFFF);
                }
            }
        }

        Arrays.sort(sortKeys);

        int[] footprint = new int[count];
        for (int i = 0; i < count; i++) {
            footprint[i] = (int) (sortKeys[i] & 0xFFFF);
        }
        return footprint;
    }

    private Vector randomHorizontalUnit() {
        double angle = random.nextDouble() * 2 * Math.PI;
        return new Vector(Math.cos(angle), 0, Math.sin(angle));
    }

    /** 각 플레이어마다 확률을 굴려서, 통과하면 그 주변 반경 안 랜덤 위치에 실제 낙뢰(피해/화재 있음)를 떨어뜨린다. */
    private void strikeLightningNearPlayers(World world, double chancePercent, double radius) {
        for (Player player : world.getPlayers()) {
            if (!PlayerFilter.isTargetable(player)) {
                continue;
            }
            if (random.nextDouble() * 100.0 >= chancePercent) {
                continue;
            }
            Location base = player.getLocation();
            double angle = random.nextDouble() * 2 * Math.PI;
            double distance = random.nextDouble() * radius;
            int x = base.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = base.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z);
            world.strikeLightning(new Location(world, x + 0.5, y, z + 0.5));
        }
    }

    /** 모두가 같은 방향으로 밀리는 돌풍 한 번을 적용한다. */
    private void applyWindGust(World world, double force) {
        double angle = random.nextDouble() * 2 * Math.PI;
        Vector push = new Vector(Math.cos(angle) * force, 0.15, Math.sin(angle) * force);
        for (Player player : world.getPlayers()) {
            if (PlayerFilter.isTargetable(player)) {
                player.setVelocity(player.getVelocity().add(push));
            }
        }
    }

    /** 지상(하늘이 뚫린 곳)에 있는 플레이어에게만 칼바람 소리를 들려준다. */
    private void playWindSoundToExposedPlayers(World world, float volume, float pitch) {
        for (Player player : world.getPlayers()) {
            if (PlayerFilter.isTargetable(player) && isExposedToSky(world, player)) {
                player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_WHIRL, volume, pitch);
            }
        }
    }

    /** 플레이어 머리 위에 하늘을 가리는 블록이 없는지 확인한다. */
    private boolean isExposedToSky(World world, Player player) {
        Location loc = player.getLocation();
        int highestY = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        return loc.getY() > highestY;
    }

    /** 반경 안 엔티티/플레이어를 중심으로 끌어당기면서 원형으로 돌리고, 일정 높이까지 띄워 올린다. */
    private void applyVortexForces(World world, Location center, double pullRadius, double pullStrength,
                                    double spinStrength, double liftStrength, double maxLiftHeight) {
        double groundY = center.getY();
        Collection<Entity> nearby = world.getNearbyEntities(center, pullRadius, maxLiftHeight + 10, pullRadius);

        for (Entity entity : nearby) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (entity instanceof Player player && !PlayerFilter.isTargetable(player)) {
                continue;
            }
            Location loc = entity.getLocation();
            double dx = loc.getX() - center.getX();
            double dz = loc.getZ() - center.getZ();
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            if (horizontalDist > pullRadius) {
                continue;
            }

            double heightAboveGround = loc.getY() - groundY;
            double lift = heightAboveGround < maxLiftHeight ? liftStrength : 0.0;

            Vector velocity = vortexVelocity(loc.getX(), loc.getZ(), center, pullStrength, spinStrength, lift);
            living.setVelocity(velocity);
            living.setFallDistance(0f);
        }
    }

    /** 주어진 지점에서 중심을 향한 인력 + 접선 방향 회전력 + 상승력을 합친 속도 벡터를 계산한다. */
    private Vector vortexVelocity(double x, double z, Location center, double pullStrength, double spinStrength, double lift) {
        double dx = x - center.getX();
        double dz = z - center.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01) {
            dist = 0.01;
        }
        double nx = dx / dist;
        double nz = dz / dist;
        double tx = -nz;
        double tz = nx;

        double vx = -nx * pullStrength + tx * spinStrength;
        double vz = -nz * pullStrength + tz * spinStrength;
        return new Vector(vx, lift, vz);
    }

    private void launchDebris(World world, Location blockLoc, Material type, Vector velocity) {
        FallingBlock debris = world.spawn(blockLoc.clone().add(0.5, 0, 0.5), FallingBlock.class, entity -> {
            entity.setBlockData(type.createBlockData());
            entity.setDropItem(false);
        });
        debris.setVelocity(velocity);
    }
}
