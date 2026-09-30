package com.leaves.ash.client.command;

import com.leaves.ash.client.AshClient;
import com.leaves.ash.client.combat.CombatEngine;
import com.leaves.ash.client.config.AshConfig;
import com.leaves.ash.client.debug.EntityScanner;
import com.leaves.ash.client.fish.FishingController;
import com.leaves.ash.client.nav.MovementController;
import com.leaves.ash.client.player.RespawnHandler;
import com.leaves.ash.client.util.Names;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Client side commands typed into the vanilla chat box.
 *
 * Anything starting with # is intercepted before ClientPacketListener#sendChat and never
 * reaches the server. Anything else, including every vanilla / command, is untouched.
 *
 * Feedback goes through LocalPlayer#displayClientMessage, which routes to the local
 * ChatListener and sends no packet.
 */
public final class ClientCommands {

    public static final ClientCommands INSTANCE = new ClientCommands();

    private static final int MAX_SCRIPT_LINES = 2000;
    private static final int MAX_SCRIPT_DEPTH = 3;

    private final Deque<String> queue = new ArrayDeque<>();
    private long nextRunAt;
    private int scriptDepth;

    private ClientCommands() {
    }

    /** @param raw the full line as typed, including the leading # */
    public void execute(String raw) {
        if (raw == null) {
            return;
        }
        String text = raw.trim();
        if (!text.startsWith("#")) {
            return;
        }
        String body = text.substring(1).trim();
        if (body.isEmpty()) {
            help();
            return;
        }
        int space = body.indexOf(' ');
        String name = (space < 0 ? body : body.substring(0, space)).toLowerCase();
        String args = space < 0 ? "" : body.substring(space + 1).trim();

        switch (name) {
            case "reload" -> reload();
            case "goto" -> goTo(args);
            case "stop" -> stop();
            case "debug" -> debug();
            case "fish" -> fish(args);
            case "respawn" -> respawn();
            case "run" -> run(args);
            case "wait" -> waitFor(args);
            case "where" -> where();
            case "scan" -> scan(args);
            case "filters" -> filters();
            case "near" -> near(args);
            case "help" -> help();
            default -> reply("unknown command: #" + name);
        }
    }

    /** Runs queued script lines, one per tick unless a #wait is in effect. */
    public void tick() {
        if (MovementController.INSTANCE.isBlocked() && !queue.isEmpty()) {
            // the walker hit an abort condition; the rest of the script assumes we can move
            queue.clear();
            scriptDepth = 0;
            reply("script dropped after movement abort");
            return;
        }
        if (queue.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextRunAt) {
            return;
        }
        nextRunAt = 0L;
        execute(queue.poll());
    }

    public void clearQueue() {
        queue.clear();
        nextRunAt = 0L;
        scriptDepth = 0;
    }

    private void reload() {
        AshClient.reloadEverything();
        reply("reloaded: movement, script and attack reset, attack is now OFF");
    }

    private void goTo(String args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            reply("no player");
            return;
        }
        if (args.isEmpty()) {
            MovementController.INSTANCE.clearManualGoal();
            reply("stopped");
            return;
        }
        String[] parts = args.split("\\s+");
        if (parts.length < 3) {
            reply("usage: #goto <x> <y> <z>   (~ and ~n work)");
            return;
        }
        try {
            double x = coordinate(parts[0], player.getX());
            double y = coordinate(parts[1], player.getY());
            double z = coordinate(parts[2], player.getZ());
            BlockPos block = new BlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z));
            MovementController.INSTANCE.setManualGoal(new Vec3(block.getX() + 0.5D, block.getY() + 0.05D, block.getZ() + 0.5D));
            reply("going to " + block.getX() + " " + block.getY() + " " + block.getZ());
        } catch (NumberFormatException e) {
            reply("bad coordinate: " + args);
        }
    }

    private void stop() {
        clearQueue();
        MovementController.INSTANCE.clearManualGoal();
        reply("stopped");
    }

    private void debug() {
        AshConfig config = AshClient.CONFIG;
        if (config == null) {
            return;
        }
        config.debug = !config.debug;
        config.save();
        reply("debug = " + config.debug);
    }

    private void fish(String args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            reply("no player");
            return;
        }
        String mode = args.trim().toLowerCase();
        boolean wanted;
        if (mode.isEmpty()) {
            wanted = !FishingController.INSTANCE.isActive();
        } else if ("on".equals(mode) || "start".equals(mode)) {
            wanted = true;
        } else if ("off".equals(mode) || "stop".equals(mode)) {
            wanted = false;
        } else {
            reply("usage: #fish [on|off]");
            return;
        }
        boolean running = FishingController.INSTANCE.isActive();
        if (wanted == running) {
            reply("fishing is already " + (running ? "on" : "off"));
            return;
        }
        FishingController.INSTANCE.toggle(mc);
    }

    /** Toggles answering the death screen automatically. Survives a restart - it is saved. */
    private void respawn() {
        AshConfig config = AshClient.CONFIG;
        if (config == null) {
            reply("no config");
            return;
        }
        config.autoRespawn = !config.autoRespawn;
        config.save();
        RespawnHandler.INSTANCE.hardReset();
        reply("auto respawn = " + config.autoRespawn
                + " (" + config.autoRespawnMinMs + "-" + config.autoRespawnMaxMs + "ms after death)");
    }

    private void run(String args) {
        if (args.isEmpty()) {
            reply("usage: #run <path>   e.g. #run D:\\script.txt");
            return;
        }
        if (scriptDepth >= MAX_SCRIPT_DEPTH) {
            reply("script nesting too deep");
            return;
        }
        Path path;
        try {
            path = Paths.get(args);
        } catch (RuntimeException e) {
            reply("bad path: " + args);
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            reply("cannot read " + args + " (" + e.getMessage() + ")");
            return;
        }
        if (lines.size() > MAX_SCRIPT_LINES) {
            reply("script too long: " + lines.size() + " lines");
            return;
        }
        queue.clear();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            queue.add(trimmed);
        }
        nextRunAt = 0L;
        scriptDepth++;
        reply("queued " + queue.size() + " lines from " + path.getFileName());
    }

    private void waitFor(String args) {
        try {
            long millis = Long.parseLong(args.trim());
            nextRunAt = System.currentTimeMillis() + Math.max(0L, millis);
        } catch (NumberFormatException e) {
            reply("usage: #wait <millis>");
        }
    }

    private void where() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            reply("no player");
            return;
        }
        BlockPos block = player.blockPosition();
        reply(String.format("%d %d %d  (%s)", block.getX(), block.getY(), block.getZ(),
                player.level().dimension().location()));
    }

    /** Shows exactly what is attackable right now, straight from the loaded config. */
    /**
     * Lists what is actually around you, with the exact name string the filter compares
     * against and whether it currently matches.
     *
     * This exists because "the whitelist does not work" almost always means the name in the
     * config file is not byte-identical to the name on the entity - a different case, a
     * hidden character, a colour code, or mojibake from an encoding mismatch. Guessing at
     * that from a config file is hopeless; reading it off the live entity is not.
     */
    private void near(String args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            reply("no player");
            return;
        }
        AshConfig config = AshClient.CONFIG;
        if (config == null) {
            reply("no config");
            return;
        }
        double radius = 8.0D;
        if (!args.trim().isEmpty()) {
            try {
                radius = Math.max(1.0D, Math.min(48.0D, Double.parseDouble(args.trim())));
            } catch (NumberFormatException e) {
                reply("usage: #near [radius]");
                return;
            }
        }

        AABB area = player.getBoundingBox().inflate(radius);
        List<Entity> entities = mc.level.getEntities(player, area, e -> e instanceof LivingEntity);
        if (entities.isEmpty()) {
            reply("nothing living within " + (int) radius + " blocks");
            return;
        }
        entities.sort(Comparator.comparingDouble(e -> player.distanceTo(e)));

        reply("within " + (int) radius + " blocks (" + entities.size() + "):");
        int hits = 0;
        for (Entity entity : entities) {
            String name = Names.plain(entity);
            String type = String.valueOf(EntityType.getKey(entity.getType()));
            boolean match = config.matchesFilterText(name, type);
            if (match) {
                hits++;
            }
            // when the camera is left alone, reach is not the only thing that decides: the
            // target also has to be somewhere the current view direction plausibly covers
            String cone = "";
            if (!config.rotateToTarget) {
                double angle = CombatEngine.angleOffCenter(player, entity);
                boolean inside = config.attackFovDeg >= 360.0 || angle <= config.attackFovDeg * 0.5;
                cone = String.format("  off=%3.0fdeg%s", angle, inside ? "" : " OUT");
            }
            // length is printed on purpose: it is how you spot a zero-width or double-width
            // character hiding inside a name that looks correct on screen
            reply(String.format("%s  \"%s\" len=%d  %s  %.1fm  %s%s",
                    match ? "HIT " : "miss", name, name.length(), type, player.distanceTo(entity),
                    entity.getClass().getSimpleName(), cone));
        }
        reply(hits + " of " + entities.size() + " match the current filter");
        if (hits == 0) {
            reply("nothing here will be attacked - copy a name above into nameFilters");
        }
    }

    private void filters() {
        AshConfig config = AshClient.CONFIG;
        if (config == null) {
            reply("no config");
            return;
        }
        List<String> filters = config.filters();
        reply("mode = " + config.nameFilterMode
                + (config.nameFilterMode == AshConfig.FilterMode.OFF ? " (everything allowed)" : "")
                + ", entries = " + filters.size());
        if (filters.isEmpty()) {
            if (config.nameFilterMode == AshConfig.FilterMode.WHITELIST) {
                reply("whitelist is empty: nothing will be attacked");
            }
            return;
        }
        StringBuilder line = new StringBuilder();
        for (String filter : filters) {
            if (line.length() + filter.length() > 60) {
                reply(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(filter);
        }
        if (line.length() > 0) {
            reply(line.toString());
        }
    }

    private void scan(String args) {
        if ("clear".equalsIgnoreCase(args.trim())) {
            EntityScanner.INSTANCE.clear();
            reply("entity scan cleared");
            return;
        }
        reply("usage: #scan clear");
    }

    private void help() {
        reply("#reload  #goto x y z  #stop  #debug  #run <path>  #wait <ms>  #where  #fish [on|off]  #respawn  #filters  #near [r]  #scan clear");
    }

    private static double coordinate(String token, double origin) {
        if ("~".equals(token)) {
            return origin;
        }
        if (token.startsWith("~")) {
            String rest = token.substring(1);
            return rest.isEmpty() ? origin : origin + Double.parseDouble(rest);
        }
        return Double.parseDouble(token);
    }

    private static void reply(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[ash] " + message), false);
        }
        System.out.println("[ash] " + message);
    }
}
