package dev.nighter.celestCombat.listeners;

import dev.nighter.celestCombat.CelestCombat;
import dev.nighter.celestCombat.combat.CombatManager;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class ItemRestrictionListener implements Listener {
    private final CelestCombat plugin;
    private final CombatManager combatManager;

    public static String formatItemName(Material material) {
        if (material == null) {
            return "Unknown Item";
        }

        String[] words = material.name().split("_");
        StringBuilder formattedName = new StringBuilder();

        for (String word : words) {
            formattedName
                .append(word.substring(0, 1).toUpperCase())
                .append(word.substring(1).toLowerCase())
                .append(" ");
        }

        return formattedName.toString().trim();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        if (!plugin.getConfig().getBoolean("combat.item_restrictions.enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (combatManager.isWorldBlacklisted(player)) {
            return;
        }

        if (combatManager.isInCombat(player)) {
            List<String> disabledItems = plugin.getConfig().getStringList("combat.item_restrictions.disabled_items");

            if (isItemDisabled(item.getType(), disabledItems)) {
                event.setCancelled(true);

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("item", formatItemName(item.getType()));
                plugin.getMessageService().sendMessage(player, "item_use_blocked_in_combat", placeholders);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMoveEvent(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("combat.item_restrictions.enabled", true)) {
            return;
        }

        Player player = event.getPlayer();

        if (combatManager.isWorldBlacklisted(player)) {
            return;
        }

        if (combatManager.isInCombat(player)) {
            List<String> disabledItems = plugin.getConfig().getStringList("combat.item_restrictions.disabled_items");

            if (disabledItems.contains("ELYTRA") && player.isGliding()) {
                player.setGliding(false);

                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", player.getName());
                placeholders.put("item", "Elytra");
                plugin.getMessageService().sendMessage(player, "item_use_blocked_in_combat", placeholders);
            }
        }
    }

    private boolean isItemDisabled(Material itemType, List<String> disabledItems) {
        return disabledItems.stream()
            .anyMatch(disabledItem ->
                itemType.name().equalsIgnoreCase(disabledItem) ||
                    itemType.name().contains(disabledItem)
            );
    }
}