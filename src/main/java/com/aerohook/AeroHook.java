package com.aerohook;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class AeroHook extends JavaPlugin implements Listener {

    // Cooldown tracking
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    
    // Configuration cache
    private double maxDistance;
    private double pullStrength;
    private double verticalBoost;
    private double horizontalMultiplier;
    private double velocitySmoothing;
    private long cooldownTicks;
    
    private String itemName;
    private List<String> itemLore;
    private Material itemMaterial;
    
    private boolean soundsEnabled;
    private boolean effectsEnabled;

    @Override
    public void onEnable() {
        // Save default config
        saveDefaultConfig();
        
        // Load configuration
        loadConfiguration();
        
        // Register events
        getServer().getPluginManager().registerEvents(this, this);
        
        // Log startup
        getLogger().info("AeroHook v" + getDescription().getVersion() + " has been enabled!");
        getLogger().info("Anti-Cheat Safe Grappling Hook loaded successfully.");
    }

    @Override
    public void onDisable() {
        // Clear cooldowns
        cooldowns.clear();
        
        getLogger().info("AeroHook has been disabled!");
    }

    /**
     * Load configuration values into memory
     */
    private void loadConfiguration() {
        FileConfiguration config = getConfig();
        
        // Physics settings
        maxDistance = config.getDouble("physics.max-distance", 50.0);
        pullStrength = config.getDouble("physics.pull-strength", 1.5);
        verticalBoost = config.getDouble("physics.vertical-boost", 0.3);
        horizontalMultiplier = config.getDouble("physics.horizontal-multiplier", 1.0);
        velocitySmoothing = config.getDouble("physics.velocity-smoothing", 0.85);
        cooldownTicks = config.getLong("physics.cooldown", 20);
        
        // Item settings
        itemName = ChatColor.translateAlternateColorCodes('&', 
            config.getString("item.name", "&b&lGrappling Hook"));
        itemLore = config.getStringList("item.lore").stream()
            .map(line -> ChatColor.translateAlternateColorCodes('&', line))
            .collect(Collectors.toList());
        itemMaterial = Material.valueOf(config.getString("item.material", "FISHING_ROD"));
        
        // Effects
        soundsEnabled = config.getBoolean("sounds.enabled", true);
        effectsEnabled = config.getBoolean("effects.enabled", true);
    }

    /**
     * Command handler
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("grapplinghook")) {
            return false;
        }

        if (!sender.hasPermission("grapplinghook.admin")) {
            sender.sendMessage(getMessage("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(getMessage("usage"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                reloadConfig();
                loadConfiguration();
                sender.sendMessage(getMessage("reload-success"));
                return true;

            case "give":
                if (args.length < 2) {
                    sender.sendMessage(getMessage("usage"));
                    return true;
                }

                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(getMessage("player-not-found"));
                    return true;
                }

                giveGrapplingHook(target);
                sender.sendMessage(getMessage("item-given")
                    .replace("{player}", target.getName()));
                return true;

            default:
                sender.sendMessage(getMessage("usage"));
                return true;
        }
    }

    /**
     * Handle fishing rod events for grappling hook
     */
    @EventHandler
    public void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        // Check if player is using a grappling hook
        if (!isGrapplingHook(item)) {
            return;
        }

        // Check permission
        if (!player.hasPermission("grapplinghook.use")) {
            event.setCancelled(true);
            return;
        }

        FishHook hook = event.getHook();
        
        // Only activate when hook lands in ground/block
        if (event.getState() != PlayerFishEvent.State.IN_GROUND) {
            return;
        }

        event.setCancelled(true);

        // Check cooldown
        if (isOnCooldown(player)) {
            long remaining = getRemainingCooldown(player);
            player.sendMessage(getMessage("cooldown")
                .replace("{time}", String.format("%.1f", remaining / 20.0)));
            return;
        }

        Location hookLocation = hook.getLocation();
        Location playerLocation = player.getLocation();

        // Check distance
        double distance = hookLocation.distance(playerLocation);
        if (distance > maxDistance) {
            player.sendMessage(getMessage("too-far")
                .replace("{max}", String.valueOf((int) maxDistance)));
            return;
        }

        // Apply grappling hook physics
        applyGrapplingPhysics(player, hookLocation);

        // Set cooldown
        setCooldown(player);

        // Play effects
        if (soundsEnabled) {
            playSound(player, "hook");
            playSound(player, "pull");
        }

        if (effectsEnabled) {
            player.getWorld().spawnParticle(
                Particle.valueOf(getConfig().getString("effects.pull-particle", "CLOUD")),
                playerLocation,
                getConfig().getInt("effects.particle-amount", 20),
                0.5, 0.5, 0.5, 0.1
            );
        }
    }

    /**
     * Apply smooth, anti-cheat safe grappling physics
     */
    private void applyGrapplingPhysics(Player player, Location hookLocation) {
        Location playerLocation = player.getLocation();
        
        // Calculate direction vector from player to hook
        Vector direction = hookLocation.toVector().subtract(playerLocation.toVector());
        
        // Normalize and apply pull strength
        direction.normalize();
        direction.multiply(pullStrength);
        
        // Apply horizontal multiplier to X and Z components
        direction.setX(direction.getX() * horizontalMultiplier);
        direction.setZ(direction.getZ() * horizontalMultiplier);
        
        // Add vertical boost
        direction.setY(direction.getY() + verticalBoost);
        
        // Get current velocity for smoothing
        Vector currentVelocity = player.getVelocity();
        
        // Smooth velocity transition (prevents AC flags)
        Vector finalVelocity = currentVelocity.multiply(1 - velocitySmoothing)
            .add(direction.multiply(velocitySmoothing));
        
        // Apply velocity to player
        player.setVelocity(finalVelocity);
    }

    /**
     * Create a grappling hook item
     */
    private ItemStack createGrapplingHook() {
        ItemStack item = new ItemStack(itemMaterial);
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(itemName);
            meta.setLore(new ArrayList<>(itemLore)); // Fixed ArrayList diamond operator
            item.setItemMeta(meta);
        }
        
        return item;
    }

    /**
     * Give grappling hook to player
     */
    private void giveGrapplingHook(Player player) {
        player.getInventory().addItem(createGrapplingHook());
        
        if (soundsEnabled) {
            playSound(player, "launch");
        }
    }

    /**
     * Check if item is a grappling hook
     */
    private boolean isGrapplingHook(ItemStack item) {
        if (item == null || item.getType() != itemMaterial) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return false;
        }

        return meta.getDisplayName().equals(itemName);
    }

    /**
     * Cooldown management
     */
    private boolean isOnCooldown(Player player) {
        if (!cooldowns.containsKey(player.getUniqueId())) {
            return false;
        }

        long lastUse = cooldowns.get(player.getUniqueId());
        long currentTime = System.currentTimeMillis();
        long cooldownMillis = cooldownTicks * 50; // Convert ticks to milliseconds

        return (currentTime - lastUse) < cooldownMillis;
    }

    private long getRemainingCooldown(Player player) {
        long lastUse = cooldowns.get(player.getUniqueId());
        long currentTime = System.currentTimeMillis();
        long cooldownMillis = cooldownTicks * 50;
        long remaining = cooldownMillis - (currentTime - lastUse);
        
        return remaining / 50; // Convert back to ticks
    }

    private void setCooldown(Player player) {
        cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Play sound effect
     */
    private void playSound(Player player, String soundKey) {
        try {
            Sound sound = Sound.valueOf(getConfig().getString("sounds." + soundKey));
            float volume = (float) getConfig().getDouble("sounds.volume", 1.0);
            float pitch = (float) getConfig().getDouble("sounds.pitch", 1.2);
            
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Exception e) {
            getLogger().warning("Invalid sound configuration for: " + soundKey);
        }
    }

    /**
     * Get formatted message from config
     */
    private String getMessage(String key) {
        String prefix = ChatColor.translateAlternateColorCodes('&',
            getConfig().getString("messages.prefix", "&8[&bAeroHook&8]&r "));
        String message = ChatColor.translateAlternateColorCodes('&',
            getConfig().getString("messages." + key, "&cMessage not found: " + key));
        
        return prefix + message;
    }
}
