package dev.smpcristalix.worldstructures.loot;

import dev.smpcristalix.worldstructures.config.WorldStructuresSettings;
import dev.smpcristalix.worldstructures.reward.RewardService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Один раз генерирует только наш контролируемый лут в помеченных контейнерах структур.
 * Нативные loot-table из скачанных NBT принудительно отключаются.
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

    /** Просто ставит PDC-метку; используется админской командой и repair. */
    public void markContainer(Container container, String structureId) {
        container.getPersistentDataContainer().set(structureChestKey, PersistentDataType.BYTE, (byte) 1);
        container.getPersistentDataContainer().set(
                structureIdKey,
                PersistentDataType.STRING,
                structureId == null ? "unknown" : structureId.toLowerCase()
        );
        container.update(true, false);
    }

    /**
     * Вызывается только при ПЕРВИЧНОЙ установке NBT.
     * Удаляет исходный datapack loot и гарантирует, что содержимое создаём только мы.
     */
    public void initializeContainer(Container container, String structureId) {
        clearNativeLootTable(container);
        ownInventory(container).clear();
        container.getPersistentDataContainer().remove(rewardGeneratedKey);
        markContainer(container, structureId);
    }

    /** Снимок сундука нужен /ws repair, чтобы перепостройка NBT не создавала новый лут. */
    public RepairSnapshot snapshotForRepair(Container container) {
        boolean generated = isGenerated(container);
        clearNativeLootTable(container);

        Inventory inventory = ownInventory(container);
        // Старый unopened-container мог приехать из NBT с исходным содержимым. Его не сохраняем.
        if (!generated) inventory.clear();

        ItemStack[] original = inventory.getContents();
        ItemStack[] copy = new ItemStack[original.length];
        for (int i = 0; i < original.length; i++) {
            copy[i] = original[i] == null ? null : original[i].clone();
        }
        return new RepairSnapshot(copy, generated);
    }

    /**
     * Возвращает точное пользовательское состояние контейнера после repair.
     * Если старого снимка нет, контейнер запечатывается как уже сгенерированный:
     * ремонт никогда не должен создавать дополнительную попытку ценного лута.
     */
    public void restoreAfterRepair(Container container, String structureId, RepairSnapshot snapshot) {
        clearNativeLootTable(container);
        markContainer(container, structureId);
        Inventory inventory = ownInventory(container);

        if (snapshot == null) {
            inventory.clear();
            setGenerated(container, true);
            return;
        }

        inventory.clear();
        ItemStack[] restored = new ItemStack[snapshot.contents().length];
        for (int i = 0; i < snapshot.contents().length; i++) {
            ItemStack item = snapshot.contents()[i];
            restored[i] = item == null ? null : item.clone();
        }
        if (restored.length == inventory.getSize()) {
            inventory.setContents(restored);
        } else {
            for (int slot = 0; slot < Math.min(restored.length, inventory.getSize()); slot++) {
                inventory.setItem(slot, restored[slot]);
            }
        }
        setGenerated(container, snapshot.rewardGenerated());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        List<Container> containers = markedContainers(event.getInventory().getHolder());
        if (containers.isEmpty()) return;
        if (event.getInventory().getHolder() instanceof DoubleChest && containers.size() != 2) {
            // A player-owned chest connected across the protection boundary must never be
            // cleared or filled as if it were part of the NBT structure.
            event.setCancelled(true);
            if (event.getPlayer() instanceof Player player) {
                player.sendActionBar("§cНельзя объединять личный сундук с сундуком структуры.");
            }
            return;
        }

        boolean needsGeneration = containers.stream().anyMatch(container -> !isGenerated(container));
        if (!needsGeneration) return;

        // Если это старый instance, native loot мог успеть сгенерироваться до InventoryOpenEvent.
        // Чистим верхний inventory целиком и после этого добавляем только наш балансный лут.
        for (Container container : containers) clearNativeLootTable(container);
        event.getInventory().clear();
        for (Container container : containers) setGenerated(container, true);

        String structureId = containers.stream()
                .map(this::structureId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse("unknown");

        WorldStructuresSettings.StructureBossSpec structure = settings.structure(structureId);
        int dangerLevel = structure == null ? 1 : structure.dangerLevel();
        generateLoot(event.getInventory(), containers.getFirst(), structureId, dangerLevel);
    }

    /**
     * Хопперы/воронки не должны открывать скрытый путь к NBT-сундуку и обходить first-open логику.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (hasMarkedContainer(event.getSource().getHolder())
                || hasMarkedContainer(event.getDestination().getHolder())) {
            event.setCancelled(true);
        }
    }

    private void generateLoot(Inventory inventory, Container dropAnchor, String structureId, int dangerLevel) {
        RandomGenerator random = ThreadLocalRandom.current();
        WorldStructuresSettings.ChestSpec chest = settings.chest();
        double dangerChanceMultiplier = 1.0 + Math.max(0, dangerLevel - 1) * 0.15;

        if (random.nextDouble() < capped(chest.coinChance() * dangerChanceMultiplier)) {
            int low = Math.min(chest.amountMin(), chest.amountMax());
            int high = Math.max(chest.amountMin(), chest.amountMax());
            addLoot(inventory, rewardService.createBronzeCoins(random.nextInt(low, high + 1)), dropAnchor);
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
            if (valuable != null) addLoot(inventory, valuable, dropAnchor);
        }

        if (random.nextDouble() < capped(chest.mobLootChance() * dangerChanceMultiplier)) {
            addLoot(inventory, rollMobLoot(structureId, dangerLevel, random), dropAnchor);
        }
    }

    private ItemStack rollValuableLoot(WorldStructuresSettings.ChestSpec chest,
                                       int dangerLevel,
                                       RandomGenerator random) {
        int total = chest.enchantedBookWeight() + chest.experienceBottleWeight() + chest.preciousResourceWeight();
        if (total <= 0) return null;

        int roll = random.nextInt(total);
        if (roll < chest.enchantedBookWeight()) return enchantedBook(random);
        roll -= chest.enchantedBookWeight();
        if (roll < chest.experienceBottleWeight()) {
            int max = dangerLevel >= 3 ? 6 : 4;
            return new ItemStack(Material.EXPERIENCE_BOTTLE, random.nextInt(2, max + 1));
        }
        return preciousResource(dangerLevel, random);
    }

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

    private List<Container> markedContainers(InventoryHolder holder) {
        List<Container> result = new ArrayList<>(2);
        if (holder instanceof Container container) {
            if (isMarked(container)) result.add(container);
            return result;
        }
        if (holder instanceof DoubleChest doubleChest) {
            addMarkedSide(result, doubleChest.getLeftSide());
            addMarkedSide(result, doubleChest.getRightSide());
        }
        return result;
    }

    private void addMarkedSide(List<Container> result, InventoryHolder holder) {
        if (holder instanceof Container container && isMarked(container)) result.add(container);
    }

    private boolean hasMarkedContainer(InventoryHolder holder) {
        return !markedContainers(holder).isEmpty();
    }

    private boolean isMarked(Container container) {
        return container.getPersistentDataContainer().has(structureChestKey, PersistentDataType.BYTE);
    }

    private boolean isGenerated(Container container) {
        return container.getPersistentDataContainer().has(rewardGeneratedKey, PersistentDataType.BYTE);
    }

    private String structureId(Container container) {
        return container.getPersistentDataContainer().get(structureIdKey, PersistentDataType.STRING);
    }

    private void setGenerated(Container container, boolean generated) {
        if (generated) {
            container.getPersistentDataContainer().set(rewardGeneratedKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            container.getPersistentDataContainer().remove(rewardGeneratedKey);
        }
        container.update(true, false);
    }

    private void clearNativeLootTable(Container container) {
        if (container instanceof Lootable lootable) lootable.clearLootTable();
    }

    /** Для половины double chest берём именно 27 слотов этого блока, а не общий inventory на 54. */
    private Inventory ownInventory(Container container) {
        if (container instanceof Chest chest) return chest.getBlockInventory();
        return container.getInventory();
    }

    private void addLoot(Inventory inventory, ItemStack item, Container container) {
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

    public record RepairSnapshot(ItemStack[] contents, boolean rewardGenerated) {}
}
