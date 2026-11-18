package com.smp.artifacts;

import com.smp.artifacts.impl.WrathArtifact;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ArtifactsPlugin extends JavaPlugin {

    private PowerManager power;
    private ArtifactManager artifacts;

    @Override
    public void onEnable() {
        this.power = new PowerManager(this);
        this.artifacts = new ArtifactManager(this);

        // Every artifact gets registered here. A new artifact = one new line.
        artifacts.register(new WrathArtifact(this));

        getServer().getPluginManager().registerEvents(new ArtifactListener(this), this);

        PluginCommand command = getCommand("artifact");
        if (command != null) {
            ArtifactCommand handler = new ArtifactCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        artifacts.startPassiveTask();
        getLogger().info("Loaded " + artifacts.all().size() + " artifact(s).");
    }

    @Override
    public void onDisable() {
        if (artifacts != null) {
            artifacts.shutdown();
        }
    }

    public PowerManager power() {
        return power;
    }

    public ArtifactManager artifacts() {
        return artifacts;
    }
}
