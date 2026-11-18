package com.smp.artifacts;

import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Controls: right-click = first ability, shift + right-click = awakening.
 * Also stops dropped artifacts from despawning or being destroyed.
 */
public final class ArtifactListener implements Listener {

    private final ArtifactManager manager;

    public ArtifactListener(ArtifactsPlugin plugin) {
        this.manager = plugin.artifacts();
    }

    // Deliberately NOT ignoreCancelled: Bukkit marks right-clicks on air as "cancelled" by default.
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // the event fires once per hand
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Artifact artifact = manager.fromItem(event.getItem());
        if (artifact == null) {
            return;
        }

        Player player = event.getPlayer();

        // Let players still open chests, doors, crafting tables... Sneaking always counts as an ability.
        Block clicked = event.getClickedBlock();
        if (clicked != null && !player.isSneaking() && clicked.getType().isInteractable()) {
            return;
        }

        event.setCancelled(true);
        if (player.isSneaking()) {
            manager.tryAwakening(player, artifact);
        } else {
            manager.tryFirstAbility(player, artifact);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.onQuit(event.getPlayer());
    }

    // ------------------------------------------------------------------ keep dropped artifacts safe

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent event) {
        if (manager.fromItem(event.getEntity().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item && manager.fromItem(item.getItemStack()) != null) {
            event.setCancelled(true);
        }
    }
}
