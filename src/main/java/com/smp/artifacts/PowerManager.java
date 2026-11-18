package com.smp.artifacts;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Stores each player's power on the player themselves (persistent data container),
 * so it survives restarts and needs no extra files.
 * <p>
 * HOW power is earned is up to you: call {@link #add(Player, int)} from wherever it should
 * happen (kills, rituals, crafting, a shop...). For now only the /artifact command does.
 */
public final class PowerManager {

    /** Highest power a player can reach. Change this to fit your SMP. */
    public static final int MAX_POWER = 10;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final NamespacedKey key;

    public PowerManager(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "power");
    }

    public int get(Player player) {
        return player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.INTEGER, 0);
    }

    public void set(Player player, int amount) {
        int before = get(player);
        int clamped = Math.max(0, Math.min(MAX_POWER, amount));
        player.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, clamped);

        // Tell the player the moment awakenings open up for them.
        if (before < Artifact.AWAKENING_POWER && clamped >= Artifact.AWAKENING_POWER) {
            player.sendMessage(MM.deserialize(
                    "<dark_purple>Your power has reached <white>" + clamped
                            + "<dark_purple>. Your artifacts can now <light_purple>awaken<dark_purple>."));
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.7f);
        }
    }

    public void add(Player player, int amount) {
        set(player, get(player) + amount);
    }
}
