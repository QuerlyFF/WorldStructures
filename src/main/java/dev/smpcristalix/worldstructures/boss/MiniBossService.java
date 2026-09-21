package dev.smpcristalix.worldstructures.boss;

import dev.smpcristalix.worldstructures.config.WorldStructuresSettings;
import dev.smpcristalix.worldstructures.mob.StructureMobService;
import dev.smpcristalix.worldstructures.persistence.AtomicYamlStore;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Runtime мини-боссов: persistence, BossBar, leash, независимые способности и безопасный respawn.
 */
public final class MiniBossService {

    private static final long MOVEMENT_SAVE_INTERVAL_MILLIS = 5_000L;

    private final Plugin plugin;
    private final StructureMobService mobService;
    private final File storageFile;
    private final Map<String, BossAnchor> anchors = new LinkedHashMap<>();
    private final Map<UUID, BossRuntime> runtimes = new HashMap<>();
    private volatile WorldStructuresSettings settings;
    private BukkitTask task;
    private boolean anchorsDirty;
    private long lastAnchorSaveMillis;
    private boolean persistenceWritable = true;

    public MiniBossService(Plugin plugin, WorldStructuresSettings settings, StructureMobService mobService) {
        this.plugin = plugin;
        this.settings = settings;
        this.mobService = mobService;
        this.storageFile = new File(plugin.getDataFolder(), "boss-anchors.yml");
    }

    public void start() {
        loadAnchors();
        lastAnchorSaveMillis = System.currentTimeMillis();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
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
        if (location == null || location.getWorld() == null) {
            throw new IllegalArgumentException("Boss anchor location has no world");
        }
        String normalized = structureId.toLowerCase();
        WorldStructuresSettings.StructureBossSpec structure = settings.structure(normalized);
        if (structure == null) throw new IllegalArgumentException("Unknown structure: " + structureId);

        BossAnchor anchor = new BossAnchor(
                instanceId,
                normalized,
                location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                structure.bossType(),
                0L,
                null,
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4
        );
        anchors.put(instanceId, anchor);
        if (!saveAnchors()) {
            anchors.remove(instanceId);
            throw new IllegalStateException("Could not persist boss anchor " + instanceId);
        }
        spawnBoss(anchor, true);
        return instanceId;
    }

    public int anchorCount() {
        return anchors.size();
    }

    public boolean hasAnchor(String anchorId) {
        return anchorId != null && anchors.containsKey(anchorId);
    }

    public int aliveBossCount() {
        int count = 0;
        for (BossAnchor anchor : anchors.values()) {
            if (!isLastKnownBossChunkLoaded(anchor)) continue;
            if (resolveLivingBoss(anchor) != null) count++;
        }
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
        if (anchor.currentBossId != null && resolveLivingBoss(anchor) == null
                && !areBossSearchChunksLoaded(anchor)) return false;
        removeCurrentBoss(anchor, true, true);
        anchor.lastDeathEpochMillis = 0L;
        spawnBoss(anchor, true);
        saveAnchors();
        return true;
    }

    public boolean forceRemove(String anchorId) {
        BossAnchor anchor = anchors.get(anchorId);
        if (anchor == null) return false;
        if (anchor.currentBossId != null && resolveLivingBoss(anchor) == null
                && !areBossSearchChunksLoaded(anchor)) return false;
        removeCurrentBoss(anchor, true, true);
        anchor.lastDeathEpochMillis = System.currentTimeMillis();
        saveAnchors();
        return true;
    }

    public void onBossDeath(LivingEntity boss) {
        BossAnchor anchor = findAnchorByBoss(boss.getUniqueId());
        if (anchor == null) return;

        removeLoadedMinions(boss.getUniqueId(), anchor);
        removeRuntime(boss.getUniqueId());
        anchor.lastDeathEpochMillis = System.currentTimeMillis();
        anchor.currentBossId = null;
        saveAnchors();
    }

    private void tick() {
        if (!settings.enabled()) return;
        long now = System.currentTimeMillis();
        long respawnMillis = settings.boss().respawnTicks() * 50L;

        for (BossAnchor anchor : new ArrayList<>(anchors.values())) {
            if (anchor.currentBossId != null) {
                // Сначала смотрим chunk state. Так тысячи боссов из выгруженных данжей не требуют UUID lookup каждый тик.
                if (!isLastKnownBossChunkLoaded(anchor)) {
                    removeRuntime(anchor.currentBossId);
                    continue;
                }

                LivingEntity boss = resolveLivingBoss(anchor);
                if (boss != null) {
                    BossRuntime runtime = runtimes.computeIfAbsent(
                            boss.getUniqueId(), ignored -> createRuntime(anchor, boss, now)
                    );
                    enforceLeash(anchor, boss);
                    if (updateLastKnownBossChunk(anchor, boss)) anchorsDirty = true;
                    updateBossBar(boss, runtime);

                    if (hasEngagedPlayer(boss)) castDueAbilities(anchor, boss, runtime, now);
                    else postponeOverdueAbilities(runtime, now);
                    continue;
                }

                // UUID lookup не отличает удалённую сущность от босса, который успел перейти
                // в соседний выгруженный чанк между двумя тиками сервиса. Сбрасывать UUID можно
                // только когда загружена вся разрешённая leash-зона.
                if (!areBossSearchChunksLoaded(anchor)) continue;
                removeRuntime(anchor.currentBossId);
                removeLoadedMinions(anchor.currentBossId, anchor);
                anchor.currentBossId = null;
                anchorsDirty = true;
            }

            boolean due = anchor.lastDeathEpochMillis == 0L
                    || now - anchor.lastDeathEpochMillis >= respawnMillis;
            if (!due) continue;

            // Фоновый respawn не имеет права будить мир. Босс появится при следующей загрузке anchor-чанка игроком.
            if (!isAnchorChunkLoaded(anchor)) continue;
            spawnBoss(anchor, false);
        }

        flushDirtyAnchorsIfDue(now);
    }

    private void castDueAbilities(BossAnchor anchor, LivingEntity boss, BossRuntime runtime, long now) {
        for (BossAbility ability : runtime.abilities) {
            long due = runtime.nextAbilityAtMillis.getOrDefault(ability, Long.MAX_VALUE);
            if (now < due) continue;
            castAbility(anchor, boss, ability);
            runtime.nextAbilityAtMillis.put(ability, now + randomCooldownMillis());
        }
    }

    private void postponeOverdueAbilities(BossRuntime runtime, long now) {
        for (BossAbility ability : runtime.abilities) {
            long due = runtime.nextAbilityAtMillis.getOrDefault(ability, Long.MAX_VALUE);
            if (now >= due) runtime.nextAbilityAtMillis.put(ability, now + 1_000L);
        }
    }

    private boolean hasEngagedPlayer(LivingEntity boss) {
        double radius = Math.max(24.0, Math.max(
                plugin.getConfig().getDouble("runtime.bossbar-radius", 48.0),
                plugin.getConfig().getDouble("runtime.boss-leash-radius", 64.0)
        ));
        double max = radius * radius;
        for (Player player : boss.getWorld().getPlayers()) {
            if (!isCombatPlayer(player)) continue;
            if (player.getLocation().distanceSquared(boss.getLocation()) <= max) return true;
        }
        return false;
    }

    private void enforceLeash(BossAnchor anchor, LivingEntity boss) {
        Location home = anchor.location(boss.getWorld());
        double radius = Math.max(24.0, plugin.getConfig().getDouble("runtime.boss-leash-radius", 64.0));
        if (boss.getLocation().distanceSquared(home) <= radius * radius) return;

        boss.teleport(home);
        AttributeInstance maxHealth = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth != null) {
            double fraction = Math.max(0.0, Math.min(1.0,
                    plugin.getConfig().getDouble("runtime.boss-return-heal-fraction", 0.25)));
            boss.setHealth(Math.min(maxHealth.getValue(), boss.getHealth() + maxHealth.getValue() * fraction));
        }
        boss.getWorld().strikeLightningEffect(home);
    }

    private boolean updateLastKnownBossChunk(BossAnchor anchor, LivingEntity boss) {
        int chunkX = boss.getLocation().getBlockX() >> 4;
        int chunkZ = boss.getLocation().getBlockZ() >> 4;
        if (chunkX == anchor.lastBossChunkX && chunkZ == anchor.lastBossChunkZ) return false;
        anchor.lastBossChunkX = chunkX;
        anchor.lastBossChunkZ = chunkZ;
        return true;
    }

    private void updateBossBar(LivingEntity boss, BossRuntime runtime) {
        AttributeInstance maxHealth = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double max = maxHealth == null ? Math.max(1.0, boss.getHealth()) : Math.max(1.0, maxHealth.getValue());
        runtime.bossBar.setProgress(Math.max(0.0, Math.min(1.0, boss.getHealth() / max)));
        runtime.bossBar.setTitle(boss.getCustomName() == null ? "§4☠ Мини-босс" : boss.getCustomName());

        double radius = Math.max(16.0, plugin.getConfig().getDouble("runtime.bossbar-radius", 48.0));
        double radiusSquared = radius * radius;
        for (Player player : boss.getWorld().getPlayers()) {
            boolean nearby = isCombatPlayer(player)
                    && player.getLocation().distanceSquared(boss.getLocation()) <= radiusSquared;
            if (nearby && !runtime.bossBar.getPlayers().contains(player)) runtime.bossBar.addPlayer(player);
            else if (!nearby && runtime.bossBar.getPlayers().contains(player)) runtime.bossBar.removePlayer(player);
        }
        for (Player player : new ArrayList<>(runtime.bossBar.getPlayers())) {
            if (!player.isOnline() || !player.getWorld().equals(boss.getWorld()) || !isCombatPlayer(player)) {
                runtime.bossBar.removePlayer(player);
            }
        }
    }

    private void spawnBoss(BossAnchor anchor, boolean force) {
        World world = Bukkit.getWorld(anchor.worldName);
        if (world == null) return;

        int anchorChunkX = ((int) Math.floor(anchor.x)) >> 4;
        int anchorChunkZ = ((int) Math.floor(anchor.z)) >> 4;
        if (!force && !world.isChunkLoaded(anchorChunkX, anchorChunkZ)) return;
        if (force && !world.isChunkLoaded(anchorChunkX, anchorChunkZ)) {
            world.getChunkAt(anchorChunkX, anchorChunkZ).load();
        }

        if (!force && anchor.currentBossId != null && resolveLivingBoss(anchor) != null) return;

        Location location = anchor.location(world);
        Entity spawned = world.spawnEntity(location, anchor.entityType);
        if (!(spawned instanceof LivingEntity boss)) {
            spawned.remove();
            plugin.getLogger().warning("Boss type " + anchor.entityType + " is not LivingEntity for " + anchor.structureId);
            return;
        }

        mobService.prepareMiniBoss(boss, anchor.structureId, anchor.id);
        anchor.currentBossId = boss.getUniqueId();
        anchor.lastBossChunkX = boss.getLocation().getBlockX() >> 4;
        anchor.lastBossChunkZ = boss.getLocation().getBlockZ() >> 4;
        runtimes.put(boss.getUniqueId(), createRuntime(anchor, boss, System.currentTimeMillis()));
        if (!saveAnchors()) {
            removeRuntime(boss.getUniqueId());
            boss.remove();
            anchor.currentBossId = null;
            saveAnchors();
        }
    }

    private BossRuntime createRuntime(BossAnchor anchor, LivingEntity boss, long now) {
        WorldStructuresSettings.StructureBossSpec spec = settings.structure(anchor.structureId);
        List<BossAbility> selected = selectAbilities(
                spec == null ? List.of(BossAbility.KNOCKBACK) : spec.abilities()
        );

        EnumMap<BossAbility, Long> nextAbilityAt = new EnumMap<>(BossAbility.class);
        int stagger = 0;
        for (BossAbility ability : selected) {
            nextAbilityAt.put(ability, now + randomCooldownMillis() + stagger);
            stagger += 700;
        }

        BossBar bossBar = Bukkit.createBossBar(
                boss.getCustomName() == null ? "§4☠ Мини-босс" : boss.getCustomName(),
                BarColor.RED,
                BarStyle.SEGMENTED_10
        );
        return new BossRuntime(selected, nextAbilityAt, bossBar);
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

    private void castAbility(BossAnchor anchor, LivingEntity boss, BossAbility ability) {
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
        int desired = random.nextInt(
                Math.min(config.summonMin(), config.summonMax()),
                Math.max(config.summonMin(), config.summonMax()) + 1
        );
        int count = Math.min(desired, config.maxAliveMinions() - alive);

        for (int i = 0; i < count; i++) {
            Location spawn = findNearbySpawn(boss.getLocation(), config.summonRadius(), random);
            Entity entity = boss.getWorld().spawnEntity(spawn, anchor.entityType);
            if (entity instanceof LivingEntity minion) {
                mobService.prepareSummonedMinion(minion, anchor.structureId, anchor.id, boss.getUniqueId());
            } else {
                entity.remove();
            }
        }
    }

    private int countAliveMinions(LivingEntity boss) {
        int count = 0;
        for (LivingEntity living : boss.getWorld().getLivingEntities()) {
            if (living.isValid()
                    && !living.isDead()
                    && mobService.isSummonedBy(living, boss.getUniqueId())) {
                count++;
            }
        }
        return count;
    }

    private Location findNearbySpawn(Location center, double radius, RandomGenerator random) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            double distance = random.nextDouble(1.5, Math.max(1.6, radius));
            Location candidate = center.clone().add(Math.cos(angle) * distance, 0.0, Math.sin(angle) * distance);
            if (candidate.getBlock().isPassable()
                    && candidate.clone().add(0, 1, 0).getBlock().isPassable()
                    && candidate.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                return candidate;
            }
        }
        return center.clone();
    }

    private void knockback(LivingEntity boss) {
        WorldStructuresSettings.BossSpec config = settings.boss();
        double radius = config.knockbackRadius();
        for (Player player : boss.getWorld().getPlayers()) {
            if (!isCombatPlayer(player)) continue;
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
        PotionEffect effect = new PotionEffect(
                PotionEffectType.REGENERATION,
                duration,
                config.regenAmplifier(),
                false,
                true,
                true
        );
        boss.addPotionEffect(effect);

        double radius = config.regenRadius();
        for (Entity entity : boss.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity living && mobService.isStructureMob(living)) {
                living.addPotionEffect(effect);
            }
        }
    }

    private void randomDebuff(LivingEntity boss) {
        WorldStructuresSettings.BossSpec config = settings.boss();
        int duration = ThreadLocalRandom.current().nextInt(
                Math.min(config.debuffDurationMinTicks(), config.debuffDurationMaxTicks()),
                Math.max(config.debuffDurationMinTicks(), config.debuffDurationMaxTicks()) + 1
        );
        PotionEffectType[] effects = {
                PotionEffectType.SLOWNESS,
                PotionEffectType.WEAKNESS,
                PotionEffectType.POISON,
                PotionEffectType.BLINDNESS,
                PotionEffectType.WITHER
        };
        double radius = config.debuffRadius();
        RandomGenerator random = ThreadLocalRandom.current();
        for (Player player : boss.getWorld().getPlayers()) {
            if (!isCombatPlayer(player)) continue;
            if (player.getLocation().distanceSquared(boss.getLocation()) > radius * radius) continue;
            PotionEffectType type = effects[random.nextInt(effects.length)];
            int amplifier = (type == PotionEffectType.SLOWNESS || type == PotionEffectType.WEAKNESS) ? 1 : 0;
            player.addPotionEffect(new PotionEffect(type, duration, amplifier, false, true, true));
        }
    }

    private boolean isCombatPlayer(Player player) {
        GameMode mode = player.getGameMode();
        return player.isOnline() && (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE);
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

    private boolean isLastKnownBossChunkLoaded(BossAnchor anchor) {
        World world = Bukkit.getWorld(anchor.worldName);
        return world != null && world.isChunkLoaded(anchor.lastBossChunkX, anchor.lastBossChunkZ);
    }

    private BossAnchor findAnchorByBoss(UUID bossId) {
        for (BossAnchor anchor : anchors.values()) {
            if (bossId.equals(anchor.currentBossId)) return anchor;
        }
        return null;
    }

    private boolean areBossSearchChunksLoaded(BossAnchor anchor) {
        World world = Bukkit.getWorld(anchor.worldName);
        if (world == null) return false;
        int radius = (int) Math.ceil(Math.max(24.0,
                plugin.getConfig().getDouble("runtime.boss-leash-radius", 64.0))) + 16;
        int minChunkX = Math.floorDiv((int) Math.floor(anchor.x) - radius, 16);
        int maxChunkX = Math.floorDiv((int) Math.floor(anchor.x) + radius, 16);
        int minChunkZ = Math.floorDiv((int) Math.floor(anchor.z) - radius, 16);
        int maxChunkZ = Math.floorDiv((int) Math.floor(anchor.z) + radius, 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    public boolean isTrackedBoss(UUID bossId) {
        return bossId != null && findAnchorByBoss(bossId) != null;
    }

    /** Removes excess loaded minions, including ones returning from formerly unloaded chunks. */
    public void enforceLoadedMinionLimit(UUID bossId) {
        BossAnchor anchor = findAnchorByBoss(bossId);
        if (anchor == null) return;
        World world = Bukkit.getWorld(anchor.worldName);
        if (world == null) return;

        int remaining = settings.boss().maxAliveMinions();
        for (LivingEntity living : world.getLivingEntities()) {
            if (!mobService.isSummonedBy(living, bossId)) continue;
            if (remaining-- > 0) continue;
            living.remove();
        }
    }

    private void removeCurrentBoss(BossAnchor anchor, boolean cleanupMinions, boolean loadLastChunk) {
        if (anchor.currentBossId == null) return;
        UUID bossId = anchor.currentBossId;
        World world = Bukkit.getWorld(anchor.worldName);
        if (loadLastChunk && world != null && !world.isChunkLoaded(anchor.lastBossChunkX, anchor.lastBossChunkZ)) {
            world.getChunkAt(anchor.lastBossChunkX, anchor.lastBossChunkZ).load();
        }

        removeRuntime(bossId);
        if (cleanupMinions) removeLoadedMinions(bossId, anchor);
        Entity entity = Bukkit.getEntity(bossId);
        if (entity != null) entity.remove();
        anchor.currentBossId = null;
    }

    private void removeRuntime(UUID bossId) {
        BossRuntime runtime = runtimes.remove(bossId);
        if (runtime != null) runtime.bossBar.removeAll();
    }

    private void removeLoadedMinions(UUID bossId, BossAnchor anchor) {
        World world = Bukkit.getWorld(anchor.worldName);
        if (world == null) return;
        // Boss removal/death is rare. A full loaded-entity pass prevents lured minions
        // outside the old radius from surviving and becoming permanent orphans.
        for (LivingEntity living : world.getLivingEntities()) {
            if (mobService.isSummonedBy(living, bossId)) {
                living.remove();
            }
        }
    }

    private BossAnchorInfo info(BossAnchor anchor) {
        World world = Bukkit.getWorld(anchor.worldName);
        Location location = world == null ? null : anchor.location(world);
        boolean alive = isLastKnownBossChunkLoaded(anchor) && resolveLivingBoss(anchor) != null;
        return new BossAnchorInfo(
                anchor.id,
                anchor.structureId,
                anchor.worldName,
                location,
                alive,
                anchor.lastDeathEpochMillis
        );
    }

    private double distanceSquared2D(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private void flushDirtyAnchorsIfDue(long now) {
        if (!anchorsDirty || now - lastAnchorSaveMillis < MOVEMENT_SAVE_INTERVAL_MILLIS) return;
        saveAnchors();
    }

    private void loadAnchors() {
        if (!storageFile.exists()) return;
        YamlConfiguration yaml = AtomicYamlStore.load(storageFile, plugin.getLogger());
        if (yaml == null) {
            persistenceWritable = false;
            return;
        }
        persistenceWritable = true;
        anchors.clear();
        ConfigurationSection root = yaml.getConfigurationSection("anchors");
        if (root == null) return;
        java.util.Set<UUID> claimedBossIds = new java.util.HashSet<>();

        for (String id : root.getKeys(false)) {
            String path = "anchors." + id;
            try {
                EntityType type = EntityType.valueOf(yaml.getString(path + ".entity-type", "VINDICATOR"));
                String uuidRaw = yaml.getString(path + ".current-boss");
                UUID current = uuidRaw == null || uuidRaw.isBlank() ? null : UUID.fromString(uuidRaw);
                double x = yaml.getDouble(path + ".x");
                double y = yaml.getDouble(path + ".y");
                double z = yaml.getDouble(path + ".z");
                String structureId = yaml.getString(path + ".structure-id");
                String worldName = yaml.getString(path + ".world");
                if (id.isBlank() || structureId == null || structureId.isBlank()
                        || worldName == null || worldName.isBlank()
                        || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                    plugin.getLogger().warning("Пропущен повреждённый boss anchor " + id);
                    continue;
                }
                if (current != null && !claimedBossIds.add(current)) {
                    plugin.getLogger().warning("Повторный boss UUID в anchor " + id + "; UUID сброшен.");
                    current = null;
                }
                int defaultChunkX = ((int) Math.floor(x)) >> 4;
                int defaultChunkZ = ((int) Math.floor(z)) >> 4;
                anchors.put(id, new BossAnchor(
                        id,
                        structureId,
                        worldName,
                        x, y, z,
                        type,
                        yaml.getLong(path + ".last-death-epoch-ms", 0L),
                        current,
                        yaml.getInt(path + ".last-boss-chunk-x", defaultChunkX),
                        yaml.getInt(path + ".last-boss-chunk-z", defaultChunkZ)
                ));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Не удалось загрузить boss anchor " + id);
            }
        }
    }

    private boolean saveAnchors() {
        if (!persistenceWritable) return false;
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
            yaml.set(path + ".last-boss-chunk-x", anchor.lastBossChunkX);
            yaml.set(path + ".last-boss-chunk-z", anchor.lastBossChunkZ);
        }
        if (AtomicYamlStore.save(storageFile, yaml, plugin.getLogger())) {
            anchorsDirty = false;
            lastAnchorSaveMillis = System.currentTimeMillis();
            return true;
        } else {
            anchorsDirty = true;
            return false;
        }
    }

    public record BossAnchorInfo(
            String id,
            String structureId,
            String worldName,
            Location location,
            boolean alive,
            long lastDeathEpochMillis
    ) {}

    private static final class BossRuntime {
        private final List<BossAbility> abilities;
        private final EnumMap<BossAbility, Long> nextAbilityAtMillis;
        private final BossBar bossBar;

        private BossRuntime(List<BossAbility> abilities,
                            EnumMap<BossAbility, Long> nextAbilityAtMillis,
                            BossBar bossBar) {
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
        private int lastBossChunkX;
        private int lastBossChunkZ;

        private BossAnchor(String id, String structureId, String worldName,
                           double x, double y, double z, EntityType entityType,
                           long lastDeathEpochMillis, UUID currentBossId,
                           int lastBossChunkX, int lastBossChunkZ) {
            this.id = id;
            this.structureId = structureId;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.entityType = entityType;
            this.lastDeathEpochMillis = lastDeathEpochMillis;
            this.currentBossId = currentBossId;
            this.lastBossChunkX = lastBossChunkX;
            this.lastBossChunkZ = lastBossChunkZ;
        }

        private Location location(World world) {
            return new Location(world, x, y, z);
        }
    }
}
