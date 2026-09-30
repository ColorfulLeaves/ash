package com.leaves.ash.client.player;

import com.leaves.ash.client.config.AshConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.Random;

/**
 * Answers the death screen so an unattended session does not end the first time something
 * wins a fight.
 *
 * The whole thing is LocalPlayer#respawn followed by clearing the screen, byte for byte
 * what the vanilla "Respawn" button does: one ServerboundClientCommandPacket with
 * PERFORM_RESPAWN, then KeyMapping#resetToggleKeys. Nothing else is sent, nothing is
 * synthesised, and there is no way to tell it apart from the click.
 *
 * The delay before pressing exists for the same reason every other timing here exists. A
 * player who dies has to read the death message, sigh, and move the mouse. Respawning on
 * the exact tick the screen opens is not a behaviour a human can produce, so the wait is
 * random and weighted well past the point where anyone clicks instinctively.
 */
public final class RespawnHandler {

    public static final RespawnHandler INSTANCE = new RespawnHandler();

    private final Random random = new Random();

    private AshConfig config;

    /** wall clock at which the pending respawn fires; 0 means "nothing scheduled" */
    private long respawnAt;

    private RespawnHandler() {
    }

    public void setConfig(AshConfig config) {
        this.config = config;
    }

    public void hardReset() {
        respawnAt = 0L;
    }

    public boolean isWaiting() {
        return respawnAt != 0L;
    }

    public void tick(Minecraft mc) {
        if (config == null || !config.autoRespawn) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.getConnection() == null) {
            return;
        }
        if (!(mc.screen instanceof DeathScreen)) {
            // covers Respawn itself: the screen goes away, so the schedule has to go with it
            respawnAt = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (respawnAt == 0L) {
            respawnAt = now + between(config.autoRespawnMinMs, config.autoRespawnMaxMs);
            if (config.debug) {
                note(mc, "respawn in " + (respawnAt - now) + "ms");
            }
            return;
        }
        if (now < respawnAt) {
            return;
        }
        respawnAt = 0L;
        player.respawn();
        mc.setScreen(null);
        note(mc, "respawned");
    }

    private int between(int low, int high) {
        return low + random.nextInt(Math.max(1, high - low));
    }

    private void note(Minecraft mc, String message) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[ash] " + message), true);
        }
        System.out.println("[ash] " + message);
    }
}
