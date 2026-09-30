package com.leaves.ash.client.debug;

import com.leaves.ash.client.AshClient;
import com.leaves.ash.client.combat.CombatEngine;
import com.leaves.ash.client.config.AshConfig;
import com.leaves.ash.client.util.Names;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Development tool, active only while debug=true.
 *
 * RPG servers spawn mobs whose vanilla type tells you nothing: a "Dungeon Slime" may
 * be a reskinned zombie, a boss may be an armour stand, and none of them necessarily
 * implement Enemy. Class based filtering is useless there, so the reliable key is the
 * display name.
 *
 * Sneak-punch a mob with an empty hand and it lands in config/ash-entity-scan.txt with
 * its type id, class, health, hitbox and scoreboard tags. The bottom of that file lists
 * every distinct name seen, ready to paste into nameFilters.
 */
public final class EntityScanner {

    public static final EntityScanner INSTANCE = new EntityScanner();

    public static final Path PATH = Paths.get("config", "ash-entity-scan.txt");

    private final Map<String, Sample> samples = new LinkedHashMap<>();

    private EntityScanner() {
    }

    /**
     * Called at the head of MultiPlayerGameMode#attack. Only the player's own swings
     * count: engine swings are flagged, and the sneak + empty hand requirement keeps
     * ordinary combat out of the log.
     */
    public void onManualAttack(Player player, Entity target) {
        AshConfig config = AshClient.CONFIG;
        if (config == null || !config.debug || !config.scanManualAttacks) {
            return;
        }
        if (CombatEngine.INSTANCE.isEngineSwinging()) {
            return;
        }
        if (!(player instanceof LocalPlayer local)) {
            return;
        }
        if (!player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) {
            return;
        }
        if (target == null || target.isRemoved()) {
            return;
        }

        Sample sample = Sample.of(target);
        Sample previous = samples.put(sample.key, sample);
        write();

        String line = "[ash] scan " + sample.name + " (" + sample.typeId + ")";
        local.displayClientMessage(Component.literal(line), true);
        if (previous == null) {
            // console gets the escaped form: a raw Chinese name would be mojibake there
            System.out.println("[ash] scan " + Names.escaped(sample.name) + " (" + sample.typeId + ")");
        }
    }

    public void clear() {
        samples.clear();
        write();
    }

    private void write() {
        StringBuilder out = new StringBuilder();
        // BOM so Notepad and friends recognise this as UTF-8 instead of the local code page
        out.append('\uFEFF');
        out.append("# ash entity scan - sneaking, empty hand, left click\n");
        out.append("# fields: type | display name | class | hostile | health | hitbox | custom name | scoreboard tags\n");
        out.append("\n");
        for (Sample sample : samples.values()) {
            out.append("type=").append(sample.typeId)
                    .append(" | name=").append(sample.name)
                    .append(" | class=").append(sample.className)
                    .append(" | hostile=").append(sample.hostile)
                    .append(" | hp=").append(sample.maxHealth)
                    .append(" | box=").append(sample.width).append('x').append(sample.height)
                    .append(" | custom=").append(sample.customName)
                    .append(" | tags=").append(sample.tags)
                    .append('\n');
        }
        out.append("\n=== distinct names (paste into nameFilters) ===\n");
        Set<String> names = new TreeSet<>();
        for (Sample sample : samples.values()) {
            names.add(sample.name);
        }
        out.append(String.join(", ", names)).append('\n');

        try {
            Path parent = PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(PATH, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[ash] failed to write entity scan: " + e.getMessage());
        }
    }

    private static final class Sample {
        final String key;
        final String typeId;
        final String name;
        final String className;
        final boolean hostile;
        final float maxHealth;
        final float width;
        final float height;
        final boolean customName;
        final String tags;

        private Sample(String key, String typeId, String name, String className, boolean hostile,
                       float maxHealth, float width, float height, boolean customName, String tags) {
            this.key = key;
            this.typeId = typeId;
            this.name = name;
            this.className = className;
            this.hostile = hostile;
            this.maxHealth = maxHealth;
            this.width = width;
            this.height = height;
            this.customName = customName;
            this.tags = tags;
        }

        static Sample of(Entity entity) {
            ResourceLocation id = EntityType.getKey(entity.getType());
            String name = Names.plain(entity);
            float maxHealth = entity instanceof LivingEntity living ? living.getMaxHealth() : 0.0F;
            Set<String> rawTags = entity.getTags();
            return new Sample(
                    id + "|" + name,
                    String.valueOf(id),
                    name.isEmpty() ? "<unnamed>" : name,
                    entity.getClass().getSimpleName(),
                    entity instanceof Enemy,
                    maxHealth,
                    entity.getBbWidth(),
                    entity.getBbHeight(),
                    entity.hasCustomName(),
                    rawTags.isEmpty() ? "-" : String.valueOf(rawTags)
            );
        }
    }
}
