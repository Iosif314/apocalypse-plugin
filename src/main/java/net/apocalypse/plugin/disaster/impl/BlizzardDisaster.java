package net.apocalypse.plugin.disaster.impl;

import net.apocalypse.plugin.disaster.DangerLevel;
import net.apocalypse.plugin.disaster.Disaster;
import net.apocalypse.plugin.disaster.DisasterContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Snow;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 기온이 급락하며 눈이 내리는 재앙. 바닐라 강수는 바이옴 온도(=고도의 영향을 받음)에 따라
 * 따뜻한 곳/저고도에서는 비로만 내리기 때문에, 실제 강수와는 별개로 모든 플레이어 머리 위에
 * 눈 파티클을 직접 뿌려서 어떤 바이옴/고도에 있든 눈이 내리는 것처럼 보이게 한다.
 * 횃불/모닥불/랜턴(영혼 포함) 같은 열원 근처에 있는 플레이어는 동상 피해와 눈 쌓임에서 면제되고,
 * 하늘이 뚫린 곳의 지표수는 열원과 무관하게 얼어붙는다.
 */
public class BlizzardDisaster implements Disaster {

    /** 근처에 있으면 동상 피해와 눈 쌓임에서 면제시켜주는 열원 블록 종류. */
    private static final Set<Material> HEAT_SOURCES = EnumSet.of(
            Material.TORCH, Material.WALL_TORCH, Material.SOUL_TORCH, Material.SOUL_WALL_TORCH,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.LANTERN, Material.SOUL_LANTERN
    );

    /** 한 눈 블록에 쌓을 수 있는 층수(1~8)를 다 채우고 나면, 그 위에 새 눈 블록을 놓아 계속 쌓이게 하는 안전 상한(블록). */
    private static final int MAX_SNOW_COLUMN_HEIGHT = 32;

    /** 눈이 쌓이기 전에 파괴해서 자리를 비켜주는 낮은 자연물. 잔디/양치식물류는 두 칸짜리라 위쪽 절반도 같이 지운다. */
    private static final Set<Material> LOW_VEGETATION = EnumSet.of(
            Material.SHORT_GRASS, Material.TALL_GRASS,
            Material.FERN, Material.LARGE_FERN,
            Material.DEAD_BUSH, Material.LEAF_LITTER
    );
    private static final Set<Material> BISECTED_VEGETATION = EnumSet.of(Material.TALL_GRASS, Material.LARGE_FERN);

    @Override
    public String getId() {
        return "blizzard";
    }

    @Override
    public String getDisplayName() {
        return "한파";
    }

    @Override
    public DangerLevel getDangerLevel() {
        return DangerLevel.LEVEL_2;
    }

    @Override
    public void trigger(DisasterContext context) {
        Plugin plugin = context.plugin();
        World world = context.world();
        ConfigurationSection section = context.config();

        long durationTicks = section.getLong("duration-seconds", 120) * 20L;
        long checkIntervalTicks = Math.max(1, section.getLong("check-interval-ticks", 20));
        int snowParticleCount = Math.max(0, section.getInt("snow-particle-count", 40));
        double snowParticleRadius = Math.max(1, section.getDouble("snow-particle-radius", 5));
        int slownessAmplifier = Math.max(0, section.getInt("frostbite-slowness-amplifier", 1));
        double frostbiteDamage = Math.max(0, section.getDouble("frostbite-damage", 1.0));
        double freezeRadius = Math.max(0, section.getDouble("ice-freeze-radius", 6));
        double snowAccumulationRadius = Math.max(0, section.getDouble("snow-accumulation-radius", 6));
        int snowMaxLayers = Math.max(1, Math.min(8, section.getInt("snow-max-layers", 8)));
        double heatSourceRadius = Math.max(0, section.getDouble("heat-source-radius", 4));
        float windSoundVolume = (float) Math.max(0, section.getDouble("wind-sound-volume", 1.0));
        float windSoundPitch = (float) section.getDouble("wind-sound-pitch", 0.6);
        // 반경이 매우 커서(기본 256블록) 매 판정마다 돌리면 부하가 크므로, 눈 쌓임/얼음 얼리기 같은 무거운 지형 작업은
        // 더 뜸한 별도 주기로 돌린다. 동상 데미지/눈 파티클은 원래 주기(check-interval-ticks) 그대로 자주 갱신된다.
        long blockEffectIntervalTicks = Math.max(checkIntervalTicks, section.getLong("block-effect-interval-ticks", 100));
        // 효과가 다음 판정 전에 끊기지 않도록 체크 주기보다 살짝 여유를 둔다.
        int slownessDurationTicks = (int) (checkIntervalTicks + 20);

        new BukkitRunnable() {
            long elapsed = 0;
            long sinceBlockEffect = 0;

            @Override
            public void run() {
                elapsed += checkIntervalTicks;
                sinceBlockEffect += checkIntervalTicks;
                boolean doBlockEffects = sinceBlockEffect >= blockEffectIntervalTicks;
                if (doBlockEffects) {
                    sinceBlockEffect = 0;
                }

                for (Player player : world.getPlayers()) {
                    spawnSnow(world, player, snowParticleCount, snowParticleRadius);

                    boolean exposed = isExposedToSky(world, player);
                    boolean warm = isNearHeatSource(world, player, heatSourceRadius);

                    if (exposed && doBlockEffects) {
                        freezeNearbyWater(world, player, freezeRadius);
                        player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_WHIRL, windSoundVolume, windSoundPitch);
                    }
                    if (!warm) {
                        applyFrostbite(player, slownessDurationTicks, slownessAmplifier, frostbiteDamage);
                    }
                    // 눈은 하늘이 뚫려 있을 때만, 그리고 무거운 지형 작업 주기가 돌아왔을 때만 쌓인다.
                    // 열원 근처인지는 플레이어 위치가 아니라 각 칸(컬럼)마다 따로 판정한다(아래 accumulateSnow 참고).
                    // 다만 열원 탐색 자체는 비용 때문에 플레이어 주변(heat-source-radius)만 훑으므로,
                    // 플레이어에게서 heat-source-radius보다 멀리 떨어진 열원은 보호 범위 밖이다.
                    if (exposed && doBlockEffects) {
                        List<Block> nearbyHeatSources = findHeatSources(world, player, heatSourceRadius);
                        accumulateSnow(world, player, snowAccumulationRadius, snowMaxLayers,
                                nearbyHeatSources, heatSourceRadius);
                    }
                }

                if (elapsed >= durationTicks) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, checkIntervalTicks, checkIntervalTicks);
    }

    /** 실제 바이옴 강수와 무관하게, 플레이어 머리 위에 눈이 흩날리는 것처럼 파티클을 뿌린다. */
    private void spawnSnow(World world, Player player, int particleCount, double radius) {
        Location above = player.getLocation().add(0, 6, 0);
        world.spawnParticle(Particle.SNOWFLAKE, above, particleCount, radius, 2, radius, 0.02);
    }

    /** 플레이어에게 동상(구속 + 체력 소모)을 입힌다. 열원 근처 여부는 호출하는 쪽에서 이미 판정한다. */
    private void applyFrostbite(Player player, int slownessDurationTicks, int slownessAmplifier, double damage) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slownessDurationTicks, slownessAmplifier, true, true));
        if (damage > 0) {
            player.damage(damage);
        }
    }

    /** 플레이어 주변 지표수를 얼음으로 바꾼다. */
    private void freezeNearbyWater(World world, Player player, double radius) {
        int r = (int) Math.ceil(radius);
        int centerX = player.getLocation().getBlockX();
        int centerZ = player.getLocation().getBlockZ();
        double radiusSquared = radius * radius;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                int x = centerX + dx;
                int z = centerZ + dz;
                Block surface = world.getHighestBlockAt(x, z);
                if (surface.getType() == Material.WATER) {
                    surface.setType(Material.ICE, false);
                }
            }
        }
    }

    /**
     * 플레이어 주변의 단단한 지형 위에 실제 눈(SNOW) 블록을 한 겹씩 쌓는다. 한 블록이 maxLayersPerBlock까지
     * 다 차면, 그 위에 새 눈 블록을 놓아 눈이 계속 더 높이 쌓이게 한다(최대 {@link #MAX_SNOW_COLUMN_HEIGHT}블록 높이까지).
     * heatSources 중 하나라도 heatSourceRadius 안에 있는 칸은 건너뛴다(그 열원 주변만 눈이 안 쌓임).
     */
    private void accumulateSnow(World world, Player player, double radius, int maxLayersPerBlock,
                                 List<Block> heatSources, double heatSourceRadius) {
        int r = (int) Math.ceil(radius);
        int centerX = player.getLocation().getBlockX();
        int centerZ = player.getLocation().getBlockZ();
        double radiusSquared = radius * radius;
        double heatSourceRadiusSquared = heatSourceRadius * heatSourceRadius;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                int x = centerX + dx;
                int z = centerZ + dz;
                int surfaceY = world.getHighestBlockYAt(x, z);
                if (isNearAnyHeatSource(x, surfaceY + 1, z, heatSources, heatSourceRadiusSquared)) {
                    continue;
                }
                Block ground = world.getBlockAt(x, surfaceY, z);
                if (ground.getType() == Material.LAVA) {
                    // 한파에 용암 표면이 굳어 조약돌이 된다. 그 위에는 눈이 쌓일 수 있다.
                    ground.setType(Material.COBBLESTONE, false);
                } else if (!ground.getType().isSolid()) {
                    // 물 위에는 쌓이지 않는다(freezeNearbyWater가 먼저 얼려야 그 위에 눈이 쌓일 수 있다). 얼음 위에는 쌓인다.
                    continue;
                }

                // 이미 쌓인 눈 기둥의 꼭대기를 찾는다.
                int topY = surfaceY;
                while (topY < surfaceY + MAX_SNOW_COLUMN_HEIGHT
                        && world.getBlockAt(x, topY + 1, z).getType() == Material.SNOW) {
                    topY++;
                }

                if (topY == surfaceY) {
                    // 아직 눈이 하나도 없는 자리라면 새로 한 블록 놓는다. 낙엽/잔디 같은 낮은 자연물은 먼저 치운다.
                    Block spot = world.getBlockAt(x, surfaceY + 1, z);
                    Material spotType = spot.getType();
                    if (spotType == Material.AIR) {
                        spot.setType(Material.SNOW, false);
                    } else if (LOW_VEGETATION.contains(spotType)) {
                        clearVegetation(world, x, surfaceY + 1, z, spotType);
                        spot.setType(Material.SNOW, false);
                    }
                    continue;
                }

                Block topSnowBlock = world.getBlockAt(x, topY, z);
                Snow snow = (Snow) topSnowBlock.getBlockData();
                // 설정값과 무관하게, 실제 게임 시스템상의 한 블록당 최대 층수를 넘지 않는다.
                int trueMaxLayers = Math.min(maxLayersPerBlock, snow.getMaximumLayers());
                if (snow.getLayers() < trueMaxLayers) {
                    snow.setLayers(snow.getLayers() + 1);
                    topSnowBlock.setBlockData(snow, false);
                } else if (topY < surfaceY + MAX_SNOW_COLUMN_HEIGHT) {
                    // 이 블록은 다 찼으니, 그 위에 새 눈 블록을 놓아 눈이 계속 더 쌓이게 한다.
                    Block above = world.getBlockAt(x, topY + 1, z);
                    if (above.getType() == Material.AIR) {
                        above.setType(Material.SNOW, false);
                    }
                }
            }
        }
    }

    /** 낮은 자연물을 파괴한다. 두 칸짜리(키 큰 풀/큰 고사리)라면 위쪽 절반도 같이 지워서 블록이 공중에 뜨지 않게 한다. */
    private void clearVegetation(World world, int x, int y, int z, Material type) {
        world.getBlockAt(x, y, z).setType(Material.AIR, false);
        if (BISECTED_VEGETATION.contains(type)) {
            world.getBlockAt(x, y + 1, z).setType(Material.AIR, false);
        }
    }

    /** heatSources 중 (x,y,z)로부터 heatSourceRadiusSquared 안에 있는 것이 하나라도 있는지 확인한다. */
    private boolean isNearAnyHeatSource(int x, int y, int z, List<Block> heatSources, double heatSourceRadiusSquared) {
        for (Block heat : heatSources) {
            double dx = heat.getX() - x;
            double dy = heat.getY() - y;
            double dz = heat.getZ() - z;
            if (dx * dx + dy * dy + dz * dz <= heatSourceRadiusSquared) {
                return true;
            }
        }
        return false;
    }

    /** 플레이어 주변(3차원 반경) 안에 있는, 켜져 있는 열원(횃불/모닥불/랜턴, 영혼 포함) 블록을 모두 찾아 반환한다. */
    private List<Block> findHeatSources(World world, Player player, double radius) {
        List<Block> found = new ArrayList<>();
        int r = (int) Math.ceil(radius);
        double radiusSquared = radius * radius;
        Location center = player.getLocation();
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                        continue;
                    }
                    Block block = world.getBlockAt(centerX + dx, centerY + dy, centerZ + dz);
                    if (!HEAT_SOURCES.contains(block.getType())) {
                        continue;
                    }
                    // 모닥불류는 꺼져 있으면 열원으로 치지 않는다. 횃불/랜턴은 항상 켜진 상태만 존재한다.
                    if (block.getBlockData() instanceof Lightable lightable && !lightable.isLit()) {
                        continue;
                    }
                    found.add(block);
                }
            }
        }
        return found;
    }

    /** 플레이어 주변(3차원 반경)에 켜져 있는 열원(횃불/모닥불/랜턴, 영혼 포함)이 있는지 확인한다. */
    private boolean isNearHeatSource(World world, Player player, double radius) {
        int r = (int) Math.ceil(radius);
        double radiusSquared = radius * radius;
        Location center = player.getLocation();
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                        continue;
                    }
                    Block block = world.getBlockAt(centerX + dx, centerY + dy, centerZ + dz);
                    if (!HEAT_SOURCES.contains(block.getType())) {
                        continue;
                    }
                    // 모닥불류는 꺼져 있으면 열원으로 치지 않는다. 횃불/랜턴은 항상 켜진 상태만 존재한다.
                    if (block.getBlockData() instanceof Lightable lightable && !lightable.isLit()) {
                        continue;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** 플레이어 머리 위에 하늘을 가리는 블록이 없는지 확인한다. */
    private boolean isExposedToSky(World world, Player player) {
        Location loc = player.getLocation();
        int highestY = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        return loc.getY() > highestY;
    }
}
