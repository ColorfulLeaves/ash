package com.leaves.ash.client.fish;

import com.leaves.ash.client.config.AshConfig;
import com.leaves.ash.client.mixin.FishingHookAccessor;
import com.leaves.ash.client.nav.MovementController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;

import java.util.Random;

/**
 * Casts, waits for the bite and reels in, using the bobber's synched data as the trigger.
 *
 * Timing is the whole anti-detection story here, because there is nothing else to hide:
 * the cast and the reel are the vanilla right-click path, so the packets on the wire are
 * exactly a player's. What gives a fishing bot away is reeling in 40 ms after the bite,
 * every single time, and never stopping. So:
 *
 *   - the reaction to a bite is random and human-scaled, with an occasional distracted
 *     late reaction;
 *   - the gap before recasting is random too;
 *   - after N catches there is a longer break, because nobody lands 200 fish without ever
 *     looking away from the screen.
 *
 * An open screen does not interrupt it. Escape menu, inventory, even the chat box the
 * #fish command was typed into: all of them keep ticking, and neither a cast nor a reel
 * needs the keyboard or the mouse, so there is nothing to fight over. Death and spectator
 * mode still stop it, because there is nobody left holding the rod.
 */
public final class FishingController {

    public static final FishingController INSTANCE = new FishingController();

    private enum State {
        /** no line out, waiting for the recast gap to pass */
        IDLE,
        /** right-click sent, waiting for the server to spawn the bobber */
        CASTING,
        /** line is in the water, watching for the bite */
        WAITING,
        /** bite registered, waiting out the reaction delay before reeling */
        REELING
    }

    private static final long CAST_TIMEOUT_MS = 4000L;

    private final Random random = new Random();

    private AshConfig config;

    private boolean active;
    private State state = State.IDLE;
    private long castRequestedAt;
    private long lineOutAt;
    private long reelAt;
    private long nextCastAt;
    private int catches;

    private FishingController() {
    }

    public void setConfig(AshConfig config) {
        this.config = config;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * @return true when fishing is now running, false when it was switched off or refused
     */
    public boolean toggle(Minecraft mc) {
        if (active) {
            stop(mc, null);
            return false;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return false;
        }
        // only the main hand is considered, deliberately: reaching for the off-hand or
        // hunting through the hotbar is not something a player does mid-cycle
        if (!player.getMainHandItem().is(Items.FISHING_ROD)) {
            message(mc, "hold a fishing rod in your main hand first");
            return false;
        }
        active = true;
        state = State.IDLE;
        catches = 0;
        nextCastAt = 0L;
        message(mc, "fishing on");
        return true;
    }

    /** Stops with a reason on the action bar. Used when something went wrong. */
    public void stop(Minecraft mc, String reason) {
        reset();
        if (reason != null) {
            message(mc, "fishing stopped: " + reason);
        }
    }

    /** Silent full reset: world change, #reload. */
    public void hardReset() {
        reset();
    }

    private void reset() {
        active = false;
        state = State.IDLE;
        castRequestedAt = 0L;
        lineOutAt = 0L;
        reelAt = 0L;
        nextCastAt = 0L;
        catches = 0;
    }

    public void tick(Minecraft mc) {
        if (config == null || !config.enabled || !active) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.getConnection() == null) {
            // not a reason to give up: the loop resumes once the player exists again
            return;
        }
        if (player.isDeadOrDying() || player.isSpectator()) {
            // the only two hard stops: there is no line to tend without a living player
            // holding the rod. Everything else keeps going.
            //
            // the cycle in flight is dropped once, on the way in. the server removes the
            // bobber the moment the player dies, so a RUNNING cast or reel would otherwise
            // sit around waiting for something that is never coming back
            if (state != State.IDLE) {
                state = State.IDLE;
                nextCastAt = System.currentTimeMillis() + recastGap();
            }
            return;
        }
        if (!config.fishWhileScreenOpen && mc.screen != null) {
            return;
        }
        if (!player.getMainHandItem().is(Items.FISHING_ROD)) {
            stop(mc, "main hand is no longer a fishing rod");
            return;
        }
        if (MovementController.INSTANCE.isBlocked()) {
            // the server moved us or the walker gave up; casting from a stale position is
            // how you get a contradiction with the server's idea of where the line is
            stop(mc, "movement was aborted");
            return;
        }

        long now = System.currentTimeMillis();
        FishingHook hook = live(player.fishing);

        switch (state) {
            case IDLE -> idle(mc, player, hook, now);
            case CASTING -> casting(mc, hook, now);
            case WAITING -> waiting(mc, hook, now);
            case REELING -> reeling(mc, player, hook, now);
        }
    }

    private void idle(Minecraft mc, LocalPlayer player, FishingHook hook, long now) {
        if (hook != null) {
            // a line is already out - adopt it instead of casting, which would reel it in
            state = State.WAITING;
            lineOutAt = now;
            return;
        }
        if (now < nextCastAt) {
            return;
        }
        use(mc, player);
        state = State.CASTING;
        castRequestedAt = now;
        debug(mc, "cast");
    }

    private void casting(Minecraft mc, FishingHook hook, long now) {
        if (hook != null) {
            state = State.WAITING;
            lineOutAt = now;
            return;
        }
        if (now - castRequestedAt > CAST_TIMEOUT_MS) {
            // never got a bobber: most likely we were not aimed at water, or the item use
            // was rejected. Back off instead of spamming right-click.
            debug(mc, "no bobber appeared, backing off");
            state = State.IDLE;
            nextCastAt = now + 1500L;
        }
    }

    private void waiting(Minecraft mc, FishingHook hook, long now) {
        if (hook == null) {
            // the line is gone without us reeling - broke, or the server removed it
            state = State.IDLE;
            nextCastAt = now + recastGap();
            return;
        }
        boolean bite = isBiting(hook);
        long waited = now - lineOutAt;
        boolean stale = config.fishGiveUpMs > 0 && waited > config.fishGiveUpMs;
        if (!bite && !stale) {
            return;
        }
        state = State.REELING;
        reelAt = now + reactionDelay();
        debug(mc, bite ? "bite" : "no bite for " + (waited / 1000L) + "s, reeling anyway");
    }

    private void reeling(Minecraft mc, LocalPlayer player, FishingHook hook, long now) {
        if (hook == null) {
            state = State.IDLE;
            nextCastAt = now + recastGap();
            return;
        }
        if (now < reelAt) {
            return;
        }
        use(mc, player);
        catches++;
        state = State.IDLE;
        long pause = 0L;
        if (config.fishPauseEvery > 0 && catches % config.fishPauseEvery == 0) {
            pause = between(config.fishPauseMinMs, config.fishPauseMaxMs);
            debug(mc, "break after " + catches + " catches");
        }
        nextCastAt = now + recastGap() + pause;
    }

    /** Vanilla right-click on the main hand: this is what sends ServerboundUseItemPacket. */
    private static void use(Minecraft mc, LocalPlayer player) {
        InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (result.shouldSwing()) {
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private static boolean isBiting(FishingHook hook) {
        return Boolean.TRUE.equals(hook.getEntityData().get(FishingHookAccessor.ash$dataBiting()));
    }

    private static FishingHook live(FishingHook hook) {
        return hook == null || hook.isRemoved() ? null : hook;
    }

    /**
     * Normal reaction, except for a slice of the time where the "player" did not notice the
     * bobber straight away. A bot that always reacts inside one window is detectable by the
     * variance alone, so the distribution has to have a tail.
     */
    private long reactionDelay() {
        if (config.fishSlowReactionChance > 0.0 && random.nextDouble() < config.fishSlowReactionChance) {
            return between(config.fishSlowReactionMinMs, config.fishSlowReactionMaxMs);
        }
        return between(config.fishReactionMinMs, config.fishReactionMaxMs);
    }

    private long recastGap() {
        return between(config.fishRecastMinMs, config.fishRecastMaxMs);
    }

    private int between(int low, int high) {
        return low + random.nextInt(Math.max(1, high - low));
    }

    private void debug(Minecraft mc, String message) {
        if (config.debug) {
            message(mc, "fish: " + message);
        }
    }

    /** Local display only: displayClientMessage routes to the client ChatListener, no packet. */
    private void message(Minecraft mc, String message) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[ash] " + message), true);
        }
        System.out.println("[ash] " + message);
    }
}
