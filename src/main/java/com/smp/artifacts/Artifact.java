package com.smp.artifacts;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Base class for every artifact.
 * <p>
 * An artifact has an item, a <b>passive</b> (always on while carried), a <b>first ability</b>
 * (right-click) and an <b>awakening</b> (shift + right-click, unlocked at 8 power).
 * <p>
 * To add a new artifact: extend this class, then register it in {@link ArtifactsPlugin#onEnable()}.
 * Artifacts are {@link Listener}s, so any {@code @EventHandler} method you add in a subclass is
 * registered automatically. That is how passives hook into combat, healing, etc.
 */
public abstract class Artifact implements Listener {

    /** Power a player needs before an awakening can be used. */
    public static final int AWAKENING_POWER = 8;

    protected static final MiniMessage MM = MiniMessage.miniMessage();

    protected final ArtifactsPlugin plugin;
    private final String id;

    protected Artifact(ArtifactsPlugin plugin, String id) {
        this.plugin = plugin;
        this.id = id;
    }

    // ------------------------------------------------------------------ identity

    /** Lower-case id used in commands and stored on the item, e.g. "wrath". */
    public final String id() {
        return id;
    }

    public abstract Material material();

    public abstract Component displayName();

    public abstract List<Component> lore();

    // ------------------------------------------------------------------ abilities

    public abstract String firstAbilityName();

    public abstract String awakeningName();

    public abstract int firstAbilityCooldownSeconds();

    public abstract int awakeningCooldownSeconds();

    /** Right-click. Return true if the ability was used (this starts its cooldown). */
    public abstract boolean useFirstAbility(Player player);

    /** Shift + right-click, only called when the player has enough power. Return true if used. */
    public abstract boolean useAwakening(Player player);

    // ------------------------------------------------------------------ optional hooks

    /** Called once when the artifact is registered. Start repeating tasks here. */
    public void onEnable() {
    }

    /** Called when the plugin shuts down. */
    public void onDisable() {
    }

    /** Called every few ticks for each online player carrying this artifact anywhere in their inventory. */
    public void onPassiveTick(Player carrier) {
    }

    /** Action-bar text shown while the artifact is held in the main hand. Return null for none. */
    public Component hud(Player holder) {
        return null;
    }

    // ------------------------------------------------------------------ helpers

    public ItemStack createItem() {
        ItemStack item = new ItemStack(material());
        item.editMeta(meta -> {
            meta.displayName(displayName());
            meta.lore(lore());
            meta.setUnbreakable(true);
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer()
                    .set(plugin.artifacts().itemKey(), PersistentDataType.STRING, id);
        });
        return item;
    }

    /** True if the player has this artifact anywhere in their inventory (hotbar, offhand, armor slots...). */
    protected final boolean isCarriedBy(Player player) {
        return plugin.artifacts().isCarrying(player, this);
    }

    /**
     * Who abilities may affect: other players in survival/adventure and hostile mobs.
     * Villagers, pets and farm animals are never targeted. Put protection checks
     * (WorldGuard regions, teams, friends...) here and every ability will respect them.
     */
    protected boolean canTarget(Player caster, LivingEntity target) {
        if (target.getUniqueId().equals(caster.getUniqueId()) || target.isDead() || !target.isValid()) {
            return false;
        }
        if (target instanceof Player other) {
            GameMode mode = other.getGameMode();
            return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
        }
        return target instanceof Enemy;
    }

    /** MiniMessage -> Component with italics switched off (item names/lore are italic by default). */
    protected static Component mm(String text) {
        return MM.deserialize("<!italic>" + text);
    }
}
