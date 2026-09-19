package dev.smpcristalix.worldstructures;

import dev.smpcristalix.worldstructures.boss.MiniBossService;
import dev.smpcristalix.worldstructures.config.WorldStructuresSettings;
import dev.smpcristalix.worldstructures.generation.NaturalStructureGenerationListener;
import dev.smpcristalix.worldstructures.listener.StructureCombatListener;
import dev.smpcristalix.worldstructures.listener.StructureProtectionListener;
import dev.smpcristalix.worldstructures.loot.StructureChestService;
import dev.smpcristalix.worldstructures.mob.StructureMobService;
import dev.smpcristalix.worldstructures.reward.RewardService;
import dev.smpcristalix.worldstructures.runtime.StructureInstanceService;
import dev.smpcristalix.worldstructures.structure.StructurePlacementService;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Главный класс WorldStructures. */
public final class WorldStructuresPlugin extends JavaPlugin {

    private WorldStructuresSettings settings;
    private StructureMobService mobService;
    private RewardService rewardService;
    private MiniBossService miniBossService;
    private StructureChestService chestService;
    private StructureInstanceService instanceService;
    private StructurePlacementService placementService;
    private NaturalStructureGenerationListener generationListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = WorldStructuresSettings.from(getConfig());

        mobService = new StructureMobService(this, settings);
        rewardService = new RewardService(this, settings);
        miniBossService = new MiniBossService(this, settings, mobService);
        chestService = new StructureChestService(this, settings, rewardService);
        instanceService = new StructureInstanceService(this, settings, mobService);
        placementService = new StructurePlacementService(
                this, settings, mobService, miniBossService, chestService, instanceService
        );
        instanceService.attachPlacementService(placementService);
        placementService.loadTemplates();
        generationListener = new NaturalStructureGenerationListener(this, settings, placementService);

        getServer().getPluginManager().registerEvents(
                new StructureCombatListener(mobService, rewardService, miniBossService), this
        );
        getServer().getPluginManager().registerEvents(
                new StructureProtectionListener(instanceService), this
        );
        getServer().getPluginManager().registerEvents(chestService, this);
        getServer().getPluginManager().registerEvents(generationListener, this);

        instanceService.start();
        miniBossService.start();
        registerCommand();

        getLogger().info("WorldStructures включён. NBT шаблонов: " + placementService.loadedTemplateCount());
    }

    @Override
    public void onDisable() {
        if (generationListener != null) generationListener.stop();
        if (instanceService != null) instanceService.stop();
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
            sender.sendMessage("§7Сгенерировано регионов: §f" + generationListener.generatedRegionCount());
            sender.sendMessage("§7Экземпляров структур: §f" + instanceService.instanceCount());
            sender.sendMessage("§7Якорей мини-боссов: §f" + miniBossService.anchorCount());
            sender.sendMessage("§7Живых мини-боссов: §f" + miniBossService.aliveBossCount());
            sender.sendMessage("§7Респавн босса: §f" + (settings.boss().respawnTicks() / 20 / 60) + " мин");
            sender.sendMessage("§7Респавн охраны: §f" + getConfig().getLong("runtime.guard-respawn-seconds", 7200L) / 60 + " мин");
            sender.sendMessage("§7Защита структур: §aвключена");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            settings = WorldStructuresSettings.from(getConfig());
            mobService.reload(settings);
            rewardService.reload(settings);
            miniBossService.reload(settings);
            chestService.reload(settings);
            instanceService.reload(settings);
            placementService.reload(settings);
            generationListener.reload(settings);
            sender.sendMessage("§aWorldStructures перезагружен.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cЭта команда доступна только игроку.");
            return true;
        }

        if (args[0].equalsIgnoreCase("locate")) {
            if (args.length < 2) {
                player.sendMessage("§cИспользование: /ws locate <structureId>");
                return true;
            }
            StructureInstanceService.InstanceView instance = instanceService.nearest(player.getLocation(), args[1]);
            if (instance == null) {
                player.sendMessage("§cСгенерированных структур такого типа пока не найдено.");
                return true;
            }
            Location center = instance.bounds().center();
            long distance = Math.round(Math.sqrt(horizontalDistanceSquared(player.getLocation(), center)));
            player.sendMessage("§6Ближайшая §f" + instance.structureId() + " §7— §f"
                    + center.getBlockX() + " " + center.getBlockY() + " " + center.getBlockZ()
                    + " §7(~" + distance + " блоков)");
            player.sendMessage("§7Instance: §f" + instance.instanceId());
            return true;
        }

        if (args[0].equalsIgnoreCase("boss")) {
            if (args.length < 2 || !(args[1].equalsIgnoreCase("respawn") || args[1].equalsIgnoreCase("kill"))) {
                player.sendMessage("§cИспользование: /ws boss <respawn|kill> [structureId]");
                return true;
            }
            String structureId = args.length >= 3 ? args[2] : null;
            MiniBossService.BossAnchorInfo boss = miniBossService.nearest(player.getLocation(), structureId);
            if (boss == null) {
                player.sendMessage("§cПодходящий boss-anchor не найден.");
                return true;
            }
            boolean ok = args[1].equalsIgnoreCase("respawn")
                    ? miniBossService.forceRespawn(boss.id())
                    : miniBossService.forceRemove(boss.id());
            player.sendMessage(ok ? "§aКоманда применена к §f" + boss.id() : "§cНе удалось изменить босса.");
            return true;
        }

        if (args[0].equalsIgnoreCase("reset")) {
            if (args.length < 2) {
                player.sendMessage("§cИспользование: /ws reset <structureId>");
                return true;
            }
            StructureInstanceService.InstanceView instance = instanceService.nearest(player.getLocation(), args[1]);
            if (instance == null) {
                player.sendMessage("§cЭкземпляр структуры не найден.");
                return true;
            }
            instanceService.resetGuards(instance.instanceId());
            miniBossService.forceRespawn(instance.instanceId());
            player.sendMessage("§aСтруктура сброшена: охрана и мини-босс восстановлены. Сундуки не перезаполнялись.");
            return true;
        }

        if (args[0].equalsIgnoreCase("repair")) {
            if (args.length < 2) {
                player.sendMessage("§cИспользование: /ws repair <instanceId>");
                return true;
            }
            StructureInstanceService.InstanceView instance = instanceService.get(args[1]);
            if (instance == null) {
                player.sendMessage("§cInstance не найден: §f" + args[1]);
                return true;
            }
            StructurePlacementService.RepairResult result = placementService.repair(instance);
            if (!result.success()) {
                player.sendMessage("§cRepair не выполнен: §f" + result.message());
                return true;
            }
            instanceService.resetGuards(instance.instanceId());
            miniBossService.forceRespawn(instance.instanceId());
            player.sendMessage("§aСтруктура восстановлена из NBT: §f" + instance.instanceId());
            player.sendMessage("§7Контейнеров восстановлено без обновления лута: §f" + result.containersRestored());
            return true;
        }

        if (args[0].equalsIgnoreCase("debug")) {
            StructureInstanceService.InstanceView nearest = instanceService.nearest(player.getLocation(), null);
            player.sendMessage("§6WorldStructures debug");
            player.sendMessage("§7Instances: §f" + instanceService.instanceCount()
                    + " §7| Bosses: §f" + miniBossService.aliveBossCount() + "/" + miniBossService.anchorCount());
            player.sendMessage("§7Leash: §f" + getConfig().getDouble("runtime.boss-leash-radius", 64.0)
                    + " §7| BossBar: §f" + getConfig().getDouble("runtime.bossbar-radius", 48.0));
            player.sendMessage("§7Guard respawn: §f" + getConfig().getLong("runtime.guard-respawn-seconds", 7200L) + " сек");
            if (nearest != null) {
                Location center = nearest.bounds().center();
                player.sendMessage("§7Nearest: §f" + nearest.instanceId() + " §7(" + nearest.structureId() + ") §f"
                        + center.getBlockX() + " " + center.getBlockY() + " " + center.getBlockZ());
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("place")) {
            if (args.length < 2) {
                player.sendMessage("§cИспользование: /ws place <structureId>");
                return true;
            }
            try {
                StructurePlacementService.PlacementResult result = placementService.place(args[1].toLowerCase(), player.getLocation());
                player.sendMessage("§aСтруктура §f" + result.structureId() + " §aпоставлена.");
                player.sendMessage("§7Instance: §f" + result.instanceId()
                        + " §7| Размер: §f" + result.sizeX() + "x" + result.sizeY() + "x" + result.sizeZ());
                player.sendMessage("§7Охрана: §f" + result.mobsSpawned() + " §7| Контейнеры: §f" + result.containersMarked());
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
            String structureId = args.length >= 2 ? args[1].toLowerCase() : "unknown";
            chestService.markContainer(container, structureId);
            player.sendMessage("§aКонтейнер помечен как сундук структуры §f" + structureId + "§a.");
            return true;
        }

        player.sendMessage("§cИспользование: /ws <status|reload|locate|boss|reset|repair|debug|place|mark|mob|markchest>");
        return true;
    }

    private double horizontalDistanceSquared(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
