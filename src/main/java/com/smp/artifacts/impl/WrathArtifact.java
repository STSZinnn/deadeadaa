package com.smp.artifacts.impl;

import com.smp.artifacts.Artifact;
import com.smp.artifacts.ArtifactManager;
import com.smp.artifacts.ArtifactManager.Slot;
import com.smp.artifacts.ArtifactsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * THE WRATH - an artifact of rage, corruption and malice.
 *
 * <pre>
 * PASSIVE    Seething Fury      Getting hurt builds Fury (max 5). +5% melee damage per stack.
 *                               At max Fury your hits corrupt.
 * ABILITY    Corrupting Wave    Right-click. A wave of malice that damages, corrupts and
 *                               hurls enemies back. Landing it feeds your Fury.
 * AWAKENING  Dominion of Wrath  Shift + right-click, needs 8 power. 12s as the avatar of wrath:
 *                               maxed Fury, buffs, and an aura that withers, blinds, corrupts and
 *                               drains everything near you. Ends in exhaustion.
 *
 * CORRUPTION (the debuff) halves all healing the victim receives.
 * </pre>
 */
public final class WrathArtifact extends Artifact {

    // ============================================================ tuning (change freely)

    // Passive: Seething Fury
    private static final int MAX_FURY = 5;
    private static final long FURY_DURATION_MS = 8_000L;     // each new stack refreshes this
    private static final long FURY_GAIN_GAP_MS = 400L;       // at most one stack per 0.4s
    private static final double FURY_DAMAGE_PER_STACK = 0.05; // +5% melee damage per stack
    private static final long FURY_CORRUPT_MS = 4_000L;      // corruption from max-Fury hits

    // Corruption debuff
    private static final double CORRUPTED_HEAL_MULTIPLIER = 0.5; // healing received is halved

    // Ability: Corrupting Wave
    private static final int WAVE_COOLDOWN_SECONDS = 20;
    private static final int WAVE_STEPS = 12;                // ticks the wave travels
    private static final double WAVE_SPEED = 1.25;           // blocks per tick (range ~15)
    private static final double WAVE_HIT_RADIUS = 1.9;
    private static final double WAVE_WIDTH = 2.4;
    private static final double WAVE_BASE_DAMAGE = 6.0;      // 3 hearts, before armor
    private static final double WAVE_DAMAGE_PER_FURY = 1.0;
    private static final long WAVE_CORRUPT_MS = 8_000L;

    // Awakening: Dominion of Wrath
    private static final int AWAKENING_COOLDOWN_SECONDS = 120;
    private static final int AWAKENING_SECONDS = 12;
    private static final double BURST_RADIUS = 7.0;          // instant shockwave on activation
    private static final double BURST_DAMAGE = 4.0;
    private static final long BURST_CORRUPT_MS = 10_000L;
    private static final double AURA_RADIUS = 6.0;
    private static final double AURA_DRAIN_HEAL = 1.0;       // HP per second while enemies are in the aura
    private static final int EXHAUSTION_SECONDS = 6;

    // Colours
    private static final Particle.DustOptions DUST_BLOOD = new Particle.DustOptions(Color.fromRGB(150, 0, 10), 1.6f);
    private static final Particle.DustOptions DUST_VOID = new Particle.DustOptions(Color.fromRGB(105, 0, 160), 1.4f);

    // ============================================================ state

    private static final class FuryState {
        int stacks;
        long expiresAt;
        long lastGain;
    }

    private final Map<UUID, FuryState> fury = new HashMap<>();
    private final Map<UUID, Long> corrupted = new HashMap<>();     // entity -> corruption ends at
    private final Map<UUID, Long> awakenedUntil = new HashMap<>(); // player -> awakening ends at
    private final Random random = new Random();
    private boolean internalDamage; // true while our own abilities are dealing damage
    private BukkitTask corruptionTask;

    public WrathArtifact(ArtifactsPlugin plugin) {
        super(plugin, "wrath");
    }

    // ============================================================ identity

    @Override
    public Material material() {
        return Material.NETHERITE_SWORD;
    }

    @Override
    public Component displayName() {
        return mm("<bold><gradient:#b00010:#7a1fd0>The Wrath</gradient></bold>");
    }

    @Override
    public List<Component> lore() {
        return List.of(
                mm("<dark_gray>Artifact <dark_gray>- <dark_red>Wrath <dark_gray>- <dark_purple>Corruption"),
                Component.empty(),
                mm("<red>Passive: <white>Seething Fury"),
                mm("<gray>Getting hurt builds <red>Fury <gray>(max 5)."),
                mm("<gray>Each stack: <white>+5% <gray>melee damage."),
                mm("<gray>At max Fury, your strikes <dark_purple>corrupt<gray>."),
                Component.empty(),
                mm("<red>Ability: <white>Corrupting Wave <dark_gray>(Right-click)"),
                mm("<gray>Send a wave of malice forward that"),
                mm("<gray>damages, corrupts and hurls enemies back."),
                Component.empty(),
                mm("<dark_purple>Awakening: <white>Dominion of Wrath <dark_gray>(Shift + Right-click)"),
                mm("<gray>Unlocks at <white>8 power<gray>. Become the avatar of wrath."),
                Component.empty(),
                mm("<dark_gray><italic>Every grievance, remembered.</italic>")
        );
    }

    @Override
    public String firstAbilityName() {
        return "Corrupting Wave";
    }

    @Override
    public String awakeningName() {
        return "Dominion of Wrath";
    }

    @Override
    public int firstAbilityCooldownSeconds() {
        return WAVE_COOLDOWN_SECONDS;
    }

    @Override
    public int awakeningCooldownSeconds() {
        return AWAKENING_COOLDOWN_SECONDS;
    }

    // ============================================================ lifecycle

    @Override
    public void onEnable() {
        corruptionTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCorruption, 10L, 10L);
    }

    @Override
    public void onDisable() {
        if (corruptionTask != null) {
            corruptionTask.cancel();
        }
        fury.clear();
        corrupted.clear();
        awakenedUntil.clear();
    }

    // ============================================================ PASSIVE: Seething Fury

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        // Victim side: being hurt feeds the Fury.
        if (event.getEntity() instanceof Player victim
                && event.getDamager() != victim
                && event.getFinalDamage() > 0
                && isCarriedBy(victim)) {
            addFury(victim);
        }

        // Attacker side: Fury empowers melee hits (not our own abilities).
        if (internalDamage) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        DamageCause cause = event.getCause();
        if (cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        if (!isCarriedBy(attacker)) {
            return;
        }

        int stacks = stacksOf(attacker);
        if (stacks <= 0) {
            return;
        }
        event.setDamage(event.getDamage() * (1.0 + FURY_DAMAGE_PER_STACK * stacks));

        // At max Fury every hit corrupts.
        if (stacks >= MAX_FURY && event.getEntity() instanceof LivingEntity target && canTarget(attacker, target)) {
            corrupt(target, FURY_CORRUPT_MS);
        }
    }

    @Override
    public void onPassiveTick(Player player) {
        int stacks = stacksOf(player);
        if (stacks <= 0) {
            return; // no visible tell until the rage starts building
        }
        World world = player.getWorld();
        Location chest = player.getLocation().add(0, 1.0, 0);
        world.spawnParticle(Particle.DUST, chest, 2 + stacks * 2, 0.4, 0.7, 0.4, 0, DUST_BLOOD);
        if (stacks >= MAX_FURY) {
            world.spawnParticle(Particle.DUST, chest, 3, 0.4, 0.7, 0.4, 0, DUST_VOID);
            world.spawnParticle(Particle.SMOKE, player.getLocation().add(0, 2.2, 0), 2, 0.2, 0.05, 0.2, 0.01);
        }
    }

    @Override
    public Component hud(Player player) {
        int stacks = stacksOf(player);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < MAX_FURY; i++) {
            bar.append(i < stacks ? "<red>\u25A0" : "<dark_gray>\u25A0");
        }

        StringBuilder text = new StringBuilder();
        text.append("<dark_red>Fury ").append(bar);

        Long until = awakenedUntil.get(player.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) {
            long left = until - System.currentTimeMillis();
            text.append("  <dark_gray>|  <dark_red><bold>UNLEASHED</bold> <red>")
                    .append(ArtifactManager.seconds(left)).append("s");
        } else {
            long waveLeft = plugin.artifacts().cooldownLeft(player, this, Slot.FIRST);
            text.append("  <dark_gray>|  <light_purple>Wave ").append(cooldownText(waveLeft));

            int power = plugin.power().get(player);
            text.append("  <dark_gray>|  <dark_purple>Awakening ");
            if (power < AWAKENING_POWER) {
                text.append("<dark_gray>SEALED ").append(power).append('/').append(AWAKENING_POWER);
            } else {
                text.append(cooldownText(plugin.artifacts().cooldownLeft(player, this, Slot.AWAKENING)));
            }
        }
        return MM.deserialize(text.toString());
    }

    private String cooldownText(long millis) {
        return millis <= 0 ? "<green>READY" : "<red>" + ArtifactManager.seconds(millis) + "s";
    }

    // ---- fury bookkeeping

    private boolean isAwakened(Player player) {
        Long until = awakenedUntil.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    /** Current stacks. While awakened Fury is locked at max. */
    private int stacksOf(Player player) {
        if (isAwakened(player)) {
            return MAX_FURY;
        }
        FuryState state = fury.get(player.getUniqueId());
        if (state == null) {
            return 0;
        }
        if (System.currentTimeMillis() > state.expiresAt) {
            fury.remove(player.getUniqueId());
            return 0;
        }
        return state.stacks;
    }

    private void addFury(Player player) {
        if (isAwakened(player)) {
            return; // already locked at max
        }
        long now = System.currentTimeMillis();
        FuryState state = fury.computeIfAbsent(player.getUniqueId(), key -> new FuryState());
        if (now > state.expiresAt) {
            state.stacks = 0; // old stacks faded
        }
        if (now - state.lastGain < FURY_GAIN_GAP_MS) {
            return;
        }
        state.lastGain = now;
        state.expiresAt = now + FURY_DURATION_MS; // every hit refreshes the timer
        if (state.stacks >= MAX_FURY) {
            return;
        }
        state.stacks++;

        World world = player.getWorld();
        world.spawnParticle(Particle.ANGRY_VILLAGER, player.getLocation().add(0, 2.2, 0), 1, 0.25, 0.1, 0.25, 0);
        world.playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.9f, 0.6f + 0.12f * state.stacks);

        if (state.stacks == MAX_FURY) {
            world.playSound(player.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.8f, 0.6f);
            world.spawnParticle(Particle.DUST, player.getLocation().add(0, 1.0, 0), 30, 0.5, 0.8, 0.5, 0, DUST_BLOOD);
            plugin.artifacts().sendFeedback(player,
                    "<dark_red><bold>FURY UNLEASHED</bold> <gray>- your strikes now <dark_purple>corrupt<gray>.");
        }
    }

    // ============================================================ CORRUPTION (the debuff)

    private boolean isCorrupted(LivingEntity entity) {
        Long until = corrupted.get(entity.getUniqueId());
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            corrupted.remove(entity.getUniqueId());
            return false;
        }
        return true;
    }

    private void corrupt(LivingEntity target, long durationMs) {
        boolean fresh = !isCorrupted(target);
        corrupted.merge(target.getUniqueId(), System.currentTimeMillis() + durationMs, Math::max);
        if (!fresh) {
            return;
        }
        World world = target.getWorld();
        Location center = target.getLocation().add(0, target.getHeight() / 2.0, 0);
        world.spawnParticle(Particle.SQUID_INK, center, 12, 0.3, 0.5, 0.3, 0.05);
        world.spawnParticle(Particle.DUST, center, 14, 0.4, 0.6, 0.4, 0, DUST_VOID);
        world.playSound(center, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.6f, 0.5f);
        if (target instanceof Player victim) {
            victim.sendActionBar(mm("<dark_purple>Corrupted <gray>- your healing is halved"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof LivingEntity entity && isCorrupted(entity)) {
            event.setAmount(event.getAmount() * CORRUPTED_HEAL_MULTIPLIER);
        }
    }

    /** Ambient visuals for corrupted entities + cleanup of expired entries. */
    private void tickCorruption() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = corrupted.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (entry.getValue() < now) {
                it.remove();
                continue;
            }
            if (!(Bukkit.getEntity(entry.getKey()) instanceof LivingEntity living) || living.isDead()) {
                it.remove();
                continue;
            }
            World world = living.getWorld();
            Location center = living.getLocation().add(0, living.getHeight() / 2.0, 0);
            world.spawnParticle(Particle.DUST, center, 3, 0.3, 0.5, 0.3, 0, DUST_VOID);
            if (random.nextInt(3) == 0) {
                world.spawnParticle(Particle.SQUID_INK, center, 1, 0.25, 0.4, 0.25, 0.01);
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        corrupted.remove(id);
        if (event.getEntity() instanceof Player) {
            fury.remove(id);
            awakenedUntil.remove(id); // the aura task notices and shuts itself down
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        fury.remove(event.getPlayer().getUniqueId());
    }

    // ============================================================ ABILITY: Corrupting Wave

    @Override
    public boolean useFirstAbility(Player player) {
        World world = player.getWorld();

        // Flat, yaw-only direction so the wave skims the ground wherever you look.
        double yaw = Math.toRadians(player.getLocation().getYaw());
        Vector dir = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());

        Location origin = player.getLocation().add(0, 0.9, 0);
        Location pos = origin.clone().add(dir.clone().multiply(1.0));

        world.playSound(origin, Sound.ENTITY_WITHER_SHOOT, 1.0f, 0.6f);
        world.playSound(origin, Sound.ENTITY_RAVAGER_ROAR, 0.7f, 0.9f);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, origin, 20, 0.5, 0.3, 0.5, 0.05);

        Set<UUID> struck = new HashSet<>();
        new BukkitRunnable() {
            private int step = 0;

            @Override
            public void run() {
                if (!player.isOnline() || step++ >= WAVE_STEPS) {
                    cancel();
                    return;
                }

                pos.add(dir.clone().multiply(WAVE_SPEED));

                // Walls stop the wave, but it can climb a single block step.
                Block block = pos.getBlock();
                if (block.getType().isSolid()) {
                    if (pos.clone().add(0, 1, 0).getBlock().getType().isSolid()) {
                        shatter(pos);
                        cancel();
                        return;
                    }
                    pos.add(0, 1, 0);
                }

                drawWave(pos, right, dir);

                for (LivingEntity target : world.getNearbyLivingEntities(pos, WAVE_HIT_RADIUS)) {
                    if (!canTarget(player, target) || !struck.add(target.getUniqueId())) {
                        continue;
                    }
                    hitWithWave(player, target, dir);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private void drawWave(Location center, Vector right, Vector dir) {
        World world = center.getWorld();
        for (double t = -1.0; t <= 1.0001; t += 0.25) {
            // Crescent shape: the middle leads, the tips trail behind.
            Location point = center.clone()
                    .add(right.clone().multiply(t * WAVE_WIDTH))
                    .subtract(dir.clone().multiply(t * t * 1.4));
            world.spawnParticle(Particle.DUST, point, 2, 0.05, 0.25, 0.05, 0, DUST_BLOOD);
            world.spawnParticle(Particle.DUST, point, 1, 0.05, 0.4, 0.05, 0, DUST_VOID);
            if (random.nextInt(3) == 0) {
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, point, 1, 0.05, 0.2, 0.05, 0.01);
            }
        }
    }

    private void shatter(Location at) {
        World world = at.getWorld();
        world.spawnParticle(Particle.LARGE_SMOKE, at, 12, 0.4, 0.5, 0.4, 0.02);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 14, 0.4, 0.5, 0.4, 0.05);
        world.playSound(at, Sound.ENTITY_WITHER_BREAK_BLOCK, 0.7f, 0.8f);
    }

    private void hitWithWave(Player caster, LivingEntity target, Vector dir) {
        double damage = WAVE_BASE_DAMAGE + WAVE_DAMAGE_PER_FURY * stacksOf(caster);
        if (!dealAbilityDamage(caster, target, damage)) {
            return; // blocked (spawn protection, etc.), so no side effects either
        }

        corrupt(target, WAVE_CORRUPT_MS);
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true, true));
        target.setVelocity(new Vector(dir.getX() * 0.9, 0.38, dir.getZ() * 0.9));
        addFury(caster); // landing the wave feeds the rage

        World world = target.getWorld();
        Location center = target.getLocation().add(0, target.getHeight() / 2.0, 0);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, center, 18, 0.3, 0.5, 0.3, 0.08);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, center, 6, 0.3, 0.4, 0.3, 0.1);
        world.playSound(center, Sound.ENTITY_WITHER_HURT, 0.8f, 0.7f);
    }

    /**
     * Damage from our own abilities. Returns false when nothing actually landed
     * (another plugin cancelled it, e.g. a protected region).
     */
    private boolean dealAbilityDamage(Player caster, LivingEntity target, double amount) {
        double healthBefore = target.getHealth();
        double absorptionBefore = target.getAbsorptionAmount();
        internalDamage = true;
        try {
            target.setNoDamageTicks(0); // so a recent melee hit can't swallow the ability damage
            target.damage(amount, caster);
        } finally {
            internalDamage = false;
        }
        return target.isDead()
                || target.getHealth() < healthBefore
                || target.getAbsorptionAmount() < absorptionBefore;
    }

    // ============================================================ AWAKENING: Dominion of Wrath

    @Override
    public boolean useAwakening(Player player) {
        if (isAwakened(player)) {
            return false;
        }
        UUID id = player.getUniqueId();
        int ticks = AWAKENING_SECONDS * 20;

        awakenedUntil.put(id, System.currentTimeMillis() + AWAKENING_SECONDS * 1000L);
        fury.remove(id); // Fury is locked at max while awakened (see stacksOf)

        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, ticks, 0, false, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, 0, false, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 0, false, false, true));

        player.showTitle(Title.title(
                mm("<dark_red><bold>WRATH UNLEASHED"),
                mm("<gray>Dominion of Wrath"),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1100), Duration.ofMillis(400))));

        Component shout = mm("<dark_red>The Wrath <gray>has awakened in the hands of <red>" + player.getName() + "<gray>!");
        for (Player nearby : player.getWorld().getNearbyPlayers(player.getLocation(), 60.0)) {
            nearby.sendMessage(shout);
        }

        awakeningBurst(player);

        new BukkitRunnable() {
            private int elapsed = 0; // ticks, advances by the 2-tick period

            @Override
            public void run() {
                boolean aborted = !player.isOnline() || player.isDead() || !awakenedUntil.containsKey(id);
                if (aborted) {
                    endAwakening(player, false);
                    cancel();
                    return;
                }
                if (elapsed >= ticks || System.currentTimeMillis() >= awakenedUntil.get(id)) {
                    endAwakening(player, true);
                    cancel();
                    return;
                }

                drawAura(player, elapsed);
                if (elapsed % 20 == 0) {
                    auraPulse(player);
                }
                elapsed += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
        return true;
    }

    /** Instant shockwave when the awakening starts. */
    private void awakeningBurst(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation();

        world.playSound(center, Sound.ENTITY_WITHER_SPAWN, 1.2f, 0.6f);
        world.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.5f);

        // Expanding ring of dark flame.
        new BukkitRunnable() {
            private int tick = 0;

            @Override
            public void run() {
                double radius = (tick + 1) * (BURST_RADIUS / 8.0);
                for (int i = 0; i < 48; i++) {
                    double angle = (Math.PI * 2 * i) / 48;
                    Location point = center.clone().add(Math.cos(angle) * radius, 0.15, Math.sin(angle) * radius);
                    world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, i % 2 == 0 ? DUST_BLOOD : DUST_VOID);
                    if (i % 6 == 0) {
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, point, 1, 0.05, 0.4, 0.05, 0.02);
                    }
                }
                if (++tick >= 8) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        // Everyone nearby is hurled away and corrupted.
        for (LivingEntity target : world.getNearbyLivingEntities(center, BURST_RADIUS)) {
            if (!canTarget(player, target) || !dealAbilityDamage(player, target, BURST_DAMAGE)) {
                continue;
            }
            Vector away = target.getLocation().toVector().subtract(center.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) {
                away = new Vector(0, 0, 1);
            }
            away.normalize().multiply(1.1).setY(0.45);
            target.setVelocity(away);
            corrupt(target, BURST_CORRUPT_MS);
        }
    }

    /** Twin helix, a ground ring marking the aura's edge, and rising souls. */
    private void drawAura(Player player, int elapsed) {
        World world = player.getWorld();
        Location base = player.getLocation();

        double spin = elapsed * 0.28;
        for (int strand = 0; strand < 2; strand++) {
            for (int k = 0; k < 6; k++) {
                double angle = spin + strand * Math.PI + k * 0.55;
                Location point = base.clone().add(Math.cos(angle) * 0.85, 0.1 + k * 0.38, Math.sin(angle) * 0.85);
                world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, strand == 0 ? DUST_BLOOD : DUST_VOID);
            }
        }

        if (elapsed % 10 == 0) {
            for (int i = 0; i < 32; i++) {
                double angle = (Math.PI * 2 * i) / 32;
                Location point = base.clone().add(Math.cos(angle) * AURA_RADIUS, 0.1, Math.sin(angle) * AURA_RADIUS);
                world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, DUST_BLOOD);
                if (i % 4 == 0) {
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, point, 1, 0.1, 0.1, 0.1, 0.01);
                }
            }
        }

        if (random.nextBoolean()) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = random.nextDouble() * AURA_RADIUS;
            Location point = base.clone().add(Math.cos(angle) * distance, 0.2, Math.sin(angle) * distance);
            world.spawnParticle(Particle.SOUL, point, 1, 0, 0.1, 0, 0.03);
        }
    }

    /** Once per second: wither, blind and corrupt everything in the aura, and drain life back. */
    private void auraPulse(Player caster) {
        Location center = caster.getLocation();
        World world = center.getWorld();
        boolean drained = false;

        for (LivingEntity target : world.getNearbyLivingEntities(center, AURA_RADIUS)) {
            if (!canTarget(caster, target)) {
                continue;
            }
            target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 50, 0, false, true, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, true));
            corrupt(target, 3_000L);
            soulStream(target.getLocation().add(0, 1.0, 0), caster.getLocation().add(0, 1.0, 0));
            drained = true;
        }

        if (drained) {
            AttributeInstance maxHealth = caster.getAttribute(Attribute.MAX_HEALTH);
            double cap = maxHealth != null ? maxHealth.getValue() : 20.0;
            caster.setHealth(Math.min(cap, caster.getHealth() + AURA_DRAIN_HEAL));
        }
        world.playSound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.5f);
    }

    /** A thin line of souls flowing from a victim to the caster. */
    private void soulStream(Location from, Location to) {
        World world = from.getWorld();
        Vector delta = to.toVector().subtract(from.toVector());
        int points = 8;
        for (int i = 1; i <= points; i++) {
            Location point = from.clone().add(delta.clone().multiply(i / (double) points));
            world.spawnParticle(Particle.SCULK_SOUL, point, 1, 0.05, 0.05, 0.05, 0);
        }
    }

    private void endAwakening(Player player, boolean natural) {
        UUID id = player.getUniqueId();
        awakenedUntil.remove(id);
        fury.remove(id);
        if (!natural || !player.isOnline() || player.isDead()) {
            return;
        }

        int ticks = EXHAUSTION_SECONDS * 20;
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 0, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 0, false, true, true));

        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.5f);
        world.spawnParticle(Particle.LARGE_SMOKE, player.getLocation().add(0, 1.0, 0), 25, 0.4, 0.6, 0.4, 0.02);
        plugin.artifacts().sendFeedback(player, "<dark_gray>The Wrath subsides... <red>exhausted<dark_gray>.");
    }
}
