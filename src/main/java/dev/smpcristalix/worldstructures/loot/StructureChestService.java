package dev.smpcristalix.worldstructures.loot;

import dev.smpcristalix.worldstructures.config.WorldStructuresSettings;
import dev.smpcristalix.worldstructures.reward.RewardService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Один раз генерирует награды в помеченных сундуках структур.
 */
public final class StructureChestService implements Listener {

    private static final Enchantment[] BOOK_ENCHANTS = {
            Enchantment.PROTECTION,
            Enchantment.SHARPNESS,
            Enchantment.POWER,
            Enchantment.UNBREAKING,
            Enchantment.QUICK_CHARGE,
            Enchantment.IMPALING
    };

    private final NamespacedKey structureChestKey;
    private final NamespacedKey structureIdKey;
    private final NamespacedKey rewardGeneratedKey;
    private final RewardService rewardService;
    private volatile WorldStructuresSettings settings;

    public StructureChestService(Plugin plugin, WorldStructuresSettings settings, RewardService rewardService) {
        structureChestKey = new NamespacedKey(plugin, "structure_chest");
        structureIdKey = new NamespacedKey(plugin, "structure_chest_id");
        rewardGeneratedKey = new NamespacedKey(plugin, "structure_chest_reward_generated");
        this.rewardService = rewardService;
        this.settings = settings;
    }

    public void reload(WorldStructuresSettings newSettings) {
        this.settings = newSettings;
    }

    public void markContainer(Container container) {
        markContainer(container, "unknown");
    }

    /**
     * Генератор структуры вызывает этот метод для каждого сундука/бочки внутри поставленной структуры.
     */
    public void markContainer(Container container, String structureId) {
        container.getPersistentDataContainer().set(structureChestKey, PersistentDataType.BYTE, (byte) 1);
        container.getPersistentDataContainer().set(
                structureIdKey,
                PersistentDataType.STRING,
                structureId == null ? "unknown" : structureId.toLowerCase()
        );
        container.update(true, false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof Container container)) return;
        if (!container.getPersistentDataContainer().has(structureChestKey, PersistentDataType.BYTE)) return;
        if (container.getPersistentDataContainer().has(rewardGeneratedKey, PersistentDataType.BYTE)) return;

        container.getPersistentDataContainer().set(rewardGeneratedKey, PersistentDataType.BYTE, (byte) 1);
        container.update(true, false);

        String structureId = container.getPersistentDataContainer().get(structureIdKey, PersistentDataType.STRING);
        WorldStructuresSettings.StructureBossSpec structure = settings.structure(structureId);
        int dangerLevel = structure == null ? 1 : structure.dangerLevel();

        RandomGenerator random = ThreadLocalRandom.current();
        WorldStructuresSettings.ChestSpec chest = settings.chest();
        Player player = event.getPlayer() instanceof Player p ? p : null;

        // Чем опаснее и реже структура, тем немного выше шанс хорошего содержимого.
        double dangerChanceMultiplier = 1.0 + Math.max(0, dangerLevel - 1) * 0.15;

        if (random.nextDouble() < capped(chest.coinChance() * dangerChanceMultiplier)) {
            int low = Math.min(chest.amountMin(), chest.amountMax());
            int high = Math.max(chest.amountMin(), chest.amountMax());
            addLoot(event.getInventory(), rewardService.createBronzeCoins(random.nextInt(low, high + 1)), container, player);
        }

        int rollsMin = Math.min(chest.valuableRollsMin(), chest.valuableRollsMax());
        int rollsMax = Math.max(chest.valuableRollsMin(), chest.valuableRollsMax());
        int rolls = random.nextInt(rollsMin, rollsMax + 1);
        if (dangerLevel >= 3) rolls++;
        if (dangerLevel >= 4) rolls++;

        double valuableChance = capped(chest.valuableRollChance() * (1.0 + Math.max(0, dangerLevel - 1) * 0.10));
        for (int i = 0; i < rolls; i++) {
            if (random.nextDouble() >= valuableChance) continue;
            ItemStack valuable = rollValuableLoot(chest, dangerLevel, random);
            if (valuable != null) addLoot(event.getInventory(), valuable, container, player);
        }

        if (random.nextDouble() < capped(chest.mobLootChance() * dangerChanceMultiplier)) {
            addLoot(event.getInventory(), rollMobLoot(structureId, dangerLevel, random), container, player);
        }
    }

    private ItemStack rollValuableLoot(WorldStructuresSettings.ChestSpec chest,
                                       int dangerLevel,
                                       RandomGenerator random) {
        int total = chest.enchantedBookWeight() + chest.experienceBottleWeight() + chest.preciousResourceWeight();
        if (total <= 0) return null;

        int roll = random.nextInt(total);
        if (roll < chest.enchantedBookWeight()) {
            return enchantedBook(random);
        }
        roll -= chest.enchantedBookWeight();
        if (roll < chest.experienceBottleWeight()) {
            int max = dangerLevel >= 3 ? 6 : 4;
            return new ItemStack(Material.EXPERIENCE_BOTTLE, random.nextInt(2, max + 1));
        }
        return preciousResource(dangerLevel, random);
    }

    /**
     * Все книги из структур ограничены I-II уровнем, чтобы сундуки не заменяли стол зачарований.
     */
    private ItemStack enchantedBook(RandomGenerator random) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        Enchantment enchantment = BOOK_ENCHANTS[random.nextInt(BOOK_ENCHANTS.length)];
        int level = random.nextInt(1, 3);
        meta.addStoredEnchant(enchantment, level, true);
        book.setItemMeta(meta);
        return book;
    }

    private ItemStack preciousResource(int dangerLevel, RandomGenerator random) {
        // Алмазы заметно реже золота/изумрудов и в небольшом количестве.
        int roll = random.nextInt(100);
        if (dangerLevel >= 2 && roll < 18 + dangerLevel * 3) {
            int amount = dangerLevel >= 4 && random.nextDouble() < 0.25 ? 2 : 1;
            return new ItemStack(Material.DIAMOND, amount);
        }
        if (roll < 58) {
            int max = dangerLevel >= 3 ? 4 : 3;
            return new ItemStack(Material.EMERALD, random.nextInt(1, max + 1));
        }
        int maxGold = dangerLevel >= 3 ? 5 : 4;
        return new ItemStack(Material.GOLD_INGOT, random.nextInt(2, maxGold + 1));
    }

    /**
     * Небольшая часть обычного дропа охраны переносится в сундуки тематически.
     */
    private ItemStack rollMobLoot(String structureId, int dangerLevel, RandomGenerator random) {
        String id = structureId == null ? "unknown" : structureId.toLowerCase();
        return switch (id) {
            case "graveyard", "ice_castle" -> switch (random.nextInt(3)) {
                case 0 -> new ItemStack(Material.BONE, random.nextInt(3, 9));
                case 1 -> new ItemStack(Material.ARROW, random.nextInt(4, 11));
                default -> new ItemStack(Material.COAL, random.nextInt(1, 4));
            };
            case "ship", "lighthouse" -> {
                int roll = random.nextInt(100);
                if (roll < 10 + dangerLevel * 2) yield new ItemStack(Material.NAUTILUS_SHELL, 1);
                if (roll < 50) yield new ItemStack(Material.COPPER_INGOT, random.nextInt(1, 4));
                yield new ItemStack(Material.ROTTEN_FLESH, random.nextInt(3, 9));
            }
            default -> {
                if (random.nextDouble() < 0.10) yield new ItemStack(Material.CROSSBOW, 1);
                yield new ItemStack(Material.ARROW, random.nextInt(4, 13));
            }
        };
    }

    private void addLoot(Inventory inventory, ItemStack item, Container container, Player player) {
        if (item == null || item.getType().isAir()) return;
        Map<Integer, ItemStack> leftovers = inventory.addItem(item);
        if (leftovers.isEmpty()) return;

        for (ItemStack leftover : leftovers.values()) {
            container.getWorld().dropItemNaturally(container.getLocation().add(0.5, 1.0, 0.5), leftover);
        }
    }

    private double capped(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
