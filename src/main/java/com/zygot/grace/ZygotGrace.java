package com.zygot.grace;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ZygotGrace extends JavaPlugin implements TabExecutor {

    private File dataFile;
    private YamlConfiguration data;

    // settings
    private String worldName;
    private int tagSeconds;
    private Set<String> allowedCommands;
    private long minSeconds;
    private long maxSeconds;

    // grace state
    private boolean graceActive;
    private long graceRemaining; // seconds left
    private long graceTotal;     // seconds it started with
    private int saveCounter;
    private final BossBar bar = BossBar.bossBar(Component.text("Grace Period"), 1.0f,
            BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
    private final Set<UUID> barViewers = new HashSet<>();

    private CombatListener combat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        graceRemaining = data.getLong("grace-remaining", 0L);
        graceTotal = data.getLong("grace-total", 0L);
        if (graceRemaining > 0) {
            graceActive = true;
            if (graceTotal < graceRemaining) graceTotal = graceRemaining;
        }

        combat = new CombatListener(this);
        getServer().getPluginManager().registerEvents(new GraceListener(this), this);
        getServer().getPluginManager().registerEvents(combat, this);

        PluginCommand cmd = getCommand("zgrace");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::secondTick, 20L, 20L);
        getLogger().info("ZygotGrace enabled for world '" + worldName + "'."
                + (graceActive ? " Grace period resumed: " + formatTime(graceRemaining) + " left." : ""));
    }

    @Override
    public void onDisable() {
        if (graceActive) saveGrace();
        for (UUID id : barViewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(bar);
        }
        barViewers.clear();
    }

    private void loadSettings() {
        worldName = getConfig().getString("world", "Survival");
        tagSeconds = Math.max(1, getConfig().getInt("combat-log.tag-seconds", 15));
        allowedCommands = new HashSet<>();
        for (String c : getConfig().getStringList("combat-log.allowed-commands")) {
            allowedCommands.add(c.toLowerCase(Locale.ROOT).replace("/", "").trim());
        }
        minSeconds = Math.max(1, getConfig().getLong("grace.min-hours", 1L)) * 3600L;
        maxSeconds = Math.max(1, getConfig().getLong("grace.max-hours", 20L)) * 3600L;
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /** True for the Survival world (name check ignores capital letters). */
    public boolean isSurvival(World world) {
        return world != null && world.getName().equalsIgnoreCase(worldName);
    }

    public boolean isGraceActive() {
        return graceActive;
    }

    public int tagSeconds() {
        return tagSeconds;
    }

    public Set<String> allowedCommands() {
        return allowedCommands;
    }

    public void forgetViewer(UUID id) {
        barViewers.remove(id);
    }

    /** The player responsible for a damage source (direct hit, arrow, TNT, tamed wolf...), or null. */
    public static Player playerSource(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player p) return p;
        if (damager instanceof Tameable pet && pet.getOwner() instanceof Player p) return p;
        return null;
    }

    // ------------------------------------------------------------------
    // Grace period
    // ------------------------------------------------------------------

    private void secondTick() {
        if (graceActive) {
            graceRemaining--;
            if (graceRemaining <= 0) {
                endGrace(true);
            } else {
                updateDisplay();
                if (graceRemaining <= 10) warn();
                if (++saveCounter >= 10) {
                    saveCounter = 0;
                    saveGrace();
                }
            }
        }
        combat.tick();
    }

    private void startGrace(long seconds) {
        graceActive = true;
        graceRemaining = seconds;
        graceTotal = seconds;
        saveCounter = 0;
        combat.clearAll(); // combat log is off during grace
        saveGrace();
        updateDisplay();
        Bukkit.broadcast(Component.text("Grace period started for " + formatTime(seconds)
                + "! No PvP and no fall damage in " + worldName + ".", NamedTextColor.GREEN));
    }

    private void endGrace(boolean natural) {
        graceActive = false;
        graceRemaining = 0;
        graceTotal = 0;
        for (UUID id : barViewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(bar);
        }
        barViewers.clear();
        saveGrace();
        Bukkit.broadcast(Component.text(natural
                ? "The grace period is over! PvP, fall damage and combat logging are now ON in " + worldName + "."
                : "The grace period was ended by an admin. PvP is now ON in " + worldName + ".",
                NamedTextColor.RED));
    }

    /** Updates the top-centre boss bar and shows it only to players in Survival. */
    private void updateDisplay() {
        float progress = graceTotal <= 0 ? 0f : (float) graceRemaining / (float) graceTotal;
        bar.progress(Math.max(0f, Math.min(1f, progress)));
        bar.color(graceRemaining <= 10 ? BossBar.Color.RED : BossBar.Color.GREEN);
        bar.name(Component.text("Grace Period: " + formatTime(graceRemaining),
                graceRemaining <= 10 ? NamedTextColor.RED : NamedTextColor.GREEN, TextDecoration.BOLD));

        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean inSurvival = isSurvival(p.getWorld());
            if (inSurvival && barViewers.add(p.getUniqueId())) {
                p.showBossBar(bar);
            } else if (!inSurvival && barViewers.remove(p.getUniqueId())) {
                p.hideBossBar(bar);
            }
        }
    }

    /** Last 10 seconds: big countdown title and a ping for everyone in Survival. */
    private void warn() {
        if (graceRemaining == 10) {
            Bukkit.broadcast(Component.text("The grace period ends in 10 seconds!", NamedTextColor.RED));
        }
        Title title = Title.title(
                Component.text(String.valueOf(graceRemaining), NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Grace period ending - PvP turns on!", NamedTextColor.YELLOW),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isSurvival(p.getWorld())) continue;
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        }
    }

    private void saveGrace() {
        data.set("grace-remaining", graceActive ? graceRemaining : 0L);
        data.set("grace-total", graceActive ? graceTotal : 0L);
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Time helpers
    // ------------------------------------------------------------------

    /** "1h", "90m", "2h30m", "5400s" or a plain number of hours. Returns -1 if invalid. */
    static long parseSeconds(String input) {
        String s = input.toLowerCase(Locale.ROOT).trim();
        try {
            if (s.matches("\\d{1,6}")) return Long.parseLong(s) * 3600L;
            if (!s.matches("(\\d{1,8}[hms])+")) return -1;
            Matcher m = Pattern.compile("(\\d+)([hms])").matcher(s);
            long total = 0;
            while (m.find()) {
                long v = Long.parseLong(m.group(1));
                switch (m.group(2)) {
                    case "h" -> total += v * 3600L;
                    case "m" -> total += v * 60L;
                    default -> total += v;
                }
            }
            return total;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    static String formatTime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format("%dh %02dm %02ds", h, m, s);
        if (m > 0) return String.format("%dm %02ds", m, s);
        return s + "s";
    }

    // ------------------------------------------------------------------
    // Command
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                if (!sender.hasPermission("zygotgrace.admin")) {
                    sender.sendMessage(Component.text("Only admins can do that.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /grace start <time>   e.g. 1h, 2h30m, 90m",
                            NamedTextColor.YELLOW));
                    return true;
                }
                String timeText = String.join("", Arrays.copyOfRange(args, 1, args.length));
                long seconds = parseSeconds(timeText);
                if (seconds < 0) {
                    sender.sendMessage(Component.text("Invalid time. Examples: 1h, 2h30m, 90m, 5 (= 5 hours).",
                            NamedTextColor.RED));
                } else if (seconds < minSeconds) {
                    sender.sendMessage(Component.text("The minimum grace period is "
                            + formatTime(minSeconds) + ".", NamedTextColor.RED));
                } else if (seconds > maxSeconds) {
                    sender.sendMessage(Component.text("The maximum grace period is "
                            + formatTime(maxSeconds) + ".", NamedTextColor.RED));
                } else {
                    startGrace(seconds); // starting again simply restarts it with the new time
                }
            }
            case "stop" -> {
                if (!sender.hasPermission("zygotgrace.admin")) {
                    sender.sendMessage(Component.text("Only admins can do that.", NamedTextColor.RED));
                    return true;
                }
                if (!graceActive) {
                    sender.sendMessage(Component.text("There is no grace period running.", NamedTextColor.YELLOW));
                } else {
                    endGrace(false);
                }
            }
            case "status" -> sender.sendMessage(Component.text(graceActive
                    ? "Grace period: " + formatTime(graceRemaining) + " left."
                    : "No grace period is running. PvP is ON in " + worldName + ".", NamedTextColor.GOLD));
            default -> sender.sendMessage(Component.text("Usage: /grace <start <time>|stop|status>",
                    NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String o : List.of("start", "stop", "status")) {
                if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(o);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            for (String o : List.of("1h", "2h", "5h", "10h", "20h")) {
                if (o.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(o);
            }
        }
        return out;
    }
}
