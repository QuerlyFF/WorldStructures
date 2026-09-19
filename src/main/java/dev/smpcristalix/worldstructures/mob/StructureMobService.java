package dev.smpcristalix.worldstructures.mob;

import dev.smpcristalix.worldstructures.config.WorldStructuresSettings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Создаёт, усиливает, визуально помечает обычных, элитных и боссовых мобов структур.
 */
public final class StructureMobService {

    private final NamespacedKey structureMobKey;
    private final NamespacedKey eliteMobKey;
    private final NamespacedKey miniBossKey;
    private final NamespacedKey structureIdKey;
    private final NamespacedKey structureInstanceKey;
    private final NamespacedKey projectileDamageMultiplierKey;
    private final NamespacedKey summonedByBossKey;
    private volatile WorldStructuresSettings settings;

    public StructureMobService(Plugin plugin, WorldStructuresSettings settings) {
        structureMobKey = new NamespacedKey(plugin, "structure_mob");
        eliteMobKey = new NamespacedKey(plugin, "elite_mob");
        miniBossKey = new NamespacedKey(plugin, "mini_boss");
        structureIdKey = new NamespacedKey(plugin, "structure_id");
        structureInstanceKey = new NamespacedKey(plugin, "structure_instance_id");
        projectileDamageMultiplierKey = new NamespacedKey(plugin, "projectile_damage_multiplier");
        summonedByBossKey = new NamespacedKey(plugin, "summoned_by_boss");
        this.settings = settings;
    }

    public void reload(WorldStructuresSettings newSettings) {
        this.settings = newSettings;
    }

    public LivingEntity spawnStructureMob(Location location, EntityType type, String structureId) {
        return spawnStructureMob(location, type, structureId, null);
    }

    public LivingEntity spawnStructureMob(Location location, EntityType type, String structureId, String instanceId) {
        Entity spawned = location.getWorld().spawnEntity(location, type);
        if (!(spawned instanceof LivingEntity living)) {
            spawned.remove();
            throw new IllegalArgumentException("EntityType " + type + " is not a LivingEntity");
        }
        prepareStructureMob(living, structureId, instanceId);
        return living;
    }

    public void prepareStructureMob(LivingEntity entity, String structureId) {
        prepareStructureMob(entity, structureId, null);
    }

    public void prepareStructureMob(LivingEntity entity, String structureId, String instanceId) {
        WorldStructuresSettings.StructureBossSpec structure = settings.structure(structureId);
        double eliteChance = structure == null ? settings.eliteChance() : structure.eliteChance();
        double power = structure == null ? 1.0 : structure.powerMultiplier();

        RandomGenerator random = ThreadLocalRandom.current();
        boolean elite = random.nextDouble() < eliteChance;
        WorldStructuresSettings.MobTierSpec base = elite ? settings.eliteMob() : settings.normalMob();
        prepare(entity, structureId, instanceId, scaleTier(base, power), elite, false);
    }

    public void prepareSummonedMinion(LivingEntity entity, String structureId, String instanceId, UUID bossId) {
        WorldStructuresSettings.StructureBossSpec structure = settings.structure(structureId);
        double power = structure == null ? 1.0 : structure.powerMultiplier();
        prepare(entity, structureId, instanceId, scaleTier(settings.normalMob(), power), false, false);
        entity.getPersistentDataContainer().set(summonedByBossKey, PersistentDataType.STRING, bossId.toString());
    }

    public void prepareMiniBoss(LivingEntity entity, String structureId, String instanceId) {
        WorldStructuresSettings.BossSpec boss = settings.boss();
        WorldStructuresSettings.StructureBossSpec structure = settings.structure(structureId);
        double power = structure == null ? 1.0 : structure.powerMultiplier();

        WorldStructuresSettings.MobTierSpec tier = new WorldStructuresSettings.MobTierSpec(
                boss.healthMultiplier() * power,
                boss.damageMultiplier() * power,
                boss.speedMultiplier()
        );
        prepare(entity, structureId, instanceId, tier, true, true);
        entity.setPersistent(true);
        entity.setRemoveWhenFarAway(false);
        entity.addPotionEffect(new PotionEffect(
                PotionEffectType.RESISTANCE,
                Integer.MAX_VALUE,
                boss.resistanceAmplifier(),
                false,
                false,
                true
        ));
    }

    public boolean isStructureMob(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(structureMobKey, PersistentDataType.BYTE);
    }

    public boolean isEliteMob(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(eliteMobKey, PersistentDataType.BYTE);
    }

    public boolean isMiniBoss(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(miniBossKey, PersistentDataType.BYTE);
    }

    public boolean isSummonedMob(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(summonedByBossKey, PersistentDataType.STRING);
    }

    public String structureId(LivingEntity entity) {
        return entity.getPersistentDataContainer().get(structureIdKey, PersistentDataType.STRING);
    }

    public String structureInstanceId(LivingEntity entity) {
        return entity.getPersistentDataContainer().get(structureInstanceKey, PersistentDataType.STRING);
    }

    public double projectileDamageMultiplier(LivingEntity entity) {
        Double value = entity.getPersistentDataContainer().get(projectileDamageMultiplierKey, PersistentDataType.DOUBLE);
        return value == null ? 1.0 : value;
    }

    public boolean isSummonedBy(LivingEntity entity, UUID bossId) {
        String owner = entity.getPersistentDataContainer().get(summonedByBossKey, PersistentDataType.STRING);
        return bossId.toString().equals(owner);
    }

    private WorldStructuresSettings.MobTierSpec scaleTier(WorldStructuresSettings.MobTierSpec base, double power) {
        return new WorldStructuresSettings.MobTierSpec(
                base.healthMultiplier() * power,
                base.damageMultiplier() * power,
                base.speedMultiplier()
        );
    }

    private void prepare(LivingEntity entity, String structureId, String instanceId,
                         WorldStructuresSettings.MobTierSpec tier, boolean elite, boolean boss) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (pdc.has(structureMobKey, PersistentDataType.BYTE)) return;

        pdc.set(structureMobKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(structureIdKey, PersistentDataType.STRING, structureId.toLowerCase());
        if (instanceId != null && !instanceId.isBlank()) {
            pdc.set(structureInstanceKey, PersistentDataType.STRING, instanceId);
        }
        pdc.set(projectileDamageMultiplierKey, PersistentDataType.DOUBLE, tier.damageMultiplier());
        if (elite) pdc.set(eliteMobKey, PersistentDataType.BYTE, (byte) 1);
        if (boss) pdc.set(miniBossKey, PersistentDataType.BYTE, (byte) 1);

        multiplyHealth(entity, tier.healthMultiplier());
        multiplyAttribute(entity, Attribute.GENERIC_ATTACK_DAMAGE, tier.damageMultiplier());
        multiplyAttribute(entity, Attribute.GENERIC_MOVEMENT_SPEED, tier.speedMultiplier());

        if (boss) applyBossGear(entity);
        else applyStructureGear(entity, elite);

        applyVisualIdentity(entity, structureId, elite, boss);
    }

    private void multiplyHealth(LivingEntity entity, double multiplier) {
        AttributeInstance maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth == null) return;
        maxHealth.setBaseValue(maxHealth.getBaseValue() * multiplier);
        entity.setHealth(maxHealth.getValue());
    }

    private void multiplyAttribute(LivingEntity entity, Attribute attribute, double multiplier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null || multiplier == 1.0) return;
        instance.setBaseValue(instance.getBaseValue() * multiplier);
    }

    private void applyStructureGear(LivingEntity entity, boolean elite) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) return;

        RandomGenerator random = ThreadLocalRandom.current();
        WorldStructuresSettings.GearSpec gear = settings.gear();
        double diamondChance = elite ? gear.diamondChanceElite() : gear.diamondChanceNormal();
        int minProtection = elite ? gear.protectionEliteMin() : gear.protectionNormalMin();
        int maxProtection = elite ? gear.protectionEliteMax() : gear.protectionNormalMax();

        equipment.setHelmet(armor(Material.IRON_HELMET, Material.DIAMOND_HELMET, diamondChance,
                randomLevel(random, minProtection, maxProtection)));
        equipment.setChestplate(armor(Material.IRON_CHESTPLATE, Material.DIAMOND_CHESTPLATE, diamondChance,
                randomLevel(random, minProtection, maxProtection)));
        equipment.setLeggings(armor(Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS, diamondChance,
                randomLevel(random, minProtection, maxProtection)));
        equipment.setBoots(armor(Material.IRON_BOOTS, Material.DIAMOND_BOOTS, diamondChance,
                randomLevel(random, minProtection, maxProtection)));
        setArmorDropChances(equipment, gear.dropChance());
        enchantWeapon(equipment.getItemInMainHand(), elite ? 4 : 3);
        equipment.setItemInMainHandDropChance(gear.dropChance());
    }

    private void applyBossGear(LivingEntity entity) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) return;

        WorldStructuresSettings.GearSpec gear = settings.gear();
        RandomGenerator random = ThreadLocalRandom.current();
        int protectionMin = Math.min(gear.bossProtectionMin(), gear.bossProtectionMax());
        int protectionMax = Math.max(gear.bossProtectionMin(), gear.bossProtectionMax());

        equipment.setHelmet(bossArmor(Material.DIAMOND_HELMET, Material.NETHERITE_HELMET,
                gear.bossNetheritePieceChance(), random.nextInt(protectionMin, protectionMax + 1)));
        equipment.setChestplate(bossArmor(Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE,
                gear.bossNetheritePieceChance(), random.nextInt(protectionMin, protectionMax + 1)));
        equipment.setLeggings(bossArmor(Material.DIAMOND_LEGGINGS, Material.NETHERITE_LEGGINGS,
                gear.bossNetheritePieceChance(), random.nextInt(protectionMin, protectionMax + 1)));
        equipment.setBoots(bossArmor(Material.DIAMOND_BOOTS, Material.NETHERITE_BOOTS,
                gear.bossNetheritePieceChance(), random.nextInt(protectionMin, protectionMax + 1)));
        setArmorDropChances(equipment, gear.dropChance());

        ItemStack weapon = equipment.getItemInMainHand();
        enchantWeapon(weapon, 5);
        equipment.setItemInMainHandDropChance(gear.dropChance());
    }

    private ItemStack armor(Material iron, Material diamond, double diamondChance, int protectionLevel) {
        Material material = ThreadLocalRandom.current().nextDouble() < diamondChance ? diamond : iron;
        return enchantedArmor(material, protectionLevel);
    }

    private ItemStack bossArmor(Material diamond, Material netherite, double netheriteChance, int protectionLevel) {
        Material material = ThreadLocalRandom.current().nextDouble() < netheriteChance ? netherite : diamond;
        ItemStack item = enchantedArmor(material, protectionLevel);
        if (ThreadLocalRandom.current().nextDouble() < 0.35) item.addUnsafeEnchantment(Enchantment.THORNS, 2);
        return item;
    }

    private ItemStack enchantedArmor(Material material, int protectionLevel) {
        ItemStack item = new ItemStack(material);
        item.addUnsafeEnchantment(Enchantment.PROTECTION, Math.max(1, protectionLevel));
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        if (protectionLevel >= 4 && ThreadLocalRandom.current().nextDouble() < 0.35) {
            item.addUnsafeEnchantment(Enchantment.THORNS, 2);
        }
        return item;
    }

    private void enchantWeapon(ItemStack item, int power) {
        if (item == null || item.getType().isAir()) return;
        switch (item.getType()) {
            case BOW -> {
                item.addUnsafeEnchantment(Enchantment.POWER, Math.max(1, power));
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
                if (power >= 5) item.addUnsafeEnchantment(Enchantment.PUNCH, 2);
            }
            case CROSSBOW -> {
                item.addUnsafeEnchantment(Enchantment.QUICK_CHARGE, 3);
                item.addUnsafeEnchantment(Enchantment.PIERCING, Math.min(4, Math.max(1, power)));
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            }
            case TRIDENT -> {
                item.addUnsafeEnchantment(Enchantment.IMPALING, Math.min(5, Math.max(1, power)));
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            }
            case WOODEN_SWORD, STONE_SWORD, IRON_SWORD, DIAMOND_SWORD, NETHERITE_SWORD,
                 WOODEN_AXE, STONE_AXE, IRON_AXE, DIAMOND_AXE, NETHERITE_AXE -> {
                item.addUnsafeEnchantment(Enchantment.SHARPNESS, Math.min(5, Math.max(1, power)));
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            }
            default -> { }
        }
    }

    private void setArmorDropChances(EntityEquipment equipment, float chance) {
        equipment.setHelmetDropChance(chance);
        equipment.setChestplateDropChance(chance);
        equipment.setLeggingsDropChance(chance);
        equipment.setBootsDropChance(chance);
    }

    private void applyVisualIdentity(LivingEntity entity, String structureId, boolean elite, boolean boss) {
        if (boss) {
            entity.setCustomName("§4☠ " + bossName(structureId));
            entity.setCustomNameVisible(true);
            entity.setGlowing(true);
            double y = Math.max(1.0, entity.getHeight() * 0.55);
            entity.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,
                    entity.getLocation().add(0.0, y, 0.0), 32, 0.45, 0.65, 0.45, 0.02);
            entity.getWorld().spawnParticle(Particle.ENCHANT,
                    entity.getLocation().add(0.0, y, 0.0), 36, 0.5, 0.7, 0.5, 0.08);
            return;
        }

        if (!elite) return;
        entity.setCustomName("§6✦ Элитный " + localizedMobName(entity.getType()));
        entity.setCustomNameVisible(true);
        entity.getWorld().spawnParticle(
                Particle.ENCHANT,
                entity.getLocation().add(0.0, Math.max(0.8, entity.getHeight() * 0.55), 0.0),
                24,
                0.35,
                0.45,
                0.35,
                0.05
        );
    }

    private String bossName(String structureId) {
        if (structureId == null) return "Хозяин Руин";
        return switch (structureId) {
            case "campsite" -> "Капитан Черноклык";
            case "windmill" -> "Мельничный Надзиратель";
            case "graveyard" -> "Костяной Жнец";
            case "lighthouse" -> "Смотритель Глубин";
            case "ship" -> "Капитан Бездны";
            case "castle" -> "Железный Палач";
            case "ice_castle" -> "Ледяной Стрелок";
            case "observatory" -> "Звёздный Пророк";
            default -> "Хозяин Руин";
        };
    }

    private String localizedMobName(EntityType type) {
        return switch (type) {
            case PILLAGER -> "налётчик";
            case VINDICATOR -> "поборник";
            case EVOKER -> "заклинатель";
            case SKELETON -> "скелет";
            case STRAY -> "зимогор";
            case WITHER_SKELETON -> "визер-скелет";
            case DROWNED -> "утопленник";
            case ZOMBIE -> "зомби";
            case HUSK -> "кадавр";
            case BOGGED -> "болотник";
            default -> type.name().toLowerCase().replace('_', ' ');
        };
    }

    private int randomLevel(RandomGenerator random, int min, int max) {
        int low = Math.max(1, Math.min(min, max));
        int high = Math.max(low, Math.max(min, max));
        return random.nextInt(low, high + 1);
    }
}
