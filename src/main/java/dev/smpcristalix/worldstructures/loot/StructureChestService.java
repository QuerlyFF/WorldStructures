package dev.smpcristalix.worldstructures.loot;

import dev.smpcristalix.worldstructures.config.WorldStructuresSettings;
import dev.smpcristalix.worldstructures.reward.RewardService;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Один раз добавляет бронзовые монеты в помеченные сундуки структур.
 */
public final class StructureChestService implements Listener {

    private final NamespacedKey structureChestKey;
    private final NamespacedKey rewardGeneratedKey;
    private final RewardService rewardService;
    private volatile WorldStructuresSettings settings;

    public StructureChestService(Plugin plugin, WorldStructuresSettings settings, RewardService rewardService) {
        structureChestKey = new NamespacedKey(plugin, "structure_chest");
        rewardGeneratedKey = new NamespacedKey(plugin, "structure_chest_reward_generated");
        this.rewardService = rewardService;
        this.settings = settings;
    }

    public void reload(WorldStructuresSettings newSettings) {
        this.settings = newSettings;
    }

    /**
     * Генератор структуры вызывает этот метод для каждого сундука/бочки внутри поставленной структуры.
     */
    public void markContainer(Container container) {
        container.getPersistentDataContainer().set(structureChestKey, PersistentDataType.BYTE, (byte) 1);
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

        WorldStructuresSettings.ChestSpec chest = settings.chest();
        RandomGenerator random = ThreadLocalRandom.current();
        if (random.nextDouble() >= chest.coinChance()) return;

        int low = Math.min(chest.amountMin(), chest.amountMax());
        int high = Math.max(chest.amountMin(), chest.amountMax());
        int amount = random.nextInt(low, high + 1);
        ItemStack coins = rewardService.createBronzeCoins(amount);
        Map<Integer, ItemStack> leftovers = event.getInventory().addItem(coins);

        if (!leftovers.isEmpty() && event.getPlayer() instanceof Player player) {
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(container.getLocation().add(0.5, 1.0, 0.5), leftover);
            }
        }
    }
}
