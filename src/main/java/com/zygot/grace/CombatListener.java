package com.zygot.grace;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Combat log for the Survival world only: a PvP hit tags both players, tagged players can't use
 * commands (except the allowed ones), and logging out while tagged kills the player.
 * Does nothing during the grace period.
 */
public final class CombatListener implements Listener {

    private final ZygotGrace plugin;
    private final Map<UUID, Long> tagUntil = new HashMap<>(); // player -> millis when the tag ends

    public CombatListener(ZygotGrace plugin) {
        this.plugin = plugin;
    }

    public boolean isTagged(Player p) {
        Long until = tagUntil.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public void clearAll() {
        tagUntil.clear();
    }

    private void tag(Player p) {
        boolean already = isTagged(p);
        tagUntil.put(p.getUniqueId(), System.currentTimeMillis() + plugin.tagSeconds() * 1000L);
        if (!already) {
            p.sendMessage(Component.text("You are in combat! Don't log out for "
                    + plugin.tagSeconds() + " seconds.", NamedTextColor.RED));
        }
    }

    /** Runs once a second from the main plugin: countdown and tag expiry. */
    public void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = tagUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || plugin.isGraceActive() || !plugin.isSurvival(p.getWorld())) {
                it.remove();
                continue;
            }
            long left = entry.getValue() - now;
            if (left <= 0) {
                it.remove();
                p.sendActionBar(Component.empty());
                p.sendMessage(Component.text("You are no longer in combat.", NamedTextColor.GREEN));
            } else {
                p.sendActionBar(Component.text("In combat: " + ((left + 999) / 1000) + "s", NamedTextColor.RED));
            }
        }
    }

    // ------------------------------------------------------------------

    /** A real (not cancelled) PvP hit in Survival tags both players. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (plugin.isGraceActive()) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = ZygotGrace.playerSource(e.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (!plugin.isSurvival(victim.getWorld()) || !plugin.isSurvival(attacker.getWorld())) return;

        tag(victim);
        tag(attacker);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!isTagged(p) || p.hasPermission("zygotgrace.bypass")) return;
        if (plugin.isGraceActive() || !plugin.isSurvival(p.getWorld())) return;

        String msg = e.getMessage().trim();
        if (msg.length() < 2) return;
        String label = msg.substring(1).split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1); // /minecraft:tp -> tp

        if (plugin.allowedCommands().contains(label)) return;

        e.setCancelled(true);
        p.sendMessage(Component.text("You can't use commands while in combat!", NamedTextColor.RED));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Long until = tagUntil.remove(p.getUniqueId());
        if (until == null || until <= System.currentTimeMillis()) return;
        if (Bukkit.isStopping()) return;
        if (plugin.isGraceActive() || !plugin.isSurvival(p.getWorld())) return;

        // Kicks by an admin or the server are not combat logging.
        PlayerQuitEvent.QuitReason reason = e.getReason();
        if (reason == PlayerQuitEvent.QuitReason.KICKED
                || reason == PlayerQuitEvent.QuitReason.ERRONEOUS_STATE) return;

        p.setHealth(0.0);
        Bukkit.broadcast(Component.text(p.getName() + " logged out during combat and was killed!",
                NamedTextColor.RED));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        tagUntil.remove(e.getEntity().getUniqueId());
    }
}
