package com.zygot.grace;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** During the grace period: no PvP and no fall damage in the Survival world. */
public final class GraceListener implements Listener {

    private final ZygotGrace plugin;

    public GraceListener(ZygotGrace plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!plugin.isGraceActive()) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!plugin.isSurvival(victim.getWorld())) return;

        // No fall damage (ender pearls count as fall damage too).
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            e.setCancelled(true);
            return;
        }

        // No PvP: hits, arrows, tridents, TNT, tamed wolves, harming potions...
        if (e instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = ZygotGrace.playerSource(byEntity.getDamager());
            if (attacker != null && !attacker.getUniqueId().equals(victim.getUniqueId())) {
                e.setCancelled(true);
                attacker.sendActionBar(Component.text("PvP is disabled during the grace period.",
                        NamedTextColor.YELLOW));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.forgetViewer(e.getPlayer().getUniqueId());
    }
}
