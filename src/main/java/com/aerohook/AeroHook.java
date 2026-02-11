package com.aerohook;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * AeroHook - Professional Grappling Hook Plugin
 * 
 * A high-quality Minecraft grappling hook plugin with anti-cheat safety.
 * Compatible with Paper/Spigot 1.20.x - 1.21.x
 * 
 * @author AeroHook Team
 * @version 1.0.0
 */
public class AeroHook extends JavaPlugin implements Listener {

    // Cooldown tracking map
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    
    // Configuration cache for performance
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
    
    private Logger logger;

    /**
     * Called when the plugin is enabled
     */
    @Override
    public void onEnable() {
        // Initialize logger
        this.logger = this.getLogger();
        
        // Save default configuration file
        this.saveDefaultConfig();
        
        // Load configuration into memory
        this.loadConfiguration();
        
        // Register event listener
        PluginManager pluginManager = this.getServer().getPluginManager();
        pluginManager.registerEvents(this, this);
        
        // Log successful startup
        PluginDescriptionFile description = this.getDescription();
        this.logger.info("AeroHook v" + description.getVersion() + " has been enabled!");
        this.logger.info("Anti-Cheat Safe Grappling Hook loaded successfully.");
    }

    /**
     * Called when the plugin is disabled
     */
    @Override
    public void onDisable() {
        // Clear all cooldowns
        this.cooldowns.clear();
        
        // Log shutdown
        this.logger.info("AeroHook has been disabled!");
    }

    /**
     * Load configuration values into memory for performance
     */
    private void loadConfiguration() {
        FileConfiguration config = this.getConfig();
        
        // Load physics settings
        this.maxDistance = config.getDouble("physics.max-distance", 50.0);
        this.pullStrength = config.getDouble("physics.pull-strength", 1.5);
        this.verticalBoost = config.getDouble("physics.vertical-boost", 0.3);
        this.horizontalMultiplier = config.getDouble("physics.horizontal-multiplier", 1.0);
        this.velocitySmoothing = config.getDouble("physics.velocity-smoothing", 0.85);
        this.cooldownTicks = config.getLong("physics.cooldown", 20);
        
        // Load item settings
        String rawItemName = config.getString("item.name", "&b&lGrappling Hook");
        this.itemName = ChatColor.translateAlternateColorCodes('&', rawItemName);
        
        List<String> rawLore = config.getStringList("item.lore");
        this.itemLore = rawLore.stream()
            .map(line -> ChatColor.translateAlternateColorCodes('&', line))
            .collect(Collectors.toList());
        
        String materialName = config.getString("item.material", "FISHING_ROD");
        try {
            this.itemMaterial = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            this.logger.warning("Invalid material '" + materialName + "' in config, using FISHING_ROD");
            this.itemMaterial = Material.FISHING_ROD;
        }
        
        // Load effect settings
        this.soundsEnabled = config.getBoolean("sounds.enabled", true);
        this.effectsEnabled = config.getBoolean("effects.enabled", true);
        
        this.logger.info("Configuration loaded successfully.");
    }

    /**
     * Handle plugin commands
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Verify this is our command
        if (!command.getName().equalsIgnoreCase("grapplinghook")) {
            return false;
        }

        // Check admin permission
        if (!sender.hasPermission("grapplinghook.admin")) {
            sender.sendMessage(this.getMessage("no-permission"));
            return true;
        }

        // Check if arguments were provided
        if (args.length == 0) {
            sender.sendMessage(this.getMessage("usage"));
            return true;
        }

        // Handle subcommands
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "reload":
                // Reload configuration
                this.reloadConfig();
                this.loadConfiguration();
                sender.sendMessage(this.getMessage("reload-success"));
                return true;

            case "give":
                // Give grappling hook to player
                if (args.length < 2) {
                    sender.sendMessage(this.getMessage("usage"));
                    return true;
                }

                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(this.getMessage("player-not-found"));
                    return true;
                }

                this.giveGrapplingHook(target);
                String successMessage = this.getMessage("item-given");
                successMessage = successMessage.replace("{player}", target.getName());
                sender.sendMessage(successMessage);
                return true;

            default:
                sender.sendMessage(this.getMessage("usage"));
                return true;
        }
    }

    /**
     * Handle fishing rod events for grappling hook functionality
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        ItemStack item = inventory.getItemInMainHand();

        // Verify player is using a grappling hook
        if (!this.isGrapplingHook(item)) {
            return;
        }

        // Check usage permission
        if (!player.hasPermission("grapplinghook.use")) {
            event.setCancelled(true);
            return;
        }

        FishHook hook = event.getHook();
        PlayerFishEvent.State state = event.getState();
        
        // Only activate when hook lands on a block
        if (state != PlayerFishEvent.State.IN_GROUND) {
            return;
        }

        // Cancel the default fishing behavior
        event.setCancelled(true);

        // Check cooldown
        if (this.isOnCooldown(player)) {
            long remaining = this.getRemainingCooldown(player);
            double seconds = remaining / 20.0;
            String cooldownMessage = this.getMessage("cooldown");
            cooldownMessage = cooldownMessage.replace("{time}", String.format("%.1f", seconds));
            player.sendMessage(cooldownMessage);
            return;
        }

        Location hookLocation = hook.getLocation();
        Location playerLocation = player.getLocation();

        // Verify distance is within configured limit
        double distance = hookLocation.distance(playerLocation);
        if (distance > this.maxDistance) {
            String tooFarMessage = this.getMessage("too-far");
            tooFarMessage = tooFarMessage.replace("{max}", String.valueOf((int) this.maxDistance));
            player.sendMessage(tooFarMessage);
            return;
        }

        // Apply grappling physics
        this.applyGrapplingPhysics(player, hookLocation);

        // Set cooldown
        this.setCooldown(player);

        // Play sound effects
        if (this.soundsEnabled) {
            this.playSound(player, "hook");
            this.playSound(player, "pull");
        }

        // Spawn particles
        if (this.effectsEnabled) {
            this.spawnPullParticles(player, playerLocation);
        }
    }

    /**
     * Apply smooth, anti-cheat safe grappling hook physics
     * Uses vector mathematics for realistic pulling
     */
    private void applyGrapplingPhysics(Player player, Location hookLocation) {
        Location playerLocation = player.getLocation();
        
        // Calculate direction vector from player to hook
        Vector direction = hookLocation.toVector().subtract(playerLocation.toVector());
        
        // Normalize the direction vector (make it unit length)
        direction.normalize();
        
        // Apply configured pull strength
        direction.multiply(this.pullStrength);
        
        // Apply horizontal multiplier to X and Z components
        double newX = direction.getX() * this.horizontalMultiplier;
        double newZ = direction.getZ() * this.horizontalMultiplier;
        direction.setX(newX);
        direction.setZ(newZ);
        
        // Add vertical boost for upward movement
        double newY = direction.getY() + this.verticalBoost;
        direction.setY(newY);
        
        // Get player's current velocity
        Vector currentVelocity = player.getVelocity();
        
        // Smooth velocity transition to prevent anti-cheat flags
        // Formula: finalVelocity = currentVelocity * (1 - smoothing) + targetVelocity * smoothing
        Vector smoothedCurrent = currentVelocity.multiply(1.0 - this.velocitySmoothing);
        Vector smoothedTarget = direction.multiply(this.velocitySmoothing);
        Vector finalVelocity = smoothedCurrent.add(smoothedTarget);
        
        // Apply the calculated velocity to the player
        player.setVelocity(finalVelocity);
    }

    /**
     * Create a grappling hook item with custom name and lore
     */
    private ItemStack createGrapplingHook() {
        ItemStack item = new ItemStack(this.itemMaterial, 1);
        ItemMeta meta = item.getItemMeta();
        
        if (meta != null) {
            meta.setDisplayName(this.itemName);
            meta.setLore(new ArrayList<>(this.itemLore));
            item.setItemMeta(meta);
        }
        
        return item;
    }

    /**
     * Give a grappling hook to the specified player
     */
    private void giveGrapplingHook(Player player) {
        ItemStack hook = this.createGrapplingHook();
        PlayerInventory inventory = player.getInventory();
        inventory.addItem(hook);
        
        // Play sound effect
        if (this.soundsEnabled) {
            this.playSound(player, "launch");
        }
    }

    /**
     * Check if an item is a valid grappling hook
     */
    private boolean isGrapplingHook(ItemStack item) {
        // Null check
        if (item == null) {
            return false;
        }
        
        // Check material type
        if (item.getType() != this.itemMaterial) {
            return false;
        }

        // Check item meta
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        
        // Check display name
        if (!meta.hasDisplayName()) {
            return false;
        }

        // Verify the display name matches
        String displayName = meta.getDisplayName();
        return displayName.equals(this.itemName);
    }

    /**
     * Check if a player is on cooldown
     */
    private boolean isOnCooldown(Player player) {
        UUID playerId = player.getUniqueId();
        
        if (!this.cooldowns.containsKey(playerId)) {
            return false;
        }

        long lastUse = this.cooldowns.get(playerId);
        long currentTime = System.currentTimeMillis();
        long cooldownMillis = this.cooldownTicks * 50; // Convert ticks to milliseconds (1 tick = 50ms)

        return (currentTime - lastUse) < cooldownMillis;
    }

    /**
     * Get remaining cooldown time in ticks
     */
    private long getRemainingCooldown(Player player) {
        UUID playerId = player.getUniqueId();
        long lastUse = this.cooldowns.get(playerId);
        long currentTime = System.currentTimeMillis();
        long cooldownMillis = this.cooldownTicks * 50;
        long elapsed = currentTime - lastUse;
        long remaining = cooldownMillis - elapsed;
        
        // Convert milliseconds back to ticks
        return remaining / 50;
    }

    /**
     * Set cooldown for a player
     */
    private void setCooldown(Player player) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        this.cooldowns.put(playerId, currentTime);
    }

    /**
     * Play a configured sound effect
     */
    private void playSound(Player player, String soundKey) {
        try {
            FileConfiguration config = this.getConfig();
            String soundName = config.getString("sounds." + soundKey);
            
            if (soundName == null) {
                return;
            }
            
            Sound sound = Sound.valueOf(soundName);
            float volume = (float) config.getDouble("sounds.volume", 1.0);
            float pitch = (float) config.getDouble("sounds.pitch", 1.2);
            
            Location location = player.getLocation();
            player.playSound(location, sound, volume, pitch);
        } catch (IllegalArgumentException e) {
            this.logger.warning("Invalid sound configuration for: " + soundKey);
        } catch (Exception e) {
            this.logger.warning("Error playing sound: " + soundKey + " - " + e.getMessage());
        }
    }

    /**
     * Spawn particle effects when pulling
     */
    private void spawnPullParticles(Player player, Location location) {
        try {
            FileConfiguration config = this.getConfig();
            String particleName = config.getString("effects.pull-particle", "CLOUD");
            int amount = config.getInt("effects.particle-amount", 20);
            
            Particle particle = Particle.valueOf(particleName);
            World world = player.getWorld();
            
            world.spawnParticle(particle, location, amount, 0.5, 0.5, 0.5, 0.1);
        } catch (IllegalArgumentException e) {
            this.logger.warning("Invalid particle configuration: " + e.getMessage());
        } catch (Exception e) {
            this.logger.warning("Error spawning particles: " + e.getMessage());
        }
    }

    /**
     * Get a formatted message from the configuration
     */
    private String getMessage(String key) {
        FileConfiguration config = this.getConfig();
        
        String prefix = config.getString("messages.prefix", "&8[&bAeroHook&8]&r ");
        prefix = ChatColor.translateAlternateColorCodes('&', prefix);
        
        String message = config.getString("messages." + key, "&cMessage not found: " + key);
        message = ChatColor.translateAlternateColorCodes('&', message);
        
        return prefix + message;
    }
}
