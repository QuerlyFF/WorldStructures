package dev.smpcristalix.worldstructures.listener;

import dev.smpcristalix.worldstructures.mob.StructureMobService;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * Даёт элитным мобам и мини-боссам понятный визуальный образ.
 * Проверка выполняется на следующий тик, потому что PDC-метки структурного моба
 * ставятся сразу после Bukkit spawnEntity(...).
 */
public final class StructureVisualIdentityListener implements Listener {

    private final Plugin plugin;
    private final StructureMobService mobService;

    public StructureVisualIdentityListener(Plugin plugin, StructureMobService mobService) {
        this.plugin = plugin;
        this.mobService = mobService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> applyIdentity(living));
    }

    private void applyIdentity(LivingEntity entity) {
        if (!entity.isValid() || entity.isDead() || !mobService.isStructureMob(entity)) return;

        if (mobService.isMiniBoss(entity)) {
            applyBossIdentity(entity);
            return;
        }

        if (mobService.isEliteMob(entity)) {
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
    }

    private void applyBossIdentity(LivingEntity boss) {
        String structureId = mobService.structureId(boss);
        boss.setCustomName("§4☠ " + bossName(structureId));
        boss.setCustomNameVisible(true);
        boss.setGlowing(true);

        double y = Math.max(1.0, boss.getHeight() * 0.55);
        boss.getWorld().spawnParticle(
                Particle.SOUL_FIRE_FLAME,
                boss.getLocation().add(0.0, y, 0.0),
                32,
                0.45,
                0.65,
                0.45,
                0.02
        );
        boss.getWorld().spawnParticle(
                Particle.ENCHANT,
                boss.getLocation().add(0.0, y, 0.0),
                36,
                0.5,
                0.7,
                0.5,
                0.08
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
}
