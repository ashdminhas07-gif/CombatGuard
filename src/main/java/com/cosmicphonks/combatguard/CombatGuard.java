package com.cosmicphonks.combatguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class CombatGuard extends JavaPlugin implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, UUID> opponents = new HashMap<>();
    private final Map<UUID, Long> lastCombatNotice = new HashMap<>();
    private final Map<UUID, PendingLogout> pending = new HashMap<>();
    private final Set<UUID> combatLogging = new HashSet<>();
    private File pendingFile;
    private YamlConfiguration pendingYaml;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pendingFile = new File(getDataFolder(), "pending.yml");
        loadPending();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickCombat, 0L, 20L);
        for (UUID id : new ArrayList<>(pending.keySet())) {
            PendingLogout record = pending.get(id);
            if (record.punished) continue;
            long delay = Math.max(1L, (record.deadline - System.currentTimeMillis() + 999L) / 50L);
            Bukkit.getScheduler().runTaskLater(this, () -> punishIfExpired(id), delay);
        }
        getLogger().info("CombatGuard enabled.");
    }

    @Override
    public void onDisable() {
        savePending();
        combatUntil.clear();
        opponents.clear();
        lastCombatNotice.clear();
    }

    private void loadPending() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        pendingYaml = YamlConfiguration.loadConfiguration(pendingFile);
        ConfigurationSection root = pendingYaml.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                PendingLogout p = new PendingLogout();
                p.deadline = s.getLong("deadline");
                p.opponent = UUID.fromString(s.getString("opponent", ""));
                p.world = s.getString("world", "");
                p.x = s.getDouble("x"); p.y = s.getDouble("y"); p.z = s.getDouble("z");
                p.yaw = (float) s.getDouble("yaw"); p.pitch = (float) s.getDouble("pitch");
                p.punished = s.getBoolean("punished", false);
                p.main = readItems(s.getConfigurationSection("main"));
                p.armor = readItems(s.getConfigurationSection("armor"));
                p.extra = readItems(s.getConfigurationSection("extra"));
                pending.put(UUID.fromString(key), p);
            } catch (Exception ex) {
                getLogger().warning("Could not load pending logout " + key + ": " + ex.getMessage());
            }
        }
    }

    private List<ItemStack> readItems(ConfigurationSection section) {
        List<ItemStack> result = new ArrayList<>();
        if (section == null) return result;
        for (String k : section.getKeys(false)) {
            Object value = section.get(k);
            if (value instanceof ItemStack item && !item.getType().isAir()) result.add(item.clone());
            else result.add(null);
        }
        return result;
    }

    private void savePending() {
        if (pendingYaml == null) return;
        pendingYaml.set("players", null);
        for (Map.Entry<UUID, PendingLogout> entry : pending.entrySet()) {
            String path = "players." + entry.getKey();
            PendingLogout p = entry.getValue();
            pendingYaml.set(path + ".deadline", p.deadline);
            pendingYaml.set(path + ".opponent", p.opponent.toString());
            pendingYaml.set(path + ".world", p.world);
            pendingYaml.set(path + ".x", p.x); pendingYaml.set(path + ".y", p.y); pendingYaml.set(path + ".z", p.z);
            pendingYaml.set(path + ".yaw", p.yaw); pendingYaml.set(path + ".pitch", p.pitch);
            pendingYaml.set(path + ".punished", p.punished);
            writeItems(path + ".main", p.main);
            writeItems(path + ".armor", p.armor);
            writeItems(path + ".extra", p.extra);
        }
        try { pendingYaml.save(pendingFile); }
        catch (IOException ex) { getLogger().severe("Failed to save pending logout data: " + ex.getMessage()); }
    }

    private void writeItems(String path, List<ItemStack> items) {
        pendingYaml.set(path, null);
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            pendingYaml.set(path + "." + i, item == null ? "AIR_SLOT" : item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = getPlayerDamager(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        tag(attacker, victim);
    }

    private Player getPlayerDamager(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player p) return p;
        }
        return null;
    }

    private void tag(Player a, Player b) {
        long until = System.currentTimeMillis() + getConfig().getLong("combat.duration-seconds", 20) * 1000L;
        combatUntil.put(a.getUniqueId(), until);
        combatUntil.put(b.getUniqueId(), until);
        opponents.put(a.getUniqueId(), b.getUniqueId());
        opponents.put(b.getUniqueId(), a.getUniqueId());
        long duration = getConfig().getLong("combat.duration-seconds", 20);
        lastCombatNotice.put(a.getUniqueId(), duration);
        lastCombatNotice.put(b.getUniqueId(), duration);
        String format = getConfig().getString("combat.chat-format", "&c{time}s &fCombat").replace("{time}", Long.toString(duration));
        a.sendMessage(message(format));
        b.sendMessage(message(format));
    }

    private boolean inCombat(Player player) {
        return combatUntil.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    private void tickCombat() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Long until = combatUntil.get(id);
            if (until == null) continue;
            long remaining = until - now;
            if (remaining <= 0) {
                combatUntil.remove(id);
                opponents.remove(id);
                lastCombatNotice.remove(id);
                player.sendMessage(message(getConfig().getString("combat.end-message", "&fYou're Out of &cCombat&f!")));
            } else {
                long seconds = (remaining + 999L) / 1000L;
                Long lastNotice = lastCombatNotice.get(id);
                if (seconds > 0 && seconds % 5 == 0 && (lastNotice == null || lastNotice.longValue() != seconds)) {
                    String format = getConfig().getString("combat.chat-format", "&c{time}s &fCombat")
                            .replace("{time}", Long.toString(seconds));
                    player.sendMessage(message(format));
                    lastCombatNotice.put(id, seconds);
                }
            }
        }
    }

    private Component message(String text) { return LEGACY.deserialize(text == null ? "" : text); }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!inCombat(player)) return;
        if (event.getItem() != null && event.getItem().getType() == Material.ENDER_PEARL
                && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            player.sendMessage(message(getConfig().getString("combat.pearl-blocked-message", "&cYou cannot use Ender Pearls while in combat!")));
            return;
        }
        Block block = event.getClickedBlock();
        if (block != null && block.getType() == Material.ENDER_CHEST
                && (event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            player.sendMessage(message(getConfig().getString("combat.blocked-message", "&cYou cannot do that while in combat!")));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPearlLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.EnderPearl pearl)) return;
        ProjectileSource shooter = pearl.getShooter();
        if (shooter instanceof Player player && inCombat(player)) {
            event.setCancelled(true);
            player.sendMessage(message(getConfig().getString("combat.pearl-blocked-message", "&cYou cannot use Ender Pearls while in combat!")));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!inCombat(player)) {
            combatUntil.remove(id);
            opponents.remove(id);
            lastCombatNotice.remove(id);
            return;
        }

        // Treat every combat disconnect as an immediate /kill.
        UUID opponentId = opponents.getOrDefault(id, id);
        Location location = player.getLocation();
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> loot = new ArrayList<>();
        loot.addAll(cloneItems(inventory.getStorageContents()));
        loot.addAll(cloneItems(inventory.getArmorContents()));
        loot.addAll(cloneItems(inventory.getExtraContents()));

        combatLogging.add(id);
        try {
            player.setHealth(0.0);
        } catch (IllegalArgumentException ignored) {
            // The player may already be in a death transition; loot is still handled below.
        } finally {
            combatLogging.remove(id);
        }

        inventory.clear();
        player.saveData();
        dropCombatLogoutLoot(location, opponentId, loot);

        combatUntil.remove(id);
        opponents.remove(id);
        lastCombatNotice.remove(id);

        Player opponent = Bukkit.getPlayer(opponentId);
        if (opponent != null) {
            opponent.sendMessage(message(getConfig().getString("logout-message",
                    "&cYour opponent combat logged; their items have been dropped.")));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCombatLogoutDeath(PlayerDeathEvent event) {
        if (!combatLogging.contains(event.getEntity().getUniqueId())) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    private void dropCombatLogoutLoot(Location location, UUID owner, List<ItemStack> loot) {
        World world = location.getWorld();
        if (world == null) return;
        for (ItemStack item : loot) {
            if (item == null || item.getType().isAir()) continue;
            Item dropped = world.dropItemNaturally(location, item.clone());
            dropped.setOwner(owner);
            dropped.setPickupDelay(0);
        }
    }

    private List<ItemStack> cloneItems(ItemStack[] source) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack item : source) result.add(item == null ? null : item.clone());
        return result;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        PendingLogout record = pending.get(id);
        if (record == null) return;
        if (!record.punished && System.currentTimeMillis() < record.deadline) {
            PlayerInventory inv = player.getInventory();
            inv.clear();
            inv.setStorageContents(toArray(record.main));
            inv.setArmorContents(toArray(record.armor));
            inv.setExtraContents(toArray(record.extra));
            player.saveData();
            pending.remove(id);
            savePending();
            player.sendMessage(message(getConfig().getString("rejoin-message", "&aReconnected safely. Your combat logout penalty was cancelled.")));
        } else {
            if (!record.punished) punish(record);
            player.getInventory().clear();
            player.saveData();
            pending.remove(id);
            savePending();
        }
    }

    private ItemStack[] toArray(List<ItemStack> items) {
        return items.toArray(new ItemStack[0]);
    }

    private void punishIfExpired(UUID id) {
        PendingLogout record = pending.get(id);
        if (record == null || record.punished || System.currentTimeMillis() < record.deadline) return;
        punish(record);
        record.punished = true;
        savePending();
        Player opponent = Bukkit.getPlayer(record.opponent);
        if (opponent != null) opponent.sendMessage(message(getConfig().getString("logout-message", "&cYour opponent combat logged; their items have been dropped.")));
    }

    private void punish(PendingLogout record) {
        World world = Bukkit.getWorld(record.world);
        if (world == null) {
            getLogger().warning("Could not drop combat logout items: world " + record.world + " is unavailable.");
            return;
        }
        Location location = new Location(world, record.x, record.y, record.z, record.yaw, record.pitch);
        for (ItemStack item : concat(record.main, record.armor, record.extra)) {
            if (item == null || item.getType().isAir()) continue;
            Item dropped = world.dropItemNaturally(location, item.clone());
            dropped.setOwner(record.opponent);
            dropped.setPickupDelay(0);
        }
    }

    private List<ItemStack> concat(List<ItemStack> a, List<ItemStack> b, List<ItemStack> c) {
        List<ItemStack> all = new ArrayList<>();
        all.addAll(a); all.addAll(b); all.addAll(c);
        return all;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("combatguard") && args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("combatguard.reload")) {
                sender.sendMessage(message("&cNo permission."));
                return true;
            }
            reloadConfig();
            sender.sendMessage(message("&aCombatGuard config reloaded."));
            return true;
        }
        return false;
    }

    private static final class PendingLogout {
        long deadline;
        UUID opponent;
        String world;
        double x, y, z;
        float yaw, pitch;
        boolean punished;
        List<ItemStack> main = new ArrayList<>();
        List<ItemStack> armor = new ArrayList<>();
        List<ItemStack> extra = new ArrayList<>();
    }
}
