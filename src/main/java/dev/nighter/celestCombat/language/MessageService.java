package dev.nighter.celestCombat.language;

import dev.nighter.celestCombat.Scheduler;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class MessageService {
    private final JavaPlugin plugin;
    private final LanguageManager languageManager;

    private static final Map<String, String> EMPTY_PLACEHOLDERS = Collections.emptyMap();
    private final Map<String, Boolean> keyExistsCache = new ConcurrentHashMap<>(128);

    private final Map<String, Long> messageDelayCache = new ConcurrentHashMap<>();
    private static final long MESSAGE_DELAY = 1000L;

    public void sendMessage(CommandSender sender, String key) {
        sendMessage(sender, key, EMPTY_PLACEHOLDERS);
    }

    public void sendMessage(Player player, String key) {
        sendMessage(player, key, EMPTY_PLACEHOLDERS);
    }

    public void sendMessage(Player player, String key, Map<String, String> placeholders) {
        sendMessage((CommandSender) player, key, placeholders);
    }

    public void sendMessage(CommandSender sender, String key, Map<String, String> placeholders) {
        if (!checkKeyExists(key)) {
            plugin.getLogger().warning("Message key not found: " + key);
            sender.sendMessage("§cMissing message key: " + key);
            return;
        }

        String message = languageManager.getMessage(key, placeholders);
        if (message != null && !message.startsWith("Missing message:")) {
            if (sender instanceof Player player) {
                sendDelayedMessage(player, message, key, placeholders);
            } else {
                sender.sendMessage(message);
            }
        }

        if (sender instanceof Player player) {
            sendPlayerSpecificContent(player, key, placeholders);
        }
    }

    private void sendDelayedMessage(Player player, String message, String key, Map<String, String> placeholders) {
        String cacheKey = player.getUniqueId() + ":" + key;
        long currentTime = System.currentTimeMillis();
        Long lastMessageTime = messageDelayCache.get(cacheKey);

        if (lastMessageTime == null || currentTime - lastMessageTime >= MESSAGE_DELAY) {
            player.sendMessage(message);
            messageDelayCache.put(cacheKey, currentTime);

            String title = languageManager.getTitle(key, placeholders);
            String subtitle = languageManager.getSubtitle(key, placeholders);
            if (title != null || subtitle != null) {
                player.sendTitle(
                    title != null ? title : "",
                    subtitle != null ? subtitle : "",
                    10, 70, 20
                );
            }
        } else {
            Scheduler.runTaskLater(() -> {
                long newCurrentTime = System.currentTimeMillis();
                Long newLastMessageTime = messageDelayCache.get(cacheKey);

                if (newLastMessageTime == null || newCurrentTime - newLastMessageTime >= MESSAGE_DELAY) {
                    player.sendMessage(message);
                    messageDelayCache.put(cacheKey, newCurrentTime);

                    String title = languageManager.getTitle(key, placeholders);
                    String subtitle = languageManager.getSubtitle(key, placeholders);
                    if (title != null || subtitle != null) {
                        player.sendTitle(
                            title != null ? title : "",
                            subtitle != null ? subtitle : "",
                            10, 70, 20
                        );
                    }
                }
            }, MESSAGE_DELAY / 50L);
        }
    }

    private boolean checkKeyExists(String key) {
        return keyExistsCache.computeIfAbsent(key, languageManager::keyExists);
    }

    public void clearKeyExistsCache() {
        keyExistsCache.clear();
    }

    public void sendConsoleMessage(String key) {
        sendConsoleMessage(key, EMPTY_PLACEHOLDERS);
    }

    public void sendConsoleMessage(String key, Map<String, String> placeholders) {
        if (!languageManager.keyExists(key)) {
            plugin.getLogger().warning("Message key not found: " + key);
            plugin.getLogger().warning("§cMissing message key: " + key);
            return;
        }

        String message = languageManager.getRawMessage(key, placeholders);
        if (message != null && !message.startsWith("Missing message:")) {
            String consoleMessage = stripColorCodes(message);
            plugin.getLogger().info(consoleMessage);
        }
    }

    private String stripColorCodes(String message) {
        return message.replaceAll("§[0-9a-fA-Fk-oK-OrR]", "")
            .replaceAll("&#[0-9a-fA-F]{6}", "")
            .replaceAll("&[0-9a-fA-Fk-oK-OrR]", "");
    }

    private void sendPlayerSpecificContent(Player player, String key, Map<String, String> placeholders) {
        String actionBar = languageManager.getActionBar(key, placeholders);
        if (actionBar != null) {
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(actionBar)
            );
        }

        String soundName = languageManager.getSound(key);
        if (soundName != null) {
            try {
                player.playSound(player.getLocation(), soundName, 1.0f, 1.0f);
            } catch (Exception e) {
                plugin.getLogger().warning("Invalid sound name for key " + key + ": " + soundName);
            }
        }
    }

    public void cleanupPlayerData(UUID playerUUID) {
        messageDelayCache.entrySet().removeIf(entry -> entry.getKey().startsWith(playerUUID.toString()));
    }
}