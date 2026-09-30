package com.leaves.ash.client;

import com.leaves.ash.Ash;
import com.leaves.ash.client.combat.CombatEngine;
import com.leaves.ash.client.command.ClientCommands;
import com.leaves.ash.client.config.AshConfig;
import com.leaves.ash.client.fish.FishingController;
import com.leaves.ash.client.nav.MovementController;
import com.leaves.ash.client.player.RespawnHandler;
import net.fabricmc.api.ClientModInitializer;

import java.nio.file.Files;

public class AshClient implements ClientModInitializer {

    public static AshConfig CONFIG = new AshConfig();

    @Override
    public void onInitializeClient() {
        boolean existed = Files.exists(AshConfig.PATH);
        CONFIG.load();
        if (!existed) {
            CONFIG.save();
        }
        CombatEngine.INSTANCE.setConfig(CONFIG);
        MovementController.INSTANCE.setConfig(CONFIG);
        FishingController.INSTANCE.setConfig(CONFIG);
        RespawnHandler.INSTANCE.setConfig(CONFIG);
        Ash.LOGGER.info("ash client ready (attack key = {}, toggle = {}, approach = {})",
                CONFIG.keyCode, CONFIG.toggleMode, CONFIG.autoApproach);
    }

    /**
     * The only way the config is ever re-read after startup.
     *
     * There is deliberately no file watching and no reload-on-world-change: the file is read
     * once when Fabric loads the mod, and after that the only trigger is the #reload command.
     *
     * Reloading is a full reset, not just a re-read. Everything that was mid-flight was
     * planned against the old numbers, so it all gets dropped: the walk target, the A* path,
     * held movement keys, the queued script, the locked combat target and the aim controller.
     * The attack toggle itself is switched off too, so after a reload the engine is in a
     * known state rather than half-applying a changed reach or cooldown.
     */
    public static void reloadEverything() {
        CONFIG.load();
        MovementController.INSTANCE.hardReset();
        ClientCommands.INSTANCE.clearQueue();
        CombatEngine.INSTANCE.resetAll();
        FishingController.INSTANCE.hardReset();
        RespawnHandler.INSTANCE.hardReset();
    }
}
