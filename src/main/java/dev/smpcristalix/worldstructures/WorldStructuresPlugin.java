package dev.smpcristalix.worldstructures;

import dev.smpcristalix.worldstructures.boss.MiniBossService;
import dev.smpcristalix.worldstructures.config.WorldStructuresSettings;
import dev.smpcristalix.worldstructures.listener.StructureCombatListener;
import dev.smpcristalix.worldstructures.loot.StructureChestService;
import dev.smpcristalix.worldstructures.mob.StructureMobService;
import dev.smpcristalix.worldstructures.reward.RewardService;
import dev.smpcristalix.worldstructures.structure.StructurePlacementService;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Главный класс WorldStructures.
 */
public final class WorldStructuresPlugin extends JavaPlugin {

    private WorldStructuresSettings settings;
    private StructureMobService mobService;
    private RewardService rewardService;
    private MiniBossService miniBossService;
    private StructureChestService chestService;
    private StructurePlacementService placementService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = WorldStructuresSettings.from(getConfig());

        mobService = new StructureMobService(this, settings);
        rewardService = new RewardService(this, settings);
        miniBossService = new MiniBossService(this, settings, mobService);
        chestService = new StructureChestService(this, settings, rewardService);
        placementService = new StructurePlacementService(this, settings, mobService, miniBossService, chestService);
        placementService.loadTemplates();

        getServer().getPluginManager().registerEvents(
                new StructureCombatListener(mobService, rewardService, miniBossService), this
        );
        getServer().getPluginManager().registerEvents(chestService, this);
        miniBossService.start();
        registerCommand();

        getLogger().info("WorldStructures включён. NBT шаблонов: " + placementService.loadedTemplateCount());
    }

    @Override
    public void onDisable() {
        if (miniBossService != null) miniBossService.stop();
    }

    private void registerCommand() {
        PluginCommand command = getCommand("worldstructures");
        if (command == null) {
            getLogger().severe("Команда worldstructures отсутствует в plugin.yml");
            return;
        }
        command.setExecutor((sender, ignoredCommand, ignoredLabel, args) -> executeCommand(sender, args));
    }

    private boolean executeCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§6WorldStructures §7— §f" + (settings.enabled() ? "включён" : "выключен"));
            sender.sendMessage("§7Конфигов структур: §f" + settings.structures().size());
            sender.sendMessage("§7Загружено NBT: §f" + placementService.loadedTemplateCount());
            sender.sendMessage("§7Якорей мини-боссов: §f" + miniBossService.anchorCount());
            sender.sendMessage("§7Живых мини-боссов: §f" + miniBossService.aliveBossCount());
            sender.sendMessage("§7Респавн босса: §f" + (settings.boss().respawnTicks() / 20 / 60) + " мин");
            sender.sendMessage("§7Осколок с босса: §f" + percent(settings.boss().shardDropChance()));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            settings = WorldStructuresSettings.from(getConfig());
            mobService.reload(settings);
            rewardService.reload(settings);
            miniBossService.reload(settings);
            chestService.reload(settings);
            placementService.reload(settings);
            sender.sendMessage("§aWorldStructures перезагружен.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cЭта команда доступна только игроку.");
            return true;
        }

        if (args[0].equalsIgnoreCase("place")) {
            if (args.length < 2) {
                player.sendMessage("§cИспользование: /ws place <structureId>");
                return true;
            }
            String structureId = args[1].toLowerCase();
            try {
                StructurePlacementService.PlacementResult result = placementService.place(structureId, player.getLocation());
                player.sendMessage("§aСтруктура §f" + result.structureId() + " §aпоставлена.");
                player.sendMessage("§7Поворот: §f" + result.rotation()
                        + " §7| Размер: §f" + result.sizeX() + "x" + result.sizeY() + "x" + result.sizeZ());
                player.sendMessage("§7Охрана: §f" + result.mobsSpawned()
                        + " §7| Контейнеры: §f" + result.containersMarked());
                player.sendMessage("§7Boss anchor: §f" + result.bossAnchorId());
            } catch (IllegalArgumentException | IllegalStateException exception) {
                player.sendMessage("§c" + exception.getMessage());
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("mark")) {
            if (args.length < 2) {
                player.sendMessage("§cИспользование: /ws mark <structureId>");
                return true;
            }
            String structureId = args[1].toLowerCase();
            if (settings.structure(structureId) == null) {
                player.sendMessage("§cНеизвестная структура. Доступно: §f" + String.join(", ", settings.structures().keySet()));
                return true;
            }
            String anchorId = miniBossService.registerAnchor(structureId, player.getLocation());
            player.sendMessage("§aЯкорь мини-босса создан: §f" + anchorId);
            return true;
        }

        if (args[0].equalsIgnoreCase("mob")) {
            if (args.length < 3) {
                player.sendMessage("§cИспользование: /ws mob <structureId> <entityType>");
                return true;
            }
            String structureId = args[1].toLowerCase();
            if (settings.structure(structureId) == null) {
                player.sendMessage("§cНеизвестная структура: " + structureId);
                return true;
            }
            try {
                EntityType type = EntityType.valueOf(args[2].toUpperCase());
                mobService.spawnStructureMob(player.getLocation(), type, structureId);
                player.sendMessage("§aСтруктурный моб создан.");
            } catch (IllegalArgumentException exception) {
                player.sendMessage("§cНекорректный EntityType: " + args[2]);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("markchest")) {
            Block target = player.getTargetBlockExact(6);
            if (target == null || !(target.getState() instanceof Container container)) {
                player.sendMessage("§cПосмотри на сундук/бочку не дальше 6 блоков.");
                return true;
            }
            chestService.markContainer(container);
            player.sendMessage("§aКонтейнер помечен как сундук структуры.");
            return true;
        }

        player.sendMessage("§cИспользование: /ws <status|reload|place|mark|mob|markchest>");
        return true;
    }

    private String percent(double chance) {
        return String.format("%.1f%%", chance * 100.0);
    }
}
