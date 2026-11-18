package com.smp.artifacts;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps track of every artifact and handles the parts they all share:
 * recognising the item, cooldowns, the power requirement, passive ticks and the HUD.
 */
public final class ArtifactManager {

    public enum Slot { FIRST, AWAKENING }

    /** How often (in ticks) passives and the action-bar HUD update. */
    public static final long PASSIVE_PERIOD = 5L;

    private static final long FEEDBACK_MILLIS = 1500L;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private record Feedback(Component message, long until) {
    }

    private final ArtifactsPlugin plugin;
    private final NamespacedKey itemKey;
    private final Map<String, Artifact> artifacts = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Feedback> feedback = new HashMap<>();
    private BukkitTask passiveTask;

    public ArtifactManager(ArtifactsPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "artifact_id");
    }

    // ------------------------------------------------------------------ registry

    public void register(Artifact artifact) {
        artifacts.put(artifact.id(), artifact);
        plugin.getServer().getPluginManager().registerEvents(artifact, plugin);
        artifact.onEnable();
    }

    public Collection<Artifact> all() {
        return Collections.unmodifiableCollection(artifacts.values());
    }

    public Artifact get(String id) {
        return id == null ? null : artifacts.get(id.toLowerCase(Locale.ROOT));
    }

    public NamespacedKey itemKey() {
        return itemKey;
    }

    // ------------------------------------------------------------------ item detection

    /** Which artifact (if any) this item is. */
    public Artifact fromItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return id == null ? null : artifacts.get(id);
    }

    public boolean isCarrying(Player player, Artifact artifact) {
        for (ItemStack item : player.getInventory().getContents()) {
            // Cheap material check first so we only read item meta when it could be a match.
            if (item != null && item.getType() == artifact.material() && fromItem(item) == artifact) {
                return true;
            }
        }
        return false;
    }

    public boolean isHolding(Player player, Artifact artifact) {
        return fromItem(player.getInventory().getItemInMainHand()) == artifact;
    }

    // ------------------------------------------------------------------ using abilities

    public void tryFirstAbility(Player player, Artifact artifact) {
        long left = cooldownLeft(player, artifact, Slot.FIRST);
        if (left > 0) {
            deny(player, "<red>" + artifact.firstAbilityName() + " <gray>is recharging <white>" + seconds(left) + "s");
            return;
        }
        if (artifact.useFirstAbility(player)) {
            startCooldown(player, artifact, Slot.FIRST, artifact.firstAbilityCooldownSeconds());
        }
    }

    public void tryAwakening(Player player, Artifact artifact) {
        int power = plugin.power().get(player);
        if (power < Artifact.AWAKENING_POWER) {
            deny(player, "<dark_purple>" + artifact.awakeningName() + " <gray>is sealed. Power <white>"
                    + power + "<gray>/" + Artifact.AWAKENING_POWER);
            return;
        }
        long left = cooldownLeft(player, artifact, Slot.AWAKENING);
        if (left > 0) {
            deny(player, "<dark_purple>" + artifact.awakeningName() + " <gray>is recharging <white>" + seconds(left) + "s");
            return;
        }
        if (artifact.useAwakening(player)) {
            startCooldown(player, artifact, Slot.AWAKENING, artifact.awakeningCooldownSeconds());
        }
    }

    // ------------------------------------------------------------------ cooldowns

    /** Milliseconds until the ability is ready again (0 = ready). */
    public long cooldownLeft(Player player, Artifact artifact, Slot slot) {
        Map<String, Long> map = cooldowns.get(player.getUniqueId());
        if (map == null) {
            return 0L;
        }
        Long until = map.get(cooldownKey(artifact, slot));
        return until == null ? 0L : Math.max(0L, until - System.currentTimeMillis());
    }

    private void startCooldown(Player player, Artifact artifact, Slot slot, int seconds) {
        cooldowns.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>())
                .put(cooldownKey(artifact, slot), System.currentTimeMillis() + seconds * 1000L);
    }

    private String cooldownKey(Artifact artifact, Slot slot) {
        return artifact.id() + ":" + slot.name();
    }

    public static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0);
    }

    // ------------------------------------------------------------------ feedback

    /** Shows a message on the action bar that the HUD will not overwrite for a moment. */
    public void sendFeedback(Player player, String miniMessage) {
        Component message = MM.deserialize(miniMessage);
        feedback.put(player.getUniqueId(), new Feedback(message, System.currentTimeMillis() + FEEDBACK_MILLIS));
        player.sendActionBar(message);
    }

    /** Like {@link #sendFeedback} but with a soft "no" sound (played at most once per message). */
    public void deny(Player player, String miniMessage) {
        Feedback current = feedback.get(player.getUniqueId());
        boolean alreadyShowing = current != null && current.until() > System.currentTimeMillis();
        sendFeedback(player, miniMessage);
        if (!alreadyShowing) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
        }
    }

    public void onQuit(Player player) {
        feedback.remove(player.getUniqueId());
    }

    // ------------------------------------------------------------------ passive + HUD loop

    public void startPassiveTask() {
        passiveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPassives, 20L, PASSIVE_PERIOD);
    }

    private void tickPassives() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead()) {
                continue;
            }
            Component hud = null;
            for (Artifact artifact : artifacts.values()) {
                if (!isCarrying(player, artifact)) {
                    continue;
                }
                artifact.onPassiveTick(player);
                if (hud == null && isHolding(player, artifact)) {
                    hud = artifact.hud(player);
                }
            }
            if (hud != null) {
                Feedback current = feedback.get(player.getUniqueId());
                player.sendActionBar(current != null && current.until() > now ? current.message() : hud);
            }
        }
    }

    public void shutdown() {
        if (passiveTask != null) {
            passiveTask.cancel();
        }
        for (Artifact artifact : artifacts.values()) {
            artifact.onDisable();
        }
    }
}
