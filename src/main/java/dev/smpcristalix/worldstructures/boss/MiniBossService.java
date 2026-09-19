package dev.smpcristalix.worldstructures.boss;

import dev.smpcristalix.worldstructures.config.WorldStructuresSettings;
import dev.smpcristalix.worldstructures.mob.StructureMobService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Управляет мини-боссами каждой структуры: респавном, способностями, BossBar и анти-уводом.
 */
public final class MiniBossService {

    private final Plugin plugin;
    private final StructureMobService mobService;
    private final File storageFile;
    private final Map<String, BossAnchor> anchors = new LinkedHashMap<>();
    private final Map<UUID, BossRuntime> runtimes = new HashMap<>();
    private volatile WorldStructuresSettings settings;
    private BukkitTask task;

    public MiniBossService(Plugin plugin, WorldStructuresSettings settings, StructureMobService mobService) {
        this.plugin = plugin;
        this.settings = settings;
        this.mobService = mobService;
        this.storageFile = new File(plugin.getDataFolder(), "boss-anchors.yml");
    }

    public void start() {
        loadAnchors();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (BossRuntime runtime : runtimes.values()) runtime.bossBar.removeAll();
        saveAnchors();
        runtimes.clear();
    }

    public void reload(WorldStructuresSettings newSettings) {
        this.settings = newSettings;
    }

    public String registerAnchor(String structureId, Location location) {
        String id = structureId.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        return registerAnchor(id, structureId, location);
    }

    public String registerAnchor(String instanceId, String structureId, Location location) {
        String normalized = structureId.toLowerCase();
        WorldStructuresSettings.StructureBossSpec structure = settings.structure(normalized);
        if (structure == null) throw new IllegalArgumentException("Unknown structure: " + structureId);

        BossAnchor anchor = new BossAnchor(
                instanceId,
                normalized,
                location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                structure.bossType(), 0L, null
        );
        anchors.put(instanceId, anchor);
        saveAnchors();
        spawnBoss(anchor, true);
        return instanceId;
    }

    public int anchorCount() { return anchors.size(); }

    public int aliveBossCount() {
        int count = 0;
        for (BossAnchor anchor : anchors.values()) if (resolveLivingBoss(anchor) != null) count++;
        return count;
    }

    public BossAnchorInfo nearest(Location location, String structureId) {
        if (location == null || location.getWorld() == null) return null;
        String filter = structureId == null ? null : structureId.toLowerCase();
        return anchors.values().stream()
                .filter(anchor -> anchor.worldName.equals(location.getWorld().getName()))
                .filter(anchor -> filter == null || anchor.structureId.equals(filter))
                .min(Comparator.comparingDouble(anchor -> distanceSquared2D(location, anchor.location(location.getWorld()))))
                .map(this::info)
                .orElse(null);
    }

    public boolean forceRespawn(String anchorId) {
        BossAnchor anchor = anchors.get(anchorId);
        if (anchor == null) return false;
        removeCurrentBoss(anchor);
        anchor.lastDeathEpochMillis = 0L;
        spawnBoss(anchor, true);
        saveAnchors();
        return true;
    }

    public boolean forceRemove(String anchorId) {
        BossAnchor anchor = anchors.get(anchorId);
        if (anchor == null) return false;
        removeCurrentBoss(anchor);
        anchor.lastDeathEpochMillis = System.currentTimeMillis();
        saveAnchors();
        return true;
    }

    public void onBossDeath(LivingEntity boss) {
        BossAnchor anchor = findAnchorByBoss(boss.getUniqueId());
        if (anchor == null) return;
        BossRuntime runtime = runtimes.remove(boss.getUniqueId());
        if (runtime != null) runtime.bossBar.removeAll();
        anchor.lastDeathEpochMillis = System.currentTimeMillis();
        anchor.currentBossId = null;
        saveAnchors();
    }

    private void tick() {
        if (!settings.enabled()) return;
        long now = System.currentTimeMillis();
        long respawnMillis = settings.boss().respawnTicks() * 50L;

        for (BossAnchor anchor : new ArrayList<>(anchors.values())) {
            LivingEntity boss = resolveLivingBoss(anchor);
            if (boss != null) {
                BossRuntime runtime = runtimes.computeIfAbsent(
                        boss.getUniqueId(), ignored -> createRuntime(anchor, boss, now)
                );
                enforceLeash(anchor, boss);
                updateBossBar(anchor, boss, runtime);
                if (now >= runtime.nextAbilityAtMillis) {
                    castAbility(anchor, boss, runtime);
                    runtime.nextAbilityAtMillis = now + randomCooldownMillis();
                }
                continue;
            }

            if (anchor.currentBossId != null && !isAnchorChunkLoaded(anchor)) {
                BossRuntime runtime = runtimes.remove(anchor.currentBossId);
                if (runtime != null) runtime.bossBar.removeAll();
                continue;
            }

            if (anchor.currentBossId != null) {
                BossRuntime runtime = runtimes.remove(anchor.currentBossId);
                if (runtime != null) runtime.bossBar.removeAll();
                anchor.currentBossId = null;
                saveAnchors();
            }

            if (anchor.lastDeathEpochMillis == 0L || now - anchor.lastDeathEpochMillis >= respawnMillis) {
                spawnBoss(anchor, false);
            }
        }
    }

    private void enforceLeash(BossAnchor anchor, LivingEntity boss) {
        World world = boss.getWorld();
        Location home = anchor.location(world);
        double radius = Math.max(24.0, plugin.getConfig().getDouble("runtime.boss-leash-radius", 64.0));
        if (boss.getLocation().distanceSquared(home) <= radius * radius) return;

        boss.teleport(home);
        AttributeInstance health = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (health != null) {
            double fraction = Math.max(0.0, Math.min(1.0,
                    plugin.getConfig().getDouble("runtime.boss-return-heal-fraction", 0.25)));
            boss.setHealth(Math.min(health.getValue(), boss.getHealth() + health.getValue() * fraction));
        }
        boss.getWorld().strikeLightningEffect(home);
    }

    private void updateBossBar(BossAnchor anchor, LivingEntity boss, BossRuntime runtime) {
        AttributeInstance maxHealth = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double max = maxHealth == null ? Math.max(1.0, boss.getHealth()) : Math.max(1.0, maxHealth.getValue());
        runtime.bossBar.setProgress(Math.max(0.0, Math.min(1.0, boss.getHealth() / max)));
        runtime.bossBar.setTitle(boss.getCustomName() == null ? "§4☠ Мини-босс" : boss.getCustomName());

        double radius = Math.max(16.0, plugin.getConfig().getDouble("runtime.bossbar-radius", 48.0));
        double radiusSquared = radius * radius;
        for (Player player : boss.getWorld().getPlayers()) {
            boolean nearby = player.getLocation().distanceSquared(boss.getLocation()) <= radiusSquared;
            if (nearby && !runtime.bossBar.getPlayers().contains(player)) runtime.bossBar.addPlayer(player);
            if (!nearby && runtime.bossBar.getPlayers().contains(player)) runtime.bossBar.removePlayer(player);
        }
        for (Player player : new ArrayList<>(runtime.bossBar.getPlayers())) {
            if (!player.isOnline() || !player.getWorld().equals(boss.getWorld())) runtime.bossBar.removePlayer(player);
        }
    }

    private void spawnBoss(BossAnchor anchor, boolean force) {
        if (!force && resolveLivingBoss(anchor) != null) return;
        World world = Bukkit.getWorld(anchor.worldName);
        if (world == null) return;

        Location location = anchor.location(world);
        world.getChunkAt(location).load();
        Entity spawned = world.spawnEntity(location, anchor.entityType);
        if (!(spawned instanceof LivingEntity boss)) {
            spawned.remove();
            plugin.getLogger().warning("Boss type " + anchor.entityType + " is not LivingEntity for " + anchor.structureId);
            return;
        }

        mobService.prepareMiniBoss(boss, anchor.structureId, anchor.id);
        boss.setCustomName("§cМини-босс §7[§f" + anchor.structureId + "§7]");
        boss.setCustomNameVisible(true);
        anchor.currentBossId = boss.getUniqueId();
        runtimes.put(boss.getUniqueId(), createRuntime(anchor, boss, System.currentTimeMillis()));
        saveAnchors();
    }

    private BossRuntime createRuntime(BossAnchor anchor, LivingEntity boss, long now) {
        WorldStructuresSettings.StructureBossSpec spec = settings.structure(anchor.structureId);
        List<BossAbility> pool = spec == null ? List.of(BossAbility.KNOCKBACK) : spec.abilities();
        BossBar bossBar = Bukkit.createBossBar(
                boss.getCustomName() == null ? "§4☠ Мини-босс" : boss.getCustomName(),
                BarColor.RED,
                BarStyle.SEGMENTED_10
        );
        return new BossRuntime(selectAbilities(pool), now + randomCooldownMillis(), bossBar);
    }

    private List<BossAbility> selectAbilities(List<BossAbility> pool) {
        if (pool.isEmpty()) return List.of(BossAbility.KNOCKBACK);
        List<BossAbility> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled);
        WorldStructuresSettings.BossSpec boss = settings.boss();
        int min = Math.min(boss.abilitiesMin(), boss.abilitiesMax());
        int max = Math.max(boss.abilitiesMin(), boss.abilitiesMax());
        int requested = ThreadLocalRandom.current().nextInt(min, max + 1);
        int count = Math.max(1, Math.min(requested, shuffled.size()));
        return List.copyOf(shuffled.subList(0, count));
    }

    private void castAbility(BossAnchor anchor, LivingEntity boss, BossRuntime runtime) {
        if (runtime.abilities.isEmpty()) return;
        BossAbility ability = runtime.abilities.get(ThreadLocalRandom.current().nextInt(runtime.abilities.size()));
        switch (ability) {
            case SUMMON_MINIONS -> summonMinions(anchor, boss);
            case KNOCKBACK -> knockback(boss);
            case REGEN_AURA -> regenerationAura(boss);
            case RANDOM_DEBUFF -> randomDebuff(boss);
        }
    }

    private void summonMinions(BossAnchor anchor, LivingEntity boss) {
        WorldStructuresSettings.BossSpec config = settings.boss();
        int alive = countAliveMinions(boss);
        if (alive >= config.maxAliveMinions()) return;

        RandomGenerator random = ThreadLocalRandom.current();
        int desired = random.nextInt(Math.min(config.summonMin(), config.summonMax()),
                Math.max(config.summonMin(), config.summonMax()) + 1);
        int count = Math.min(desired, config.maxAliveMinions() - alive);

        for (int i = 0; i < count; i++) {
            Location spawn = findNearbySpawn(boss.getLocation(), config.summonRadius(), random);
            Entity entity = boss.getWorld().spawnEntity(spawn, anchor.entityType);
            if (entity instanceof LivingEntity minion) {
                mobService.prepareSummonedMinion(minion, anchor.structureId, anchor.id, boss.getUniqueId());
            } else entity.remove();
        }
    }

    private int countAliveMinions(LivingEntity boss) {
        int count = 0;
        double radius = Math.max(16.0, settings.boss().summonRadius() * 4.0);
        for (Entity entity : boss.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity living && mobService.isSummonedBy(living, boss.getUniqueId())) count++;
        }
        return count;
    }

    private Location findNearbySpawn(Location center, double radius, RandomGenerator random) {
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            double distance = random.nextDouble(1.5, Math.max(1.6, radius));
            Location candidate = center.clone().add(Math.cos(angle) * distance, 0.0, Math.sin(angle) * distance);
            if (candidate.getBlock().isPassable() && candidate.clone().add(0, 1, 0).getBlock().isPassable()) return candidate;
        }
        return center.clone().add(1.0, 0.0, 0.0);
    }

    private void knockback(LivingEntity boss) {
        WorldStructuresSettings.BossSpec config = settings.boss();
        double radius = config.knockbackRadius();
        for (Player player : boss.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(boss.getLocation()) > radius * radius) continue;
            Vector direction = player.getLocation().toVector().subtract(boss.getLocation().toVector());
            direction.setY(0.0);
            if (direction.lengthSquared() < 0.01) direction = new Vector(1, 0, 0);
            direction.normalize().multiply(config.knockbackHorizontal());
            direction.setY(config.knockbackVertical());
            player.setVelocity(direction);
        }
    }

    private void regenerationAura(LivingEntity boss) {
        WorldStructuresSettings.BossSpec config = settings.boss();
        int duration = ThreadLocalRandom.current().nextInt(
                Math.min(config.regenDurationMinTicks(), config.regenDurationMaxTicks()),
                Math.max(config.regenDurationMinTicks(), config.regenDurationMaxTicks()) + 1
        );
        PotionEffect effect = new PotionEffect(PotionEffectType.REGENERATION, duration,
                config.regenAmplifier(), false, true, true);
        boss.addPotionEffect(effect);
        double radius = config.regenRadius();
        for (Entity entity : boss.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity living && mobService.isStructureMob(living)) living.addPotionEffect(effect);
        }
    }

    private void randomDebuff(LivingEntity boss) {
        WorldStructuresSettings.BossSpec config = settings.boss();
        int duration = ThreadLocalRandom.current().nextInt(
                Math.min(config.debuffDurationMinTicks(), config.debuffDurationMaxTicks()),
                Math.max(config.debuffDurationMinTicks(), config.debuffDurationMaxTicks()) + 1
        );
        PotionEffectType[] effects = {
                PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.POISON,
                PotionEffectType.BLINDNESS, PotionEffectType.WITHER
        };
        double radius = config.debuffRadius();
        RandomGenerator random = ThreadLocalRandom.current();
        for (Player player : boss.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(boss.getLocation()) > radius * radius) continue;
            PotionEffectType type = effects[random.nextInt(effects.length)];
            int amplifier = (type == PotionEffectType.SLOWNESS || type == PotionEffectType.WEAKNESS) ? 1 : 0;
            player.addPotionEffect(new PotionEffect(type, duration, amplifier, false, true, true));
        }
    }

    private long randomCooldownMillis() {
        WorldStructuresSettings.BossSpec boss = settings.boss();
        int min = Math.min(boss.cooldownMinTicks(), boss.cooldownMaxTicks());
        int max = Math.max(boss.cooldownMinTicks(), boss.cooldownMaxTicks());
        return ThreadLocalRandom.current().nextInt(min, max + 1) * 50L;
    }

    private LivingEntity resolveLivingBoss(BossAnchor anchor) {
        if (anchor.currentBossId == null) return null;
        Entity entity = Bukkit.getEntity(anchor.currentBossId);
        return entity instanceof LivingEntity living && living.isValid() && !living.isDead() ? living : null;
    }

    private boolean isAnchorChunkLoaded(BossAnchor anchor) {
        World world = Bukkit.getWorld(anchor.worldName);
        if (world == null) return false;
        return world.isChunkLoaded(((int) Math.floor(anchor.x)) >> 4, ((int) Math.floor(anchor.z)) >> 4);
    }

    private BossAnchor findAnchorByBoss(UUID bossId) {
        for (BossAnchor anchor : anchors.values()) if (bossId.equals(anchor.currentBossId)) return anchor;
        return null;
    }

    private void removeCurrentBoss(BossAnchor anchor) {
        if (anchor.currentBossId == null) return;
        BossRuntime runtime = runtimes.remove(anchor.currentBossId);
        if (runtime != null) runtime.bossBar.removeAll();
        Entity entity = Bukkit.getEntity(anchor.currentBossId);
        if (entity != null) entity.remove();
        anchor.currentBossId = null;
    }

    private BossAnchorInfo info(BossAnchor anchor) {
        World world = Bukkit.getWorld(anchor.worldName);
        Location location = world == null ? null : anchor.location(world);
        return new BossAnchorInfo(anchor.id, anchor.structureId, anchor.worldName, location,
                resolveLivingBoss(anchor) != null, anchor.lastDeathEpochMillis);
    }

    private double distanceSquared2D(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private void loadAnchors() {
        anchors.clear();
        if (!storageFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
        ConfigurationSection root = yaml.getConfigurationSection("anchors");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            String path = "anchors." + id;
            try {
                EntityType type = EntityType.valueOf(yaml.getString(path + ".entity-type", "VINDICATOR"));
                String uuidRaw = yaml.getString(path + ".current-boss");
                UUID current = uuidRaw == null || uuidRaw.isBlank() ? null : UUID.fromString(uuidRaw);
                anchors.put(id, new BossAnchor(
                        id,
                        yaml.getString(path + ".structure-id", "unknown"),
                        yaml.getString(path + ".world", "world"),
                        yaml.getDouble(path + ".x"), yaml.getDouble(path + ".y"), yaml.getDouble(path + ".z"),
                        type, yaml.getLong(path + ".last-death-epoch-ms", 0L), current
                ));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Не удалось загрузить boss anchor " + id);
            }
        }
    }

    private void saveAnchors() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (BossAnchor anchor : anchors.values()) {
            String path = "anchors." + anchor.id;
            yaml.set(path + ".structure-id", anchor.structureId);
            yaml.set(path + ".world", anchor.worldName);
            yaml.set(path + ".x", anchor.x);
            yaml.set(path + ".y", anchor.y);
            yaml.set(path + ".z", anchor.z);
            yaml.set(path + ".entity-type", anchor.entityType.name());
            yaml.set(path + ".last-death-epoch-ms", anchor.lastDeathEpochMillis);
            yaml.set(path + ".current-boss", anchor.currentBossId == null ? null : anchor.currentBossId.toString());
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Не удалось создать папку WorldStructures");
            }
            yaml.save(storageFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Не удалось сохранить boss-anchors.yml: " + exception.getMessage());
        }
    }

    public record BossAnchorInfo(String id, String structureId, String worldName, Location location,
                                 boolean alive, long lastDeathEpochMillis) {}

    private static final class BossRuntime {
        private final List<BossAbility> abilities;
        private long nextAbilityAtMillis;
        private final BossBar bossBar;

        private BossRuntime(List<BossAbility> abilities, long nextAbilityAtMillis, BossBar bossBar) {
            this.abilities = abilities;
            this.nextAbilityAtMillis = nextAbilityAtMillis;
            this.bossBar = bossBar;
        }
    }

    private static final class BossAnchor {
        private final String id;
        private final String structureId;
        private final String worldName;
        private final double x;
        private final double y;
        private final double z;
        private final EntityType entityType;
        private long lastDeathEpochMillis;
        private UUID currentBossId;

        private BossAnchor(String id, String structureId, String worldName,
                           double x, double y, double z, EntityType entityType,
                           long lastDeathEpochMillis, UUID currentBossId) {
            this.id = id;
            this.structureId = structureId;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.entityType = entityType;
            this.lastDeathEpochMillis = lastDeathEpochMillis;
            this.currentBossId = currentBossId;
        }

        private Location location(World world) { return new Location(world, x, y, z); }
    }
}
