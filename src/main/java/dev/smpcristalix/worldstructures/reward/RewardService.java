package dev.smpcristalix.worldstructures.reward;

import dev.smpcristalix.worldstructures.config.WorldStructuresSettings;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Централизованно выдаёт физические награды структур.
 * Позже этот класс можно заменить мостом к отдельному Economy/Stats plugin без изменения логики мобов.
 */
public final class RewardService {

    private final NamespacedKey bronzeCoinKey;
    private final NamespacedKey statShardKey;
    private volatile WorldStructuresSettings settings;

    public RewardService(Plugin plugin, WorldStructuresSettings settings) {
        this.bronzeCoinKey = new NamespacedKey(plugin, "bronze_coin");
        this.statShardKey = new NamespacedKey(plugin, "stat_shard");
        this.settings = settings;
    }

    public void reload(WorldStructuresSettings newSettings) {
        this.settings = newSettings;
    }

    public void dropNormalMobReward(EntityDeathEvent event) {
        dropByRule(event, settings.normalCoins());
    }

    public void dropEliteMobReward(EntityDeathEvent event) {
        dropByRule(event, settings.eliteCoins());
    }

    public void dropBossReward(EntityDeathEvent event) {
        RandomGenerator random = ThreadLocalRandom.current();
        WorldStructuresSettings.BossSpec boss = settings.boss();

        int coins = random.nextInt(
                Math.min(boss.bronzeCoinsMin(), boss.bronzeCoinsMax()),
                Math.max(boss.bronzeCoinsMin(), boss.bronzeCoinsMax()) + 1
        );
        event.getDrops().add(createBronzeCoins(coins));

        if (random.nextDouble() < boss.shardDropChance()) {
            event.getDrops().add(createStatShard());
        }
    }

    public ItemStack createBronzeCoins(int amount) {
        ItemStack item = new ItemStack(Material.COPPER_INGOT, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6Бронзовая монета");
        meta.getPersistentDataContainer().set(bronzeCoinKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createStatShard() {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§dОсколок характеристик");
        meta.getPersistentDataContainer().set(statShardKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isBronzeCoin(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(bronzeCoinKey, PersistentDataType.BYTE);
    }

    public boolean isStatShard(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(statShardKey, PersistentDataType.BYTE);
    }

    private void dropByRule(EntityDeathEvent event, WorldStructuresSettings.DropRule rule) {
        RandomGenerator random = ThreadLocalRandom.current();
        if (!rule.shouldDrop(random)) return;
        event.getDrops().add(createBronzeCoins(rule.randomAmount(random)));
    }
}
