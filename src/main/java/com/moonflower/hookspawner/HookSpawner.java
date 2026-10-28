package com.moonflower.hookspawner;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class HookSpawner extends JavaPlugin implements Listener {

    private final List<TriggerData> triggers = new ArrayList<>();
    private FileConfiguration messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultMessages();
        loadMessages();

        if (!loadConfig()) {
            getLogger().severe(getConsoleMessage("config-error"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info(getConsoleMessage("plugin-enabled", "%amount%", String.valueOf(triggers.size())));
    }

    private void saveDefaultMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) {
            saveResource("messages.yml", false);
        }
    }

    private void loadMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        messages = YamlConfiguration.loadConfiguration(file);
    }

    private String getString(Player player, String key) {
        String lang = "en";
        if (player != null) {
            String locale = player.locale().toString();
            if (locale.startsWith("ru")) lang = "ru";
        }
        String path = lang + "." + key;
        String msg = messages.getString(path, messages.getString("en." + key, "Missing message: " + key));
        return msg != null ? msg.replace("&", "§") : "Missing message: " + key;
    }

    private String getConsoleMessage(String key, String... replacements) {
        String lang = getConfig().getString("console-language", "en").toLowerCase();
        String path = "console." + lang + "." + key;
        String msg = messages.getString(path, messages.getString("console.en." + key, "Missing console message: " + key));
        
        if (msg != null) {
            for (int i = 0; i < replacements.length; i += 2) {
                msg = msg.replace(replacements[i], replacements[i + 1]);
            }
        }
        return msg;
    }

    private boolean loadConfig() {
        triggers.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("triggers");
        if (section == null) {
            return false;
        }

        for (String key : section.getKeys(false)) {
            try {
                ConfigurationSection trigSec = section.getConfigurationSection(key);
                if (trigSec == null) continue;

                String itemStr = trigSec.getString("item");
                String mobStr = trigSec.getString("mob");
                if (itemStr == null || mobStr == null) continue;

                Material material = Material.valueOf(itemStr.toUpperCase());
                EntityType mobType = EntityType.valueOf(mobStr.toUpperCase());
                
                String rawName = trigSec.getString("name", "");
                String name = rawName != null ? rawName.replace("&", "§") : "";
                
                int amount = trigSec.getInt("amount", 1);
                boolean keepItem = trigSec.getBoolean("keep-item", false);
                boolean fly = trigSec.getBoolean("fly-to-player", true);

                triggers.add(new TriggerData(material, name, mobType, amount, keepItem, fly));
            } catch (IllegalArgumentException e) {
                getLogger().warning(getConsoleMessage("invalid-trigger", "%key%", key));
            }
        }
        return !triggers.isEmpty();
    }

    @EventHandler
    public void onFishCatch(@NotNull PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        
        Entity caught = event.getCaught();
        if (!(caught instanceof Item itemEntity)) return;

        ItemStack item = itemEntity.getItemStack();
        ItemMeta meta = item.getItemMeta();

        TriggerData matchedTrigger = null;

        for (TriggerData trig : triggers) {
            if (item.getType() == trig.material()) {
                if (trig.name().isEmpty() || (meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(trig.name()))) {
                    matchedTrigger = trig;
                    break;
                }
            }
        }

        if (matchedTrigger != null) {
            Player player = event.getPlayer();
            
            if (event.getHook() == null || !event.getHook().isValid()) return;
            Location hookLoc = event.getHook().getLocation();

            for (int i = 0; i < matchedTrigger.amount(); i++) {
                LivingEntity mob = (LivingEntity) hookLoc.getWorld().spawnEntity(hookLoc, matchedTrigger.mobType());

                if (matchedTrigger.flyToPlayer()) {
                    mob.setAI(false);
                    mob.setGravity(false);

                    Vector velocity = player.getLocation().toVector().subtract(mob.getLocation().toVector()).normalize();
                    velocity.multiply(1.5); 
                    velocity.setY(velocity.getY() + 0.2); 

                    mob.setVelocity(velocity);

                    new BukkitRunnable() {
                        int ticks = 0;
                        @Override
                        public void run() {
                            if (!player.isOnline() || !mob.isValid() || ticks > 40) {
                                if (mob.isValid()) { mob.setAI(true); mob.setGravity(true); }
                                this.cancel();
                                return;
                            }
                            
                            if (!mob.getWorld().equals(player.getWorld())) {
                                mob.setAI(true); mob.setGravity(true); this.cancel(); return;
                            }
                            if (mob.getLocation().distanceSquared(player.getLocation()) < 4.0) {
                                mob.setAI(true); mob.setGravity(true); this.cancel(); return;
                            }
                            if (mob.getVelocity().lengthSquared() < 0.1) {
                                mob.setAI(true); mob.setGravity(true); this.cancel(); return;
                            }
                            ticks++;
                        }
                    }.runTaskTimer(this, 1L, 1L);
                }
            }
            
            String spawnMessage = getString(player, "mob-spawned").replace("%mob%", matchedTrigger.mobType().name());
            player.sendMessage(spawnMessage);
            
            if (matchedTrigger.keepItem()) {
                // Player keeps the item, do nothing (vanilla pickup is preserved)
            } else {
                event.setCancelled(true);
                itemEntity.remove();
            }
        }
    }

    private record TriggerData(Material material, String name, EntityType mobType, int amount, boolean keepItem, boolean flyToPlayer) {}
}