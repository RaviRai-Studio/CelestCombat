package dev.nighter.celestCombat.listeners;

import dev.nighter.celestCombat.CelestCombat;
import dev.nighter.celestCombat.combat.CombatManager;
import dev.nighter.celestCombat.combat.DeathAnimationManager;
import dev.nighter.celestCombat.language.MessageService;
import dev.nighter.celestCombat.protection.NewbieProtectionManager;
import dev.nighter.celestCombat.rewards.KillRewardManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class CombatListeners implements Listener {
    private final CelestCombat plugin;
    private CombatManager combatManager;
    private NewbieProtectionManager newbieProtectionManager;
    private KillRewardManager killRewardManager;
    private DeathAnimationManager deathAnimationManager;
    private MessageService messageService;

    private final Map<UUID, Boolean> playerLoggedOutInCombat = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastDamageSource = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDamageTime = new ConcurrentHashMap<>();
    private static final long DAMAGE_RECORD_CLEANUP_THRESHOLD = TimeUnit.MINUTES.toMillis(5);

    public CombatListeners(CelestCombat plugin) {
        this.plugin = plugin;
        this.combatManager = plugin.getCombatManager();
        this.newbieProtectionManager = plugin.getNewbieProtectionManager();
        this.killRewardManager = plugin.getKillRewardManager();
        this.deathAnimationManager = plugin.getDeathAnimationManager();
        this.messageService = plugin.getMessageService();
    }

    public void reload() {
        this.combatManager = plugin.getCombatManager();
        this.newbieProtectionManager = plugin.getNewbieProtectionManager();
        this.killRewardManager = plugin.getKillRewardManager();
        this.deathAnimationManager = plugin.getDeathAnimationManager();
        this.messageService = plugin.getMessageService();

        plugin.debug("CombatListeners managers reloaded successfully");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = null;
        Player victim = null;

        if (event.getEntity() instanceof Player) {
            victim = (Player) event.getEntity();
        } else {
            return;
        }

        if (combatManager.isWorldBlacklisted(victim)) {
            return;
        }

        Entity damager = event.getDamager();

        if (damager instanceof Player) {
            attacker = (Player) damager;
        }
        else if (damager instanceof Projectile) {
            Projectile projectile = (Projectile) damager;
            if (projectile.getShooter() instanceof Player) {
                attacker = (Player) projectile.getShooter();
            }
        }

        if (attacker != null && newbieProtectionManager.shouldProtectFromPvP() &&
            newbieProtectionManager.hasProtection(victim)) {

            boolean shouldBlock = newbieProtectionManager.handleDamageReceived(victim, attacker);
            if (shouldBlock) {
                event.setCancelled(true);
                plugin.debug("Blocked PvP damage to protected newbie: " + victim.getName());
                return;
            }
        }

        else if (attacker == null && newbieProtectionManager.shouldProtectFromMobs() &&
            newbieProtectionManager.hasProtection(victim)) {
            event.setCancelled(true);
            plugin.debug("Blocked mob damage to protected newbie: " + victim.getName());
            return;
        }

        if (attacker != null && newbieProtectionManager.hasProtection(attacker)) {
            newbieProtectionManager.handleDamageDealt(attacker);
        }

        if (attacker != null && victim != null && !attacker.equals(victim)) {
            if (combatManager.isVictimProtectedFromThirdParty(victim, attacker)) {
                event.setCancelled(true);

                Player victimOpponent = combatManager.getCombatOpponent(victim);
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("attacker", attacker.getName());
                placeholders.put("victim", victim.getName());
                placeholders.put("opponent", victimOpponent != null ? victimOpponent.getName() : "Unknown");
                placeholders.put("time", String.valueOf(combatManager.getRemainingCombatTime(victim)));

                plugin.getMessageService().sendMessage(attacker, "third_party_damage_blocked", placeholders);
                plugin.debug("Blocked third party damage from " + attacker.getName() + " to " + victim.getName());
                return;
            }

            if (combatManager.isAttackerInCombatWithOther(attacker, victim)) {
                event.setCancelled(true);

                Player attackerOpponent = combatManager.getCombatOpponent(attacker);
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("attacker", attacker.getName());
                placeholders.put("victim", victim.getName());
                placeholders.put("opponent", attackerOpponent != null ? attackerOpponent.getName() : "Unknown");
                placeholders.put("time", String.valueOf(combatManager.getRemainingCombatTime(attacker)));

                plugin.getMessageService().sendMessage(attacker, "combat_locked_cannot_attack_others", placeholders);
                plugin.debug("Blocked " + attacker.getName() + " from attacking " + victim.getName() + " while in combat with " +
                    (attackerOpponent != null ? attackerOpponent.getName() : "someone"));
                return;
            }

            lastDamageSource.put(victim.getUniqueId(), attacker.getUniqueId());
            lastDamageTime.put(victim.getUniqueId(), System.currentTimeMillis());

            combatManager.tagPlayer(attacker, victim);
            combatManager.tagPlayer(victim, attacker);

            cleanupStaleDamageRecords();
        }
    }

    private void cleanupStaleDamageRecords() {
        long currentTime = System.currentTimeMillis();
        lastDamageTime.entrySet().removeIf(entry ->
            (currentTime - entry.getValue()) > DAMAGE_RECORD_CLEANUP_THRESHOLD);

        lastDamageSource.keySet().removeIf(uuid -> !lastDamageTime.containsKey(uuid));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        newbieProtectionManager.handlePlayerQuit(player);
        messageService.cleanupPlayerData(playerUUID);

        if (combatManager.isInCombat(player)) {
            playerLoggedOutInCombat.put(playerUUID, true);
            combatManager.punishCombatLogout(player);
        } else {
            playerLoggedOutInCombat.put(playerUUID, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        newbieProtectionManager.handlePlayerQuit(player);
        messageService.cleanupPlayerData(playerUUID);

        if (combatManager.isInCombat(player)) {
            if (plugin.getConfig().getBoolean("combat.exempt_admin_kick", true)) {
                Player opponent = combatManager.getCombatOpponent(player);
                combatManager.removeFromCombatSilently(player);

                if (opponent != null) {
                    combatManager.removeFromCombat(opponent);
                }
            } else {
                Player opponent = combatManager.getCombatOpponent(player);
                playerLoggedOutInCombat.put(playerUUID, true);

                combatManager.punishCombatLogout(player);

                if (opponent != null && opponent.isOnline()) {
                    killRewardManager.giveKillReward(opponent, player);
                    deathAnimationManager.performDeathAnimation(player, opponent);
                } else {
                    deathAnimationManager.performDeathAnimation(player, null);
                }

                combatManager.removeFromCombatSilently(player);
                if (opponent != null) {
                    combatManager.removeFromCombat(opponent);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        UUID victimId = victim.getUniqueId();

        if (newbieProtectionManager.hasProtection(victim)) {
            newbieProtectionManager.removeProtection(victim, false);
            plugin.debug("Removed newbie protection from " + victim.getName() + " due to death");
        }

        if (killer != null && !killer.equals(victim)) {
            killRewardManager.giveKillReward(killer, victim);
            deathAnimationManager.performDeathAnimation(victim, killer);

            combatManager.removeFromCombat(victim);
            combatManager.removeFromCombat(killer);
        }
        else if (combatManager.isInCombat(victim)) {
            Player opponent = combatManager.getCombatOpponent(victim);

            if (opponent != null && opponent.isOnline()) {
                killRewardManager.giveKillReward(opponent, victim);
                deathAnimationManager.performDeathAnimation(victim, opponent);
            } else if (lastDamageSource.containsKey(victimId)) {
                UUID lastAttackerUuid = lastDamageSource.get(victimId);
                Player lastAttacker = plugin.getServer().getPlayer(lastAttackerUuid);

                if (lastAttacker != null && lastAttacker.isOnline() && !lastAttacker.equals(victim)) {
                    killRewardManager.giveKillReward(lastAttacker, victim);
                    deathAnimationManager.performDeathAnimation(victim, lastAttacker);
                } else {
                    deathAnimationManager.performDeathAnimation(victim, null);
                }
            } else {
                deathAnimationManager.performDeathAnimation(victim, null);
            }

            combatManager.removeFromCombat(victim);
            if (opponent != null) {
                combatManager.removeFromCombat(opponent);
            }

            lastDamageSource.remove(victimId);
            lastDamageTime.remove(victimId);
        } else {
            deathAnimationManager.performDeathAnimation(victim, null);

            lastDamageSource.remove(victimId);
            lastDamageTime.remove(victimId);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        newbieProtectionManager.handlePlayerJoin(player);

        if (playerLoggedOutInCombat.containsKey(playerUUID)) {
            if (playerLoggedOutInCombat.get(playerUUID)) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                messageService.sendMessage(player, "player_died_combat_logout", placeholders);
            }
            playerLoggedOutInCombat.remove(playerUUID);
        }

        lastDamageSource.remove(playerUUID);
        lastDamageTime.remove(playerUUID);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (combatManager.isWorldBlacklisted(player)) {
            return;
        }

        if (combatManager.isInCombat(player)) {
            String command = event.getMessage().split(" ")[0].toLowerCase().substring(1);

            String blockMode = plugin.getConfig().getString("combat.command_block_mode", "whitelist").toLowerCase();

            boolean shouldBlock = false;

            if ("blacklist".equalsIgnoreCase(blockMode)) {
                List<String> blockedCommands = plugin.getConfig().getStringList("combat.blocked_commands");

                for (String blockedCmd : blockedCommands) {
                    if (command.equalsIgnoreCase(blockedCmd) ||
                        (blockedCmd.endsWith("*") && command.startsWith(blockedCmd.substring(0, blockedCmd.length() - 1)))) {
                        shouldBlock = true;
                        break;
                    }
                }
            } else {
                List<String> allowedCommands = plugin.getConfig().getStringList("combat.allowed_commands");
                shouldBlock = true;

                for (String allowedCmd : allowedCommands) {
                    if (command.equalsIgnoreCase(allowedCmd) ||
                        (allowedCmd.endsWith("*") && command.startsWith(allowedCmd.substring(0, allowedCmd.length() - 1)))) {
                        shouldBlock = false;
                        break;
                    }
                }
            }

            if (shouldBlock) {
                event.setCancelled(true);

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("command", command);
                placeholders.put("time", String.valueOf(combatManager.getRemainingCombatTime(player)));
                messageService.sendMessage(player, "command_blocked_in_combat", placeholders);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        if (combatManager.isWorldBlacklisted(player)) {
            return;
        }

        if (event.isFlying() && combatManager.shouldDisableFlight(player)) {
            event.setCancelled(true);
        }
    }

    public void shutdown() {
        playerLoggedOutInCombat.clear();
        lastDamageSource.clear();
        lastDamageTime.clear();
    }
}