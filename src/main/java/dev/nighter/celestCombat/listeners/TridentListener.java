package dev.nighter.celestCombat.listeners;

import dev.nighter.celestCombat.CelestCombat;
import dev.nighter.celestCombat.Scheduler;
import dev.nighter.celestCombat.combat.CombatManager;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class TridentListener implements Listener {
    private final CelestCombat plugin;
    private final CombatManager combatManager;

    private final Map<UUID, Scheduler.Task> tridentCountdownTasks = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> activeTridents = new ConcurrentHashMap<>();
    private final Map<UUID, Location> riptideOriginalLocations = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTridentUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        Action action = event.getAction();

        if (combatManager.isWorldBlacklisted(player)) {
            return;
        }

        if (item != null && item.getType() == Material.TRIDENT &&
            (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {

            if (combatManager.isTridentBanned(player)) {
                event.setCancelled(true);
                sendBannedMessage(player);
                return;
            }

            if (item.containsEnchantment(Enchantment.RIPTIDE)) {
                if (combatManager.isTridentOnCooldown(player)) {
                    event.setCancelled(true);
                    sendCooldownMessage(player);
                    return;
                } else {
                    riptideOriginalLocations.put(player.getUniqueId(), player.getLocation().clone());
                }
            } else {
                if (combatManager.isTridentOnCooldown(player)) {
                    event.setCancelled(true);
                    sendCooldownMessage(player);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRiptideUse(PlayerRiptideEvent event) {
        Player player = event.getPlayer();

        if (combatManager.isWorldBlacklisted(player)) {
            return;
        }

        if (combatManager.isTridentBanned(player)) {
            sendBannedMessage(player);
            rollbackRiptide(player);
            return;
        }

        if (combatManager.isTridentOnCooldown(player)) {
            sendCooldownMessage(player);
            rollbackRiptide(player);
            return;
        }

        combatManager.setTridentCooldown(player);
        startTridentCountdown(player);
        combatManager.refreshCombatOnTridentLand(player);
        riptideOriginalLocations.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof Trident && event.getEntity().getShooter() instanceof Player) {
            Player player = (Player) event.getEntity().getShooter();

            if (combatManager.isWorldBlacklisted(player)) {
                return;
            }

            if (combatManager.isTridentBanned(player)) {
                event.setCancelled(true);
                sendBannedMessage(player);
                return;
            }

            if (combatManager.isTridentOnCooldown(player)) {
                event.setCancelled(true);
                sendCooldownMessage(player);
            } else {
                combatManager.setTridentCooldown(player);
                startTridentCountdown(player);
                activeTridents.put(event.getEntity().getEntityId(), player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof Trident) {
            int tridentId = event.getEntity().getEntityId();

            if (activeTridents.containsKey(tridentId)) {
                UUID playerUUID = activeTridents.remove(tridentId);
                Player player = plugin.getServer().getPlayer(playerUUID);

                if (player != null && player.isOnline()) {
                    combatManager.refreshCombatOnTridentLand(player);
                }
            }
        }
    }

    private void rollbackRiptide(Player player) {
        Location originalLocation = riptideOriginalLocations.remove(player.getUniqueId());

        if (originalLocation != null) {
            Scheduler.runTaskLater(() -> {
                if (player.isOnline()) {
                    player.setVelocity(player.getVelocity().multiply(0));

                    if (player.getLocation().distance(originalLocation) > 5) {
                        player.teleport(originalLocation);
                    }
                }
            }, 2L);
        } else {
            Scheduler.runTask(() -> {
                player.setVelocity(player.getVelocity().multiply(0));
            });
        }
    }

    private void startTridentCountdown(Player player) {
        if (player == null) return;

        UUID playerUUID = player.getUniqueId();

        Scheduler.Task existingTask = tridentCountdownTasks.get(playerUUID);
        if (existingTask != null) {
            existingTask.cancel();
        }

        long updateInterval = 20L;

        Scheduler.Task task = Scheduler.runTaskTimer(() -> {
            if (!player.isOnline()) {
                cancelTridentCountdown(playerUUID);
                return;
            }

            if (!combatManager.isTridentOnCooldown(player)) {
                cancelTridentCountdown(playerUUID);
                return;
            }

            int remainingTime = combatManager.getRemainingTridentCooldown(player);

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            placeholders.put("time", String.valueOf(remainingTime));

            if (!combatManager.isInCombat(player)) {
                plugin.getMessageService().sendMessage(player, "trident_only_countdown", placeholders);
            }

        }, 0L, updateInterval);

        tridentCountdownTasks.put(playerUUID, task);
    }

    private void cancelTridentCountdown(UUID playerUUID) {
        Scheduler.Task task = tridentCountdownTasks.remove(playerUUID);
        if (task != null) {
            task.cancel();
        }
    }

    private void sendBannedMessage(Player player) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        plugin.getMessageService().sendMessage(player, "trident_banned", placeholders);
    }

    private void sendCooldownMessage(Player player) {
        int remainingTime = combatManager.getRemainingTridentCooldown(player);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", player.getName());
        placeholders.put("time", String.valueOf(remainingTime));
        plugin.getMessageService().sendMessage(player, "trident_cooldown", placeholders);
    }

    public void shutdown() {
        tridentCountdownTasks.values().forEach(Scheduler.Task::cancel);
        tridentCountdownTasks.clear();
        activeTridents.clear();
        riptideOriginalLocations.clear();
    }
}