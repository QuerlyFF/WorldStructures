package dev.smpcristalix.worldstructures.config;

import dev.smpcristalix.worldstructures.boss.BossAbility;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Неизменяемый снимок настроек WorldStructures.
 */
public final class WorldStructuresSettings {

    private final boolean enabled;
    private final MobTierSpec normalMob;
    private final MobTierSpec eliteMob;
    private final double eliteChance;
    private final DropRule normalCoins;
    private final DropRule eliteCoins;
    private final GearSpec gear;
    private final BossSpec boss;
    private final ChestSpec chest;
    private final Map<String, StructureBossSpec> structures;

    private WorldStructuresSettings(FileConfiguration config) {
        enabled = config.getBoolean("general.enabled", true);

        normalMob = new MobTierSpec(
                positive(config.getDouble("structure-mobs.health-multiplier", 2.0), 1.0),
                positive(config.getDouble("structure-mobs.damage-multiplier", 1.5), 1.0),
                1.0
        );
        eliteChance = probability(config.getDouble("structure-mobs.elite-chance", 0.20));
        normalCoins = readDropRule(config, "structure-mobs.bronze-coins", 0.20, 0.40, 1, 2);

        eliteMob = new MobTierSpec(
                positive(config.getDouble("elite-mobs.health-multiplier", 3.0), 1.0),
                positive(config.getDouble("elite-mobs.damage-multiplier", 2.0), 1.0),
                positive(config.getDouble("elite-mobs.speed-multiplier", 1.15), 1.0)
        );
        eliteCoins = readDropRule(config, "elite-mobs.bronze-coins", 0.30, 0.50, 2, 4);

        gear = new GearSpec(
                probability(config.getDouble("gear.diamond-chance-normal", 0.40)),
                probability(config.getDouble("gear.diamond-chance-elite", 0.80)),
                positiveInt(config.getInt("gear.protection-normal-min", 2), 1),
                positiveInt(config.getInt("gear.protection-normal-max", 3), 1),
                positiveInt(config.getInt("gear.protection-elite-min", 3), 1),
                positiveInt(config.getInt("gear.protection-elite-max", 4), 1),
                probability(config.getDouble("gear.boss-netherite-piece-chance", 0.60)),
                positiveInt(config.getInt("gear.boss-protection-min", 2), 1),
                positiveInt(config.getInt("gear.boss-protection-max", 3), 1),
                (float) probability(config.getDouble("gear.drop-chance", 0.01))
        );

        boss = new BossSpec(
                positive(config.getDouble("miniboss.health-multiplier", 5.0), 1.0),
                positive(config.getDouble("miniboss.damage-multiplier", 3.0), 1.0),
                positive(config.getDouble("miniboss.speed-multiplier", 1.40), 1.0),
                Math.max(0, config.getInt("miniboss.resistance-amplifier", 0)),
                positiveInt(config.getInt("miniboss.bronze-coins-min", 13), 1),
                positiveInt(config.getInt("miniboss.bronze-coins-max", 16), 1),
                probability(config.getDouble("miniboss.shard-drop-chance", 0.05)),
                Math.max(20L, config.getLong("miniboss.respawn-seconds", 7200L) * 20L),
                positiveInt(config.getInt("miniboss.abilities-per-boss-min", 1), 1),
                positiveInt(config.getInt("miniboss.abilities-per-boss-max", 2), 1),
                positiveInt(config.getInt("miniboss.ability-cooldown-min-seconds", 10), 1) * 20,
                positiveInt(config.getInt("miniboss.ability-cooldown-max-seconds", 15), 1) * 20,
                positiveInt(config.getInt("miniboss.summon.min-count", 2), 1),
                positiveInt(config.getInt("miniboss.summon.max-count", 4), 1),
                positiveInt(config.getInt("miniboss.summon.max-alive-minions", 12), 1),
                atLeast(config.getDouble("miniboss.summon.radius", 5.0), 1.0),
                atLeast(config.getDouble("miniboss.knockback.radius", 9.0), 1.0),
                atLeast(config.getDouble("miniboss.knockback.horizontal-strength", 1.35), 0.1),
                atLeast(config.getDouble("miniboss.knockback.vertical-strength", 0.35), 0.0),
                atLeast(config.getDouble("miniboss.regeneration.radius", 10.0), 1.0),
                positiveInt(config.getInt("miniboss.regeneration.duration-min-seconds", 5), 1) * 20,
                positiveInt(config.getInt("miniboss.regeneration.duration-max-seconds", 6), 1) * 20,
                Math.max(0, config.getInt("miniboss.regeneration.amplifier", 1)),
                atLeast(config.getDouble("miniboss.debuff.radius", 10.0), 1.0),
                positiveInt(config.getInt("miniboss.debuff.duration-min-seconds", 5), 1) * 20,
                positiveInt(config.getInt("miniboss.debuff.duration-max-seconds", 8), 1) * 20
        );

        chest = new ChestSpec(
                probability(config.getDouble("chests.bronze-coin-chance", 0.35)),
                positiveInt(config.getInt("chests.bronze-coins-min", 2), 1),
                positiveInt(config.getInt("chests.bronze-coins-max", 5), 1),
                positiveInt(config.getInt("chests.valuable-rolls-min", 1), 1),
                positiveInt(config.getInt("chests.valuable-rolls-max", 2), 1),
                probability(config.getDouble("chests.valuable-roll-chance", 0.45)),
                Math.max(0, config.getInt("chests.enchanted-book-weight", 25)),
                Math.max(0, config.getInt("chests.experience-bottle-weight", 35)),
                Math.max(0, config.getInt("chests.precious-resource-weight", 40)),
                probability(config.getDouble("chests.mob-loot-chance", 0.60))
        );

        structures = Collections.unmodifiableMap(readStructures(config, eliteChance));
        if (enabled && structures.isEmpty()) {
            throw new IllegalArgumentException("structures section must contain at least one structure");
        }
        validateGenerationConfig(config, structures.keySet());
        validateRuntimeConfig(config);
    }

    public static WorldStructuresSettings from(FileConfiguration config) {
        return new WorldStructuresSettings(config);
    }

    public boolean enabled() { return enabled; }
    public MobTierSpec normalMob() { return normalMob; }
    public MobTierSpec eliteMob() { return eliteMob; }
    public double eliteChance() { return eliteChance; }
    public DropRule normalCoins() { return normalCoins; }
    public DropRule eliteCoins() { return eliteCoins; }
    public GearSpec gear() { return gear; }
    public BossSpec boss() { return boss; }
    public ChestSpec chest() { return chest; }
    public Map<String, StructureBossSpec> structures() { return structures; }

    public StructureBossSpec structure(String id) {
        return id == null ? null : structures.get(id.toLowerCase());
    }

    private static Map<String, StructureBossSpec> readStructures(FileConfiguration config, double defaultEliteChance) {
        Map<String, StructureBossSpec> result = new LinkedHashMap<>();
        ConfigurationSection root = config.getConfigurationSection("structures");
        if (root == null) return result;

        for (String key : root.getKeys(false)) {
            String path = "structures." + key;
            EntityType type;
            try {
                type = EntityType.valueOf(config.getString(path + ".boss-type", "VINDICATOR").toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown boss type at " + path + ".boss-type");
            }
            if (!type.isAlive()) throw new IllegalArgumentException("Boss type must be living at " + path);

            EnumSet<BossAbility> abilities = EnumSet.noneOf(BossAbility.class);
            for (String raw : config.getStringList(path + ".abilities")) {
                try {
                    abilities.add(BossAbility.valueOf(raw.toUpperCase()));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Unknown boss ability at " + path + ": " + raw);
                }
            }
            if (abilities.isEmpty()) abilities.add(BossAbility.KNOCKBACK);

            List<MobSpawnSpec> mobs = readMobSpawns(config, path + ".mobs");
            if (mobs.isEmpty()) mobs = List.of(new MobSpawnSpec(type, 4, 7));

            int dangerLevel = Math.max(1, config.getInt(path + ".danger-level", 1));
            double structureEliteChance = probability(config.getDouble(path + ".elite-chance", defaultEliteChance));
            double powerMultiplier = positive(config.getDouble(path + ".power-multiplier", 1.0), 1.0);

            result.put(key.toLowerCase(), new StructureBossSpec(
                    type,
                    List.copyOf(abilities),
                    List.copyOf(mobs),
                    dangerLevel,
                    structureEliteChance,
                    powerMultiplier
            ));
        }
        return result;
    }

    private static List<MobSpawnSpec> readMobSpawns(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) return List.of();

        List<MobSpawnSpec> result = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            EntityType type;
            try {
                type = EntityType.valueOf(key.toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown mob type at " + path + ": " + key);
            }
            if (!type.isAlive()) throw new IllegalArgumentException("Mob type must be living at " + path + ": " + key);

            List<Integer> range = config.getIntegerList(path + "." + key);
            if (range.size() < 2) continue;
            int min = Math.max(0, Math.min(range.get(0), range.get(1)));
            int max = Math.max(min, Math.max(range.get(0), range.get(1)));
            if (max > 0) result.add(new MobSpawnSpec(type, min, max));
        }
        return result;
    }

    private static DropRule readDropRule(FileConfiguration config, String root,
                                         double chanceMin, double chanceMax, int amountMin, int amountMax) {
        double minChance = probability(config.getDouble(root + ".chance-min", chanceMin));
        double maxChance = probability(config.getDouble(root + ".chance-max", chanceMax));
        int minAmount = positiveInt(config.getInt(root + ".amount-min", amountMin), 1);
        int maxAmount = positiveInt(config.getInt(root + ".amount-max", amountMax), minAmount);
        return new DropRule(Math.min(minChance, maxChance), Math.max(minChance, maxChance),
                Math.min(minAmount, maxAmount), Math.max(minAmount, maxAmount));
    }

    private static double probability(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Probability must be finite: " + value);
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double positive(double value, double fallback) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Multiplier must be finite: " + value);
        return value > 0.0 ? value : fallback;
    }

    private static double atLeast(double value, double minimum) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Numeric setting must be finite: " + value);
        return Math.max(minimum, value);
    }

    private static void validateGenerationConfig(FileConfiguration config, java.util.Set<String> structureIds) {
        atLeast(config.getDouble("generation.min-distance-from-spawn-blocks", 500.0), 0.0);
        probability(config.getDouble("generation.spawn-chance-per-region", 0.60));
        for (String structureId : structureIds) {
            probability(config.getDouble("structures." + structureId + ".generation.chance", 1.0));
            String placement = config.getString("structures." + structureId + ".generation.placement", "LAND");
            if (placement == null || !java.util.Set.of("LAND", "WATER", "COAST")
                    .contains(placement.toUpperCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Unknown generation placement for " + structureId + ": " + placement);
            }
        }
    }

    private static void validateRuntimeConfig(FileConfiguration config) {
        atLeast(config.getDouble("runtime.guard-respawn-player-exclusion-radius", 64.0), 0.0);
        atLeast(config.getDouble("runtime.boss-leash-radius", 64.0), 0.0);
        atLeast(config.getDouble("runtime.bossbar-radius", 48.0), 0.0);
        double healFraction = config.getDouble("runtime.boss-return-heal-fraction", 0.25);
        if (!Double.isFinite(healFraction) || healFraction < 0.0 || healFraction > 1.0) {
            throw new IllegalArgumentException("runtime.boss-return-heal-fraction must be between 0 and 1");
        }
    }

    private static int positiveInt(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    public record MobTierSpec(double healthMultiplier, double damageMultiplier, double speedMultiplier) {}

    public record DropRule(double chanceMin, double chanceMax, int amountMin, int amountMax) {
        public boolean shouldDrop(RandomGenerator random) {
            double chance = chanceMin == chanceMax ? chanceMin : random.nextDouble(chanceMin, chanceMax);
            return random.nextDouble() < chance;
        }

        public int randomAmount(RandomGenerator random) {
            return random.nextInt(amountMin, amountMax + 1);
        }
    }

    public record GearSpec(double diamondChanceNormal, double diamondChanceElite,
                           int protectionNormalMin, int protectionNormalMax,
                           int protectionEliteMin, int protectionEliteMax,
                           double bossNetheritePieceChance,
                           int bossProtectionMin, int bossProtectionMax,
                           float dropChance) {}

    public record BossSpec(double healthMultiplier, double damageMultiplier, double speedMultiplier,
                           int resistanceAmplifier, int bronzeCoinsMin, int bronzeCoinsMax,
                           double shardDropChance, long respawnTicks,
                           int abilitiesMin, int abilitiesMax,
                           int cooldownMinTicks, int cooldownMaxTicks,
                           int summonMin, int summonMax, int maxAliveMinions, double summonRadius,
                           double knockbackRadius, double knockbackHorizontal, double knockbackVertical,
                           double regenRadius, int regenDurationMinTicks, int regenDurationMaxTicks,
                           int regenAmplifier, double debuffRadius,
                           int debuffDurationMinTicks, int debuffDurationMaxTicks) {}

    public record ChestSpec(double coinChance, int amountMin, int amountMax,
                            int valuableRollsMin, int valuableRollsMax,
                            double valuableRollChance,
                            int enchantedBookWeight, int experienceBottleWeight, int preciousResourceWeight,
                            double mobLootChance) {}

    public record MobSpawnSpec(EntityType type, int minCount, int maxCount) {}

    public record StructureBossSpec(EntityType bossType,
                                    List<BossAbility> abilities,
                                    List<MobSpawnSpec> mobs,
                                    int dangerLevel,
                                    double eliteChance,
                                    double powerMultiplier) {}
}
