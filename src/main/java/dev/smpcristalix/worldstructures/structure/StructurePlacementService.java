package dev.smpcristalix.worldstructures.structure;

import dev.smpcristalix.worldstructures.boss.MiniBossService;
import dev.smpcristalix.worldstructures.config.WorldStructuresSettings;
import dev.smpcristalix.worldstructures.loot.StructureChestService;
import dev.smpcristalix.worldstructures.mob.StructureMobService;
import dev.smpcristalix.worldstructures.runtime.StructureInstanceService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Загружает NBT-шаблоны из JAR и ставит полностью игровую структуру:
 * постройка -> сундуки -> охрана -> runtime instance -> якорь мини-босса.
 */
public final class StructurePlacementService {

    private static final StructureRotation[] ROTATIONS = StructureRotation.values();

    private final Plugin plugin;
    private final StructureMobService mobService;
    private final MiniBossService bossService;
    private final StructureChestService chestService;
    private final StructureInstanceService instanceService;
    private final Map<String, Structure> templates = new HashMap<>();
    private volatile WorldStructuresSettings settings;

    public StructurePlacementService(Plugin plugin,
                                     WorldStructuresSettings settings,
                                     StructureMobService mobService,
                                     MiniBossService bossService,
                                     StructureChestService chestService,
                                     StructureInstanceService instanceService) {
        this.plugin = plugin;
        this.settings = settings;
        this.mobService = mobService;
        this.bossService = bossService;
        this.chestService = chestService;
        this.instanceService = instanceService;
    }

    public void loadTemplates() {
        templates.clear();
        for (String structureId : settings.structures().keySet()) {
            String resource = "structures/" + structureId + ".nbt";
            try (InputStream input = plugin.getResource(resource)) {
                if (input == null) {
                    plugin.getLogger().warning("NBT шаблон не найден: " + resource);
                    continue;
                }
                Structure structure = plugin.getServer().getStructureManager().loadStructure(input);
                templates.put(structureId, structure);
            } catch (IOException exception) {
                plugin.getLogger().severe("Не удалось загрузить " + resource + ": " + exception.getMessage());
            }
        }
    }

    public void reload(WorldStructuresSettings newSettings) {
        settings = newSettings;
        loadTemplates();
    }

    public int loadedTemplateCount() {
        return templates.size();
    }

    public PlacementResult place(String structureId, Location origin) {
        String id = structureId.toLowerCase();
        WorldStructuresSettings.StructureBossSpec spec = settings.structure(id);
        if (spec == null) throw new IllegalArgumentException("Unknown structure: " + structureId);

        Structure structure = templates.get(id);
        if (structure == null) throw new IllegalStateException("NBT template is not loaded: " + id);

        Random random = ThreadLocalRandom.current();
        StructureRotation rotation = ROTATIONS[random.nextInt(ROTATIONS.length)];
        Location blockOrigin = origin.clone();
        blockOrigin.setX(Math.floor(blockOrigin.getX()));
        blockOrigin.setY(Math.floor(blockOrigin.getY()));
        blockOrigin.setZ(Math.floor(blockOrigin.getZ()));

        structure.place(blockOrigin, false, rotation, Mirror.NONE, -1, 1.0f, random);

        Bounds bounds = boundsFor(blockOrigin, structure.getSize(), rotation);
        String instanceId = id + "-" + UUID.randomUUID().toString().substring(0, 8);
        instanceService.registerInstance(instanceId, id, bounds.toData());

        int containers = markContainers(id, bounds);
        int mobs = spawnConfiguredMobs(instanceId, id, spec, bounds, random);

        Location bossLocation = findSafeSpawn(bounds.center(), bounds, random);
        String anchorId = bossService.registerAnchor(instanceId, id, bossLocation);
        return new PlacementResult(id, instanceId, rotation, bounds.sizeX(), bounds.sizeY(), bounds.sizeZ(), containers, mobs, anchorId);
    }

    public int respawnGuards(StructureInstanceService.InstanceView instance) {
        if (instance == null) return 0;
        WorldStructuresSettings.StructureBossSpec spec = settings.structure(instance.structureId());
        if (spec == null) return 0;
        Bounds bounds = Bounds.from(instance.bounds());
        World world = bounds.world;
        if (world == null) return 0;
        world.getChunkAt(bounds.center()).load();
        return spawnConfiguredMobs(instance.instanceId(), instance.structureId(), spec, bounds, ThreadLocalRandom.current());
    }

    public int resetGuards(StructureInstanceService.InstanceView instance) {
        if (instance == null) return 0;
        Bounds bounds = Bounds.from(instance.bounds());
        World world = bounds.world;
        if (world == null) return 0;

        Location center = bounds.center();
        double rx = Math.max(8.0, bounds.sizeX() / 2.0 + 8.0);
        double ry = Math.max(8.0, bounds.sizeY() / 2.0 + 8.0);
        double rz = Math.max(8.0, bounds.sizeZ() / 2.0 + 8.0);
        for (Entity entity : world.getNearbyEntities(center, rx, ry, rz)) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!mobService.isStructureMob(living) || mobService.isMiniBoss(living)) continue;
            if (instance.instanceId().equals(mobService.structureInstanceId(living))) living.remove();
        }
        return respawnGuards(instance);
    }

    private int markContainers(String structureId, Bounds bounds) {
        int count = 0;
        World world = bounds.world;
        for (int x = bounds.minX; x <= bounds.maxX; x++) {
            for (int y = bounds.minY; y <= bounds.maxY; y++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    if (world.getBlockAt(x, y, z).getState() instanceof Container container) {
                        chestService.markContainer(container, structureId);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private int spawnConfiguredMobs(String instanceId,
                                    String structureId,
                                    WorldStructuresSettings.StructureBossSpec spec,
                                    Bounds bounds,
                                    Random random) {
        int spawned = 0;
        for (WorldStructuresSettings.MobSpawnSpec mob : spec.mobs()) {
            int count = mob.minCount() == mob.maxCount()
                    ? mob.minCount()
                    : random.nextInt(mob.minCount(), mob.maxCount() + 1);
            for (int i = 0; i < count; i++) {
                Location location = findSafeSpawn(bounds.center(), bounds, random);
                try {
                    mobService.spawnStructureMob(location, mob.type(), structureId, instanceId);
                    spawned++;
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Не удалось создать " + mob.type() + " для " + structureId);
                }
            }
        }
        return spawned;
    }

    private Location findSafeSpawn(Location fallback, Bounds bounds, Random random) {
        World world = bounds.world;
        int minY = Math.max(world.getMinHeight() + 1, bounds.minY);
        int maxY = Math.min(world.getMaxHeight() - 2, bounds.maxY);

        for (int attempt = 0; attempt < 80; attempt++) {
            int x = random.nextInt(bounds.minX, bounds.maxX + 1);
            int z = random.nextInt(bounds.minZ, bounds.maxZ + 1);
            int y = random.nextInt(minY, maxY + 1);
            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);
            Block floor = world.getBlockAt(x, y - 1, z);
            if (feet.isPassable() && head.isPassable() && floor.getType().isSolid()) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }

        int x = fallback.getBlockX();
        int z = fallback.getBlockZ();
        int y = world.getHighestBlockYAt(x, z) + 1;
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private Bounds boundsFor(Location origin, BlockVector size, StructureRotation rotation) {
        int sx = Math.max(1, size.getBlockX());
        int sy = Math.max(1, size.getBlockY());
        int sz = Math.max(1, size.getBlockZ());

        int[][] corners = {
                transform(0, 0, rotation),
                transform(sx - 1, 0, rotation),
                transform(0, sz - 1, rotation),
                transform(sx - 1, sz - 1, rotation)
        };

        int minDx = Integer.MAX_VALUE;
        int maxDx = Integer.MIN_VALUE;
        int minDz = Integer.MAX_VALUE;
        int maxDz = Integer.MIN_VALUE;
        for (int[] corner : corners) {
            minDx = Math.min(minDx, corner[0]);
            maxDx = Math.max(maxDx, corner[0]);
            minDz = Math.min(minDz, corner[1]);
            maxDz = Math.max(maxDz, corner[1]);
        }

        return new Bounds(
                origin.getWorld(),
                origin.getBlockX() + minDx,
                origin.getBlockX() + maxDx,
                origin.getBlockY(),
                origin.getBlockY() + sy - 1,
                origin.getBlockZ() + minDz,
                origin.getBlockZ() + maxDz
        );
    }

    private int[] transform(int x, int z, StructureRotation rotation) {
        return switch (rotation) {
            case NONE -> new int[]{x, z};
            case CLOCKWISE_90 -> new int[]{-z, x};
            case CLOCKWISE_180 -> new int[]{-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, -x};
        };
    }

    public record PlacementResult(String structureId,
                                  String instanceId,
                                  StructureRotation rotation,
                                  int sizeX,
                                  int sizeY,
                                  int sizeZ,
                                  int containersMarked,
                                  int mobsSpawned,
                                  String bossAnchorId) {}

    private static final class Bounds {
        private final World world;
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;

        private Bounds(World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.world = world;
            this.minX = Math.min(minX, maxX);
            this.maxX = Math.max(minX, maxX);
            this.minY = Math.min(minY, maxY);
            this.maxY = Math.max(minY, maxY);
            this.minZ = Math.min(minZ, maxZ);
            this.maxZ = Math.max(minZ, maxZ);
        }

        private static Bounds from(StructureInstanceService.BoundsData data) {
            World world = data == null ? null : org.bukkit.Bukkit.getWorld(data.worldName());
            if (data == null) return new Bounds(world, 0, 0, 0, 0, 0, 0);
            return new Bounds(world, data.minX(), data.maxX(), data.minY(), data.maxY(), data.minZ(), data.maxZ());
        }

        private StructureInstanceService.BoundsData toData() {
            return new StructureInstanceService.BoundsData(
                    world.getName(), minX, maxX, minY, maxY, minZ, maxZ
            );
        }

        private Location center() {
            return new Location(
                    world,
                    (minX + maxX) / 2.0 + 0.5,
                    minY + 1.0,
                    (minZ + maxZ) / 2.0 + 0.5
            );
        }

        private int sizeX() { return maxX - minX + 1; }
        private int sizeY() { return maxY - minY + 1; }
        private int sizeZ() { return maxZ - minZ + 1; }
    }
}
