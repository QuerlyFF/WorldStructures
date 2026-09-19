package dev.smpcristalix.worldstructures.generation;

import dev.smpcristalix.worldstructures.config.WorldStructuresSettings;
import dev.smpcristalix.worldstructures.structure.StructurePlacementService;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Детерминированно размещает наши NBT-структуры только в новых чанках.
 * На один регион выбирается максимум одна кандидатная точка.
 */
public final class NaturalStructureGenerationListener implements Listener {

    private final Plugin plugin;
    private final StructurePlacementService placementService;
    private final File storageFile;
    private final Set<String> generatedRegions = new HashSet<>();

    private volatile WorldStructuresSettings worldStructuresSettings;
    private volatile GenerationSpec generation;
    private volatile Map<String, StructureGenerationSpec> structures;
    private boolean placing;

    public NaturalStructureGenerationListener(Plugin plugin,
                                               WorldStructuresSettings worldStructuresSettings,
                                               StructurePlacementService placementService) {
        this.plugin = plugin;
        this.worldStructuresSettings = worldStructuresSettings;
        this.placementService = placementService;
        this.storageFile = new File(plugin.getDataFolder(), "generated-structures.yml");
        readConfig();
        loadGeneratedRegions();
    }

    public void reload(WorldStructuresSettings newSettings) {
        this.worldStructuresSettings = newSettings;
        readConfig();
    }

    public void stop() {
        saveGeneratedRegions();
    }

    public int generatedRegionCount() {
        return generatedRegions.size();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk() || placing) return;

        GenerationSpec current = generation;
        if (!worldStructuresSettings.enabled() || !current.enabled()) return;

        World world = event.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return;
        if (!current.worlds().isEmpty() && !current.worlds().contains(world.getName())) return;

        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        Candidate candidate = candidateFor(world, chunkX, chunkZ, current);
        if (candidate == null || candidate.chunkX() != chunkX || candidate.chunkZ() != chunkZ) return;
        if (generatedRegions.contains(candidate.regionKey())) return;

        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> generate(candidate, world),
                current.delayTicks()
        );
    }

    private Candidate candidateFor(World world, int chunkX, int chunkZ, GenerationSpec spec) {
        int regionSize = spec.regionSizeChunks();
        int regionX = Math.floorDiv(chunkX, regionSize);
        int regionZ = Math.floorDiv(chunkZ, regionSize);
        long seed = mixedSeed(world.getSeed(), regionX, regionZ, spec.salt());
        Random random = new Random(seed);

        if (random.nextDouble() >= spec.spawnChancePerRegion()) return null;

        int margin = Math.min(spec.marginChunks(), Math.max(0, (regionSize - 1) / 2));
        int span = Math.max(1, regionSize - margin * 2);
        int candidateX = regionX * regionSize + margin + random.nextInt(span);
        int candidateZ = regionZ * regionSize + margin + random.nextInt(span);
        String regionKey = world.getName() + ":" + regionX + ":" + regionZ;
        return new Candidate(regionKey, regionX, regionZ, candidateX, candidateZ, seed);
    }

    private void generate(Candidate candidate, World world) {
        if (generatedRegions.contains(candidate.regionKey()) || placing) return;

        // ChunkLoadEvent был новым чанком, значит право на попытку уже получено.
        // Если игрок успел уйти за delayTicks и чанк выгрузился, не теряем регион навсегда:
        // кратко подгружаем тот же уже-сгенерированный кандидатный чанк и завершаем попытку.
        if (!world.isChunkLoaded(candidate.chunkX(), candidate.chunkZ())) {
            world.getChunkAt(candidate.chunkX(), candidate.chunkZ()).load();
        }

        GenerationSpec current = generation;
        int x = candidate.chunkX() * 16 + 8;
        int z = candidate.chunkZ() * 16 + 8;

        Location spawn = world.getSpawnLocation();
        double minDistance = current.minDistanceFromSpawnBlocks();
        double dx = x + 0.5 - spawn.getX();
        double dz = z + 0.5 - spawn.getZ();
        if (dx * dx + dz * dz < minDistance * minDistance) return;

        boolean water = isWaterSurface(world, x, z);
        int y = water
                ? world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE) + 1
                : landSurfaceY(world, x, z);

        List<Map.Entry<String, StructureGenerationSpec>> candidates = structures.entrySet().stream()
                .filter(entry -> entry.getValue().enabled())
                .filter(entry -> entry.getValue().weight() > 0)
                .filter(entry -> passesStructureChance(candidate, entry.getKey(), entry.getValue()))
                .filter(entry -> BiomePlacementRules.matchesBiome(
                        world, x, y, z, entry.getValue().allowedBiomes()))
                .filter(entry -> BiomePlacementRules.matchesHeight(
                        y, entry.getValue().minY(), entry.getValue().maxY()))
                .filter(entry -> matchesPlacement(
                        world, x, z, water, current, entry.getKey(), entry.getValue()))
                .toList();
        if (candidates.isEmpty()) return;

        Random random = new Random(candidate.seed() ^ 0x6A09E667F3BCC909L);
        String structureId = weightedChoice(candidates, random);
        if (structureId == null) return;

        try {
            placing = true;
            StructurePlacementService.PlacementResult result = placementService.place(
                    structureId,
                    new Location(world, x, y, z)
            );
            generatedRegions.add(candidate.regionKey());
            saveGeneratedRegions();
            plugin.getLogger().info(
                    "Сгенерирована структура " + result.structureId()
                            + " в " + world.getName()
                            + " [" + x + ", " + y + ", " + z + "]"
            );
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Не удалось сгенерировать " + structureId + ": " + exception.getMessage());
        } finally {
            placing = false;
        }
    }

    private boolean passesStructureChance(Candidate candidate,
                                          String structureId,
                                          StructureGenerationSpec spec) {
        long salt = ((long) structureId.hashCode() << 32) ^ structureId.hashCode();
        Random random = new Random(candidate.seed() ^ salt ^ 0xBB67AE8584CAA73BL);
        return random.nextDouble() < spec.chance();
    }

    private boolean matchesPlacement(World world,
                                     int x,
                                     int z,
                                     boolean water,
                                     GenerationSpec current,
                                     String structureId,
                                     StructureGenerationSpec spec) {
        int templateRadius = placementService.templateHorizontalRadius(structureId);
        int sampleRadius = Math.max(current.terrainSampleRadius(), templateRadius + 2);

        return switch (spec.placement()) {
            case WATER -> water && isOpenWaterArea(world, x, z, sampleRadius);
            case LAND -> !water && isAcceptableLand(
                    world, x, z, sampleRadius, current.maxHeightDifference());
            case COAST -> !water
                    && isAcceptableLand(world, x, z, sampleRadius, current.maxHeightDifference())
                    && hasNearbyWater(world, x, z, spec.coastSearchRadius());
        };
    }

    private int landSurfaceY(World world, int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
    }

    private boolean isWaterSurface(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        return world.getBlockAt(x, y, z).getType() == Material.WATER;
    }

    private boolean isOpenWaterArea(World world, int centerX, int centerZ, int radius) {
        int checks = 0;
        int waterChecks = 0;
        int step = Math.max(4, radius / 2);
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                checks++;
                if (isWaterSurface(world, centerX + dx, centerZ + dz)) waterChecks++;
            }
        }
        return checks > 0 && waterChecks >= Math.ceil(checks * 0.75);
    }

    private boolean hasNearbyWater(World world, int centerX, int centerZ, int radius) {
        int effectiveRadius = Math.max(8, radius);
        int step = 6;
        for (int distance = step; distance <= effectiveRadius; distance += step) {
            for (int dx = -distance; dx <= distance; dx += step) {
                if (isWaterSurface(world, centerX + dx, centerZ - distance)
                        || isWaterSurface(world, centerX + dx, centerZ + distance)) {
                    return true;
                }
            }
            for (int dz = -distance + step; dz <= distance - step; dz += step) {
                if (isWaterSurface(world, centerX - distance, centerZ + dz)
                        || isWaterSurface(world, centerX + distance, centerZ + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAcceptableLand(World world, int centerX, int centerZ, int radius, int maxDifference) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int step = Math.max(4, radius / 2);

        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int x = centerX + dx;
                int z = centerZ + dz;
                int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Material floor = world.getBlockAt(x, y, z).getType();
                if (floor == Material.WATER || floor == Material.LAVA || !floor.isSolid()) return false;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        return maxY - minY <= maxDifference;
    }

    private String weightedChoice(List<Map.Entry<String, StructureGenerationSpec>> candidates, Random random) {
        int total = candidates.stream().mapToInt(entry -> entry.getValue().weight()).sum();
        if (total <= 0) return null;

        int roll = random.nextInt(total);
        for (Map.Entry<String, StructureGenerationSpec> entry : candidates) {
            roll -= entry.getValue().weight();
            if (roll < 0) return entry.getKey();
        }
        return candidates.getLast().getKey();
    }

    private void readConfig() {
        FileConfiguration config = plugin.getConfig();
        boolean enabled = config.getBoolean("generation.enabled", true);
        int region = Math.max(16, config.getInt("generation.region-size-chunks", 24));
        int margin = Math.max(0, config.getInt("generation.margin-chunks", 10));
        double chance = probability(config.getDouble("generation.spawn-chance-per-region", 0.60));
        double minSpawnDistance = Math.max(0.0, config.getDouble("generation.min-distance-from-spawn-blocks", 500.0));
        long delayTicks = Math.max(1L, config.getLong("generation.place-delay-ticks", 10L));
        int sampleRadius = Math.max(4, config.getInt("generation.terrain-sample-radius", 12));
        int maxHeightDifference = Math.max(0, config.getInt("generation.max-height-difference", 7));
        long salt = config.getLong("generation.salt", 918273645L);
        Set<String> worlds = new HashSet<>(config.getStringList("generation.worlds"));
        generation = new GenerationSpec(
                enabled, region, margin, chance, minSpawnDistance, delayTicks,
                sampleRadius, maxHeightDifference, salt, Set.copyOf(worlds)
        );

        Map<String, StructureGenerationSpec> parsed = new LinkedHashMap<>();
        for (String structureId : worldStructuresSettings.structures().keySet()) {
            String root = "structures." + structureId + ".generation";
            boolean structureEnabled = config.getBoolean(root + ".enabled", true);
            int weight = Math.max(0, config.getInt(root + ".weight", 10));
            double structureChance = probability(config.getDouble(root + ".chance", 1.0));
            PlacementKind placement = parsePlacement(config.getString(root + ".placement", "LAND"));
            int minY = config.getInt(root + ".min-y", Integer.MIN_VALUE);
            int maxY = config.getInt(root + ".max-y", Integer.MAX_VALUE);
            int coastSearchRadius = Math.max(8, config.getInt(root + ".coast-search-radius", 36));
            Set<String> biomes = config.getStringList(root + ".biomes").stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

            parsed.put(structureId, new StructureGenerationSpec(
                    structureEnabled,
                    weight,
                    structureChance,
                    placement,
                    biomes,
                    Math.min(minY, maxY),
                    Math.max(minY, maxY),
                    coastSearchRadius
            ));
        }
        // Map.copyOf не обещает порядок итерации. Для детерминированного weighted choice
        // сохраняем порядок config.yml через LinkedHashMap.
        structures = Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
    }

    private PlacementKind parsePlacement(String raw) {
        try {
            return PlacementKind.valueOf(raw == null ? "LAND" : raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PlacementKind.LAND;
        }
    }

    private long mixedSeed(long worldSeed, int regionX, int regionZ, long salt) {
        long value = worldSeed ^ salt;
        value ^= (long) regionX * 341873128712L;
        value ^= (long) regionZ * 132897987541L;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return value;
    }

    private double probability(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private void loadGeneratedRegions() {
        generatedRegions.clear();
        if (!storageFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
        generatedRegions.addAll(yaml.getStringList("generated-regions"));
    }

    private void saveGeneratedRegions() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("generated-regions", new ArrayList<>(generatedRegions));
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Не удалось создать папку WorldStructures");
            }
            yaml.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Не удалось сохранить generated-structures.yml: " + exception.getMessage());
        }
    }

    private enum PlacementKind {
        LAND,
        WATER,
        COAST
    }

    private record GenerationSpec(boolean enabled,
                                  int regionSizeChunks,
                                  int marginChunks,
                                  double spawnChancePerRegion,
                                  double minDistanceFromSpawnBlocks,
                                  long delayTicks,
                                  int terrainSampleRadius,
                                  int maxHeightDifference,
                                  long salt,
                                  Set<String> worlds) {}

    private record StructureGenerationSpec(boolean enabled,
                                           int weight,
                                           double chance,
                                           PlacementKind placement,
                                           Set<String> allowedBiomes,
                                           int minY,
                                           int maxY,
                                           int coastSearchRadius) {}

    private record Candidate(String regionKey,
                             int regionX,
                             int regionZ,
                             int chunkX,
                             int chunkZ,
                             long seed) {}
}
