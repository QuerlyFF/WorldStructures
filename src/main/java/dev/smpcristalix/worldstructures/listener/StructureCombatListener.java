package dev.smpcristalix.worldstructures.listener;

import dev.smpcristalix.worldstructures.boss.MiniBossService;
import dev.smpcristalix.worldstructures.mob.StructureMobService;
import dev.smpcristalix.worldstructures.reward.RewardService;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.EnumSet;
import java.util.Set;

/**
 * Награды структурных мобов, усиление дальнего урона и анти-абуз мини-боссов.
 */
public final class StructureCombatListener implements Listener {

    private static final Set<EntityDamageEvent.DamageCause> BLOCKED_BOSS_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.LAVA,
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.SUFFOCATION,
            EntityDamageEvent.DamageCause.DROWNING,
            EntityDamageEvent.DamageCause.FALL,
            EntityDamageEvent.DamageCause.FLY_INTO_WALL,
            EntityDamageEvent.DamageCause.CRAMMING,
            EntityDamageEvent.DamageCause.CONTACT,
            EntityDamageEvent.DamageCause.FREEZE
    );

    private final StructureMobService mobService;
    private final RewardService rewardService;
    private final MiniBossService miniBossService;

    public StructureCombatListener(StructureMobService mobService,
                                   RewardService rewardService,
                                   MiniBossService miniBossService) {
        this.mobService = mobService;
        this.rewardService = rewardService;
        this.miniBossService = miniBossService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossEnvironmentDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) return;
        if (!mobService.isMiniBoss(living)) return;
        if (BLOCKED_BOSS_CAUSES.contains(event.getCause())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!mobService.isStructureMob(entity)) return;

        if (mobService.isMiniBoss(entity)) {
            rewardService.dropBossReward(event);
            miniBossService.onBossDeath(entity);
            return;
        }

        // Призванные боссом миньоны не дают монеты, чтобы нельзя было фармить их бесконечно.
        if (mobService.isSummonedMob(entity)) return;

        if (mobService.isEliteMob(entity)) rewardService.dropEliteMobReward(event);
        else rewardService.dropNormalMobReward(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRangedDamage(EntityDamageByEntityEvent event) {
        LivingEntity shooter = resolveShooter(event);
        if (shooter == null || !mobService.isStructureMob(shooter)) return;

        double multiplier = mobService.projectileDamageMultiplier(shooter);
        if (multiplier != 1.0) event.setDamage(event.getDamage() * multiplier);
    }

    private LivingEntity resolveShooter(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof LivingEntity living) return living;
        }
        if (event.getDamager() instanceof EvokerFangs fangs) return fangs.getOwner();
        return null;
    }
}
