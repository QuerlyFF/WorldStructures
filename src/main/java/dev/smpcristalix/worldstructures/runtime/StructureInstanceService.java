package dev.smpcristalix.worldstructures.runtime;

import dev.smpcristalix.worldstructures.config.WorldStructuresSettings;
import dev.smpcristalix.worldstructures.mob.StructureMobService;
import dev.smpcristalix.worldstructures.structure.StructurePlacementService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Хранит конкретные экземпляры сгенерированных структур и обслуживает их зоны.
 * Через этот сервис работают предупреждения игрокам, поиск структур и респавн охраны.
 */
public final class StructureInstanceService {

    private final Plugin plugin;
    private final StructureMobService mobService;
    private final File storageFile;
    private final Map<String, StructureInstance> instances = new LinkedHashMap<>();
    private final Map<UUID, String> playerZones = new HashMap<>();
    private volatile WorldStructuresSettings settings;
    private StructurePlacementService placementService;
    private BukkitTask task;

    public StructureInstanceService(Plugin plugin,
                                    WorldStructuresSettings settings,
                                    StructureMobService mobService) {
        this.plugin = plugin;
        this.settings = settings;
        this.mobService = mobService;
        this.storageFile = new File(plugin.getDataFolder(), "structure-instances.yml");
    }

    public void attachPlacementService(StructurePlacementService placementService) {
        this.placementService = placementService;
    }

    public void start() {
        load();
        long interval = Math.max(20L, plugin.getConfig().getLong("runtime.scan-interval-seconds", 5L) * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) task.cancel();
        save();
        playerZones.clear();
    }

    public void reload(WorldStructuresSettings newSettings) {
        this.settings = newSettings;
    }

    public void registerInstance(String instanceId, String structureId, BoundsData bounds) {
        instances.put(instanceId, new StructureInstance(instanceId, structureId.toLowerCase(), bounds, 0L));
        save();
    }

    public int instanceCount() {
        return instances.size();
    }

    public InstanceView get(String instanceId) {
        StructureInstance instance = instances.get(instanceId);
        return instance == null ? null : instance.view();
    }

    public InstanceView nearest(Location location, String structureId) {
        if (location == null || location.getWorld() == null) return null;
        String filter = structureId == null ? null : structureId.toLowerCase();
        return instances.values().stream()
                .filter(instance -> instance.bounds.worldName().equals(location.getWorld().getName()))
                .filter(instance -> filter == null || instance.structureId.equals(filter))
                .min(Comparator.comparingDouble(instance -> distanceSquared2D(location, instance.bounds.center())))
                .map(StructureInstance::view)
                .orElse(null);
    }

    public boolean resetGuards(String instanceId) {
        StructureInstance instance = instances.get(instanceId);
        if (instance == null || placementService == null) return false;
        placementService.resetGuards(instance.view());
        instance.guardsClearedAtMillis = 0L;
        save();
        return true;
    }

    private void tick() {
        if (!settings.enabled()) return;
        updatePlayerZones();
        updateGuards();
    }

    private void updatePlayerZones() {
        int padding = Math.max(0, plugin.getConfig().getInt("runtime.zone-padding-blocks", 8));
        for (Player player : Bukkit.getOnlinePlayers()) {
            StructureInstance inside = findContaining(player.getLocation(), padding);
            String previous = playerZones.get(player.getUniqueId());
            String current = inside == null ? null : inside.instanceId;
            if (current == null) {
                playerZones.remove(player.getUniqueId());
                continue;
            }
            if (current.equals(previous)) continue;

            playerZones.put(player.getUniqueId(), current);
            WorldStructuresSettings.StructureBossSpec spec = settings.structure(inside.structureId);
            int danger = spec == null ? 1 : spec.dangerLevel();
            player.sendTitle("§c⚠ " + displayName(inside.structureId), "§7Опасность: §c" + roman(danger), 10, 50, 15);
            player.sendMessage("§8[§6WorldStructures§8] §7Вы вошли в опасную структуру. §fОхрана и мини-босс усилены.");
        }
    }

    private void updateGuards() {
        if (placementService == null) return;
        long now = System.currentTimeMillis();
        long respawnMillis = Math.max(60L, plugin.getConfig().getLong("runtime.guard-respawn-seconds", 7200L)) * 1000L;
        double exclusionRadius = Math.max(16.0, plugin.getConfig().getDouble("runtime.guard-respawn-player-exclusion-radius", 64.0));
        boolean dirty = false;

        for (StructureInstance instance : instances.values()) {
            World world = Bukkit.getWorld(instance.bounds.worldName());
            if (world == null || !isCenterChunkLoaded(world, instance.bounds)) continue;

            int guards = countAliveGuards(instance, world);
            if (guards > 0) {
                if (instance.guardsClearedAtMillis != 0L) {
                    instance.guardsClearedAtMillis = 0L;
                    dirty = true;
                }
                continue;
            }

            if (instance.guardsClearedAtMillis == 0L) {
                instance.guardsClearedAtMillis = now;
                dirty = true;
                continue;
            }

            if (now - instance.guardsClearedAtMillis < respawnMillis) continue;
            Location center = instance.bounds.center();
            if (hasNearbyPlayer(world, center, exclusionRadius)) continue;

            placementService.respawnGuards(instance.view());
            instance.guardsClearedAtMillis = 0L;
            dirty = true;
        }

        if (dirty) save();
    }

    private int countAliveGuards(StructureInstance instance, World world) {
        BoundsData b = instance.bounds;
        Location center = b.center();
        double radiusX = Math.max(8.0, (b.maxX() - b.minX()) / 2.0 + 8.0);
        double radiusY = Math.max(8.0, (b.maxY() - b.minY()) / 2.0 + 8.0);
        double radiusZ = Math.max(8.0, (b.maxZ() - b.minZ()) / 2.0 + 8.0);
        int count = 0;
        for (Entity entity : world.getNearbyEntities(center, radiusX, radiusY, radiusZ)) {
            if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) continue;
            if (!mobService.isStructureMob(living) || mobService.isMiniBoss(living) || mobService.isSummonedMob(living)) continue;
            if (instance.instanceId.equals(mobService.structureInstanceId(living))) count++;
        }
        return count;
    }

    private StructureInstance findContaining(Location location, int padding) {
        if (location == null || location.getWorld() == null) return null;
        for (StructureInstance instance : instances.values()) {
            BoundsData b = instance.bounds;
            if (!b.worldName().equals(location.getWorld().getName())) continue;
            double x = location.getX();
            double y = location.getY();
            double z = location.getZ();
            if (x >= b.minX() - padding && x <= b.maxX() + padding
                    && y >= b.minY() - padding && y <= b.maxY() + padding
                    && z >= b.minZ() - padding && z <= b.maxZ() + padding) {
                return instance;
            }
        }
        return null;
    }

    private boolean isCenterChunkLoaded(World world, BoundsData bounds) {
        Location center = bounds.center();
        return world.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4);
    }

    private boolean hasNearbyPlayer(World world, Location center, double radius) {
        double max = radius * radius;
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= max) return true;
        }
        return false;
    }

    private double distanceSquared2D(Location from, Location to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return dx * dx + dz * dz;
    }

    private void load() {
        instances.clear();
        if (!storageFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
        ConfigurationSection root = yaml.getConfigurationSection("instances");
        if (root == null) return;

        for (String id : root.getKeys(false)) {
            String path = "instances." + id;
            String world = yaml.getString(path + ".world");
            String structure = yaml.getString(path + ".structure-id");
            if (world == null || structure == null) continue;
            BoundsData bounds = new BoundsData(
                    world,
                    yaml.getInt(path + ".min-x"), yaml.getInt(path + ".max-x"),
                    yaml.getInt(path + ".min-y"), yaml.getInt(path + ".max-y"),
                    yaml.getInt(path + ".min-z"), yaml.getInt(path + ".max-z")
            );
            instances.put(id, new StructureInstance(
                    id, structure, bounds, yaml.getLong(path + ".guards-cleared-at-ms", 0L)
            ));
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (StructureInstance instance : instances.values()) {
            String path = "instances." + instance.instanceId;
            BoundsData b = instance.bounds;
            yaml.set(path + ".structure-id", instance.structureId);
            yaml.set(path + ".world", b.worldName());
            yaml.set(path + ".min-x", b.minX());
            yaml.set(path + ".max-x", b.maxX());
            yaml.set(path + ".min-y", b.minY());
            yaml.set(path + ".max-y", b.maxY());
            yaml.set(path + ".min-z", b.minZ());
            yaml.set(path + ".max-z", b.maxZ());
            yaml.set(path + ".guards-cleared-at-ms", instance.guardsClearedAtMillis);
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Не удалось создать папку WorldStructures");
            }
            yaml.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Не удалось сохранить structure-instances.yml: " + exception.getMessage());
        }
    }

    private String displayName(String id) {
        return switch (id) {
            case "campsite" -> "Лагерь налётчиков";
            case "windmill" -> "Захваченная мельница";
            case "graveyard" -> "Проклятое кладбище";
            case "lighthouse" -> "Затопленный маяк";
            case "ship" -> "Проклятый корабль";
            case "castle" -> "Крепость налётчиков";
            case "ice_castle" -> "Ледяной замок";
            case "observatory" -> "Забытая обсерватория";
            default -> id;
        };
    }

    private String roman(int value) {
        return switch (Math.max(1, Math.min(5, value))) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "V";
        };
    }

    public record BoundsData(String worldName, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        public Location center() {
            World world = Bukkit.getWorld(worldName);
            if (world == null) return new Location(null, (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
            return new Location(world, (minX + maxX) / 2.0 + 0.5, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0 + 0.5);
        }
    }

    public record InstanceView(String instanceId, String structureId, BoundsData bounds, long guardsClearedAtMillis) {}

    private static final class StructureInstance {
        private final String instanceId;
        private final String structureId;
        private final BoundsData bounds;
        private long guardsClearedAtMillis;

        private StructureInstance(String instanceId, String structureId, BoundsData bounds, long guardsClearedAtMillis) {
            this.instanceId = instanceId;
            this.structureId = structureId;
            this.bounds = bounds;
            this.guardsClearedAtMillis = guardsClearedAtMillis;
        }

        private InstanceView view() {
            return new InstanceView(instanceId, structureId, bounds, guardsClearedAtMillis);
        }
    }
}
