package com.smp.artifacts;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /artifact list
 * /artifact give &lt;artifact&gt; [player]
 * /artifact power &lt;get|set|add&gt; &lt;player&gt; [amount]
 */
public final class ArtifactCommand implements TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_gray>[<dark_red>Artifacts<dark_gray>] ";

    private final ArtifactsPlugin plugin;

    public ArtifactCommand(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "give" -> give(sender, label, args);
            case "power" -> power(sender, label, args);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    // ------------------------------------------------------------------ subcommands

    private void list(CommandSender sender) {
        send(sender, "<gray>Registered artifacts:");
        for (Artifact artifact : plugin.artifacts().all()) {
            send(sender, "<dark_gray> - <white>" + artifact.id() + " <dark_gray>(<gray>"
                    + artifact.firstAbilityName() + " / " + artifact.awakeningName() + "<dark_gray>)");
        }
    }

    private void give(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            send(sender, "<gray>Usage: <white>/" + label + " give <artifact> [player]");
            return;
        }
        Artifact artifact = plugin.artifacts().get(args[1]);
        if (artifact == null) {
            send(sender, "<red>Unknown artifact <white>" + MM.escapeTags(args[1]) + "<red>. Try /" + label + " list");
            return;
        }
        Player target = args.length >= 3
                ? Bukkit.getPlayerExact(args[2])
                : (sender instanceof Player self ? self : null);
        if (target == null) {
            send(sender, "<red>Name an online player.");
            return;
        }

        Map<Integer, ItemStack> leftovers = target.getInventory().addItem(artifact.createItem());
        for (ItemStack extra : leftovers.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), extra);
        }
        send(sender, "<gray>Gave <white>" + artifact.id() + " <gray>to <white>" + target.getName() + "<gray>.");
    }

    private void power(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            send(sender, "<gray>Usage: <white>/" + label + " power <get|set|add> <player> [amount]");
            return;
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            send(sender, "<red>That player is not online.");
            return;
        }

        PowerManager powers = plugin.power();
        if (op.equals("get")) {
            send(sender, "<white>" + target.getName() + " <gray>has <white>" + powers.get(target)
                    + "<gray>/" + PowerManager.MAX_POWER + " power.");
            return;
        }
        if ((!op.equals("set") && !op.equals("add")) || args.length < 4) {
            send(sender, "<gray>Usage: <white>/" + label + " power <get|set|add> <player> [amount]");
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            send(sender, "<red>Amount must be a whole number.");
            return;
        }

        if (op.equals("set")) {
            powers.set(target, amount);
        } else {
            powers.add(target, amount);
        }
        send(sender, "<white>" + target.getName() + " <gray>now has <white>" + powers.get(target)
                + "<gray>/" + PowerManager.MAX_POWER + " power.");
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("give", "power", "list"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("give")) {
            if (args.length == 2) {
                List<String> ids = new ArrayList<>();
                for (Artifact artifact : plugin.artifacts().all()) {
                    ids.add(artifact.id());
                }
                return filter(ids, args[1]);
            }
            if (args.length == 3) {
                return filter(onlineNames(), args[2]);
            }
        }
        if (sub.equals("power")) {
            if (args.length == 2) {
                return filter(List.of("get", "set", "add"), args[1]);
            }
            if (args.length == 3) {
                return filter(onlineNames(), args[2]);
            }
            if (args.length == 4 && !args[1].equalsIgnoreCase("get")) {
                return filter(List.of("1", "8", String.valueOf(PowerManager.MAX_POWER)), args[3]);
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------------ helpers

    private void sendUsage(CommandSender sender, String label) {
        send(sender, "<gray>/" + label + " list");
        send(sender, "<gray>/" + label + " give <artifact> [player]");
        send(sender, "<gray>/" + label + " power <get|set|add> <player> [amount]");
    }

    private void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(MM.deserialize(PREFIX + miniMessage));
    }

    private List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private List<String> filter(Collection<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
