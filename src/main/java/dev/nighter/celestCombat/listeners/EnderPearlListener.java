package dev.nighter.celestCombat.listeners;

import dev.nighter.celestCombat.CelestCombat;
import dev.nighter.celestCombat.Scheduler;
import dev.nighter.celestCombat.combat.CombatManager;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class EnderPearlListener implements Listener {
    private final CelestCombat plugin;
    private final CombatManager combatManager;

    private final Map<UUID, Scheduler.Task> pearlCountdownTasks = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> activePearls = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderPearlUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        Action action = event.getAction();

        if (combatManager.isWorldBlacklisted(player)) {
            return;
        }

        if (item != null && item.getType() == Material.ENDER_PEARL &&
            (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {

            if (combatManager.isEnderPearlOnCooldown(player)) {
                event.setCancelled(true);

                int remainingTime = combatManager.getRemainingEnderPearlCooldown(player);
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("time", String.valueOf(remainingTime));
                plugin.getMessageService().sendMessage(player, "enderpearl_cooldown", placeholders);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl && event.getEntity().getShooter() instanceof Player) {
            Player player = (Player) event.getEntity().getShooter();

            if (combatManager.isWorldBlacklisted(player)) {
                return;
            }

            if (combatManager.isEnderPearlOnCooldown(player)) {
                event.setCancelled(true);

                int remainingTime = combatManager.getRemainingEnderPearlCooldown(player);
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("time", String.valueOf(remainingTime));
                plugin.getMessageService().sendMessage(player, "enderpearl_cooldown", placeholders);
            } else {
                combatManager.setEnderPearlCooldown(player);
                startPearlCountdown(player);
                activePearls.put(event.getEntity().getEntityId(), player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof EnderPearl) {
            int pearlId = event.getEntity().getEntityId();

            if (activePearls.containsKey(pearlId)) {
                UUID playerUUID = activePearls.remove(pearlId);
                Player player = plugin.getServer().getPlayer(playerUUID);

                if (player != null && player.isOnline()) {
                    combatManager.refreshCombatOnPearlLand(player);
                }
            }
        }
    }

    private void startPearlCountdown(Player player) {
        if (player == null) return;

        UUID playerUUID = player.getUniqueId();

        Scheduler.Task existingTask = pearlCountdownTasks.get(playerUUID);
        if (existingTask != null) {
            existingTask.cancel();
        }

        long updateInterval = 20L;

        Scheduler.Task task = Scheduler.runTaskTimer(() -> {
            if (!player.isOnline()) {
                cancelPearlCountdown(playerUUID);
                return;
            }

            if (!combatManager.isEnderPearlOnCooldown(player)) {
                cancelPearlCountdown(playerUUID);
                return;
            }

            int remainingTime = combatManager.getRemainingEnderPearlCooldown(player);

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", player.getName());
            placeholders.put("time", String.valueOf(remainingTime));

            if (!combatManager.isInCombat(player)) {
                plugin.getMessageService().sendMessage(player, "pearl_only_countdown", placeholders);
            }

        }, 0L, updateInterval);

        pearlCountdownTasks.put(playerUUID, task);
    }

    private void cancelPearlCountdown(UUID playerUUID) {
        Scheduler.Task task = pearlCountdownTasks.remove(playerUUID);
        if (task != null) {
            task.cancel();
        }
    }

    public void shutdown() {
        pearlCountdownTasks.values().forEach(Scheduler.Task::cancel);
        pearlCountdownTasks.clear();
        activePearls.clear();
    }
}