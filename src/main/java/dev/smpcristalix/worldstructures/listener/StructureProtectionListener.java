package dev.smpcristalix.worldstructures.listener;

import dev.smpcristalix.worldstructures.runtime.StructureInstanceService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Защищает физические блоки наших структур от грифа и обходных способов разрушения.
 * Администратор с worldstructures.protection.bypass может редактировать их вручную.
 */
public final class StructureProtectionListener implements Listener {

    private final StructureInstanceService instances;

    public StructureProtectionListener(StructureInstanceService instances) {
        this.instances = instances;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (canBypass(event.getPlayer())) return;
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (canBypass(event.getPlayer())) return;
        if (isProtected(event.getBlockPlaced()) || wouldMergeWithProtectedChest(event.getBlockPlaced())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (canBypass(event.getPlayer())) return;
        if (instances.isInside(event.getEntity().getLocation())) {
            event.setCancelled(true);
            if (event.getPlayer() != null) deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (canBypass(event.getPlayer())) return;
        if (instances.isInside(event.getEntity().getLocation())) {
            event.setCancelled(true);
            if (event.getPlayer() != null) deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (!instances.isInside(event.getEntity().getLocation())) return;
        if (event instanceof HangingBreakByEntityEvent byEntity
                && byEntity.getRemover() instanceof Player player
                && canBypass(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProtectedEntityInteract(PlayerInteractEntityEvent event) {
        if (canBypass(event.getPlayer())) return;
        if ((event.getRightClicked() instanceof Hanging || event.getRightClicked() instanceof ArmorStand)
                && instances.isInside(event.getRightClicked().getLocation())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProtectedEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ArmorStand)
                || !instances.isInside(event.getEntity().getLocation())) return;
        if (event.getDamager() instanceof Player player && canBypass(player)) return;
        event.setCancelled(true);
    }

    /**
     * Некоторые vanilla-преобразования блока (strip/path/till/wax/scrape) идут через interact,
     * а не через BlockBreak/BlockPlace. Блокируем только инструменты, которые реально меняют блок,
     * поэтому сундуки, двери, кнопки и прочие обычные взаимодействия остаются доступными.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMutatingInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || canBypass(event.getPlayer())) return;
        Block clicked = event.getClickedBlock();
        if (!isProtected(clicked) || !isMutatingTool(event.getItem())) return;
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (canBypass(event.getPlayer())) return;
        Block target = event.getBlock().getRelative(event.getBlockFace());
        if (isProtected(target)) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (canBypass(event.getPlayer())) return;
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
            deny(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isProtected(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (!(event.getBlock().getBlockData() instanceof Directional directional)) return;
        if (isProtected(event.getBlock().getRelative(directional.getFacing()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isProtected(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (isProtected(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (isProtected(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (isProtected(event.getToBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (isProtected(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (isProtected(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (isProtected(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (canBypass(event.getPlayer())) return;
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
            if (event.getPlayer() != null) deny(event.getPlayer());
            return;
        }
        for (BlockState state : event.getBlocks()) {
            if (instances.isInside(state.getLocation())) {
                event.setCancelled(true);
                if (event.getPlayer() != null) deny(event.getPlayer());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (isProtected(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        for (BlockState state : event.getBlocks()) {
            if (instances.isInside(state.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (isProtected(event.getBlock())
                || isProtected(event.getBlock().getRelative(event.getDirection()))) {
            event.setCancelled(true);
            return;
        }
        for (Block block : event.getBlocks()) {
            if (isProtected(block)
                    || isProtected(block.getRelative(event.getDirection()))
                    || isProtected(block.getRelative(event.getDirection().getOppositeFace()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (isProtected(event.getBlock())
                || isProtected(event.getBlock().getRelative(event.getDirection()))) {
            event.setCancelled(true);
            return;
        }
        for (Block block : event.getBlocks()) {
            if (isProtected(block)
                    || isProtected(block.getRelative(event.getDirection()))
                    || isProtected(block.getRelative(event.getDirection().getOppositeFace()))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private boolean isMutatingTool(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Material type = item.getType();
        String name = type.name();
        return name.endsWith("_AXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || type == Material.SHEARS
                || type == Material.HONEYCOMB
                || type == Material.BONE_MEAL
                || type == Material.FLINT_AND_STEEL;
    }

    private boolean isProtected(Block block) {
        return block != null && instances.isInside(block.getLocation());
    }

    private boolean wouldMergeWithProtectedChest(Block placed) {
        Material type = placed.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) return false;
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block neighbor = placed.getRelative(face);
            if (neighbor.getType() == type && isProtected(neighbor)) return true;
        }
        return false;
    }

    private boolean canBypass(Player player) {
        return player != null && player.hasPermission("worldstructures.protection.bypass");
    }

    private void deny(Player player) {
        player.sendActionBar("§cЭта структура защищена.");
    }
}
