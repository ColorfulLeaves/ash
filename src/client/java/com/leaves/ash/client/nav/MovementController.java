package com.leaves.ash.client.nav;

import com.leaves.ash.client.combat.CombatEngine;
import com.leaves.ash.client.config.AshConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;

/**
 * Drives the vanilla movement keys.
 *
 * The rule this class exists to enforce: never send a position packet. Set the same
 * booleans a keyboard would set and let LocalPlayer#aiStep -> Input#tick -> travel do
 * the physics, so what reaches the server is byte for byte what a player produces. Any
 * bot that computes its own movement has to re-implement collision, step height, water,
 * ladders and knockback, and every error there is a detectable discrepancy.
 *
 * How the keys are driven: LocalPlayer#input is a KeyboardInput, whose tick() rebuilds
 * the impulse fields from mc.options every tick, so writing the impulse fields directly
 * would be overwritten. KeyMapping#setDown is the real input, so that is what we set.
 */
public final class MovementController {

    public static final MovementController INSTANCE = new MovementController();

    /** how long every hard stop refuses new goals */
    private static final long FAIL_COOLDOWN_MS = 3000L;

    private final Held forward = new Held();
    private final Held jump = new Held();
    private final Held sprint = new Held();

    private AshConfig config;

    /** set by #goto; works whether or not the combat toggle is engaged */
    private Vec3 manualGoal;
    /** set by the combat engine when autoApproach cannot find anything in reach */
    private Vec3 goal;
    private Vec3 pathGoal;
    private List<BlockPos> path = Collections.emptyList();
    private int index;

    private long nextRepathAt;
    private long jumpUntil;
    private long reassertAt;

    private double lastProgressDistance = Double.MAX_VALUE;
    private long lastProgressAt;
    private long lastHopAt;

    /**
     * Set by every hard stop. While it is in the future, new goals are refused, which
     * stops the combat engine from instantly re-arming autoApproach and re-planning the
     * same impossible path twenty times a second.
     */
    private long blockedUntil;
    /** consecutive time spent in water/lava before it counts as "in trouble" */
    private long fluidSince;

    private MovementController() {
    }

    public void setConfig(AshConfig config) {
        this.config = config;
    }

    public void setGoal(Vec3 goal) {
        if (isBlocked()) {
            return;
        }
        this.goal = goal;
    }

    public void clearGoal() {
        this.goal = null;
        abort();
    }

    public void setManualGoal(Vec3 manualGoal) {
        this.manualGoal = manualGoal;
        blockedUntil = 0L;
        fluidSince = 0L;
    }

    public void clearManualGoal() {
        this.manualGoal = null;
        abort();
    }

    public boolean isBlocked() {
        return System.currentTimeMillis() < blockedUntil;
    }

    /**
     * Everything stops here: keys released, path dropped, goals cleared, and the controller
     * refuses new work for a moment afterwards.
     *
     * The cooldown matters more than it looks. Without it the combat engine re-arms
     * autoApproach on the very next tick and re-plans the same unreachable path forever,
     * which is exactly the kind of packet stream that looks like a stuck bot.
     */
    public void emergencyStop(Minecraft mc, String reason) {
        long now = System.currentTimeMillis();
        // longer than one replan interval, short enough that a manual retry feels instant
        blockedUntil = now + FAIL_COOLDOWN_MS;
        manualGoal = null;
        goal = null;
        pathGoal = null;
        abort();
        lastProgressDistance = Double.MAX_VALUE;
        lastProgressAt = now;
        lastHopAt = now;
        fluidSince = 0L;
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[ash] stopped: " + reason), true);
        }
        System.out.println("[ash] movement stopped: " + reason);
    }

    /** Drops keys and the cached path but keeps the goal, so the next tick replans. */
    public void abort() {
        path = Collections.emptyList();
        index = 0;
        releaseInputs();
    }

    /** Called when the world is swapped out entirely. */
    public void hardReset() {
        manualGoal = null;
        goal = null;
        abort();
        pathGoal = null;
        blockedUntil = 0L;
        fluidSince = 0L;
    }

    public void tick(Minecraft mc) {
        if (config == null || !config.enabled) {
            reset();
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            reset();
            return;
        }
        if (mc.screen != null || player.isDeadOrDying() || player.isSpectator()) {
            reset();
            return;
        }

        boolean manual = manualGoal != null;
        if (!manual && (!config.autoApproach || !CombatEngine.INSTANCE.isActive())) {
            reset();
            return;
        }
        Vec3 active = manual ? manualGoal : goal;
        if (active == null) {
            reset();
            return;
        }

        long now = System.currentTimeMillis();

        if (!sane(mc, player, now)) {
            return;
        }

        double distanceToGoal = active.distanceTo(player.position());
        if (distanceToGoal <= config.approachStopDistance) {
            if (manual) {
                manualGoal = null;
            }
            reset();
            return;
        }

        if (path.isEmpty() || now >= nextRepathAt
                || (pathGoal != null && active.distanceTo(pathGoal) > 1.5D)) {
            if (!replan(mc, player, active, now)) {
                return;
            }
        }
        if (path.isEmpty()) {
            // complete but empty means we were already standing on the goal block
            abort();
            return;
        }

        while (index < path.size() && reached(player, path.get(index))) {
            index++;
        }
        if (index >= path.size()) {
            abort();
            return;
        }

        BlockPos waypoint = path.get(index);
        Vec3 point = waypoint.getCenter();

        double deltaYaw = Mth.wrapDegrees(yawTo(player, point) - player.getYRot());
        double step = Mth.clamp(deltaYaw, -config.moveMaxYawStepDeg, config.moveMaxYawStepDeg);
        player.setYRot(Mth.wrapDegrees(player.getYRot() + (float) step));

        boolean facing = Math.abs(deltaYaw) <= 30.0D;
        boolean wantJump = waypoint.getY() > player.getY() + 0.4D
                || (config.jumpWhenBlocked && player.horizontalCollision && player.onGround());
        if (wantJump) {
            jumpUntil = now + 260L;
        }
        boolean wantSprint = config.sprintWhenFar && facing && distanceToGoal > 8.0D;

        apply(mc, facing, now < jumpUntil, wantSprint, now);

        if (distanceToGoal < lastProgressDistance - 0.25D) {
            lastProgressDistance = distanceToGoal;
            lastProgressAt = now;
        } else if (now - lastProgressAt > config.abortStuckMs) {
            emergencyStop(mc, "no progress for " + (config.abortStuckMs / 1000) + "s");
            return;
        } else if (now - lastProgressAt > 2000L && now - lastHopAt > 2000L) {
            // wedged against something the planner did not model: hop and replan.
            // Note this deliberately does not touch lastProgressAt - only real movement
            // counts as progress, so the stuck timer above can still fire.
            lastHopAt = now;
            jumpUntil = now + 320L;
            nextRepathAt = 0L;
            abort();
        }
    }

    /**
     * Conditions that mean "stop walking right now". Each of these either means the
     * server has overridden us or that we have wandered somewhere the planner has no
     * model for. Swimming lava, both involve physics this controller does not drive, and
     * trying anyway produces motion no player could have produced.
     *
     * @return false when an abort already happened this tick
     */
    private boolean sane(Minecraft mc, LocalPlayer player, long now) {
        Level level = mc.level;
        if (player.getY() < level.getMinBuildHeight()) {
            emergencyStop(mc, "below the world floor");
            return false;
        }
        if (config.stopOnFluid) {
            if (player.isInWater() || player.isInLava()) {
                if (fluidSince == 0L) {
                    fluidSince = now;
                } else if (now - fluidSince > 500L) {
                    // half a second so wading through a one block stream does not trip it
                    emergencyStop(mc, "entered " + (player.isInLava() ? "lava" : "water"));
                    return false;
                }
            } else {
                fluidSince = 0L;
            }
        }
        return true;
    }

    /** @return false when the caller must bail out this tick */
    private boolean replan(Minecraft mc, LocalPlayer player, Vec3 active, long now) {
        nextRepathAt = now + Math.max(150, config.pathRepathMs);
        pathGoal = active;

        Level level = mc.level;
        BlockPos start = player.blockPosition();
        BlockPos end = standableNear(level, floor(active));
        if (end == null) {
            if (config.abortOnUnreachable) {
                emergencyStop(mc, "nowhere to stand near the goal");
                return false;
            }
            path = Collections.emptyList();
            index = 0;
            return true;
        }

        PathFinder.PathResult result = PathFinder.find(level, start, end,
                config.pathMaxNodes, config.pathAllowDiagonal);
        if (config.abortOnUnreachable && !result.isComplete()) {
            BlockPos tail = result.tail();
            String detail = tail == null || tail.equals(start)
                    ? "no walkable route out of the current position"
                    : "goal unreachable, closest reachable point is " + tail.toShortString();
            emergencyStop(mc, detail);
            return false;
        }

        path = result.nodes();
        index = 0;
        if (path.isEmpty() && config.debug && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[ash] empty path to " + end), true);
        }
        return true;
    }

    private void apply(Minecraft mc, boolean wantForward, boolean wantJump, boolean wantSprint, long now) {
        Options options = mc.options;
        boolean reassert = now >= reassertAt;
        forward.apply(options.keyUp, wantForward, reassert);
        jump.apply(options.keyJump, wantJump, reassert);
        sprint.apply(options.keySprint, wantSprint, reassert);
        if (reassert) {
            // KeyMapping#releaseAll runs whenever a screen opens, so re-assert periodically
            // instead of trusting the last write
            reassertAt = now + 400L;
        }
    }

    private void releaseInputs() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return;
        }
        forward.release(mc.options.keyUp);
        jump.release(mc.options.keyJump);
        sprint.release(mc.options.keySprint);
    }

    private void reset() {
        goal = null;
        pathGoal = null;
        path = Collections.emptyList();
        index = 0;
        lastProgressDistance = Double.MAX_VALUE;
        releaseInputs();
    }

    private static boolean reached(LocalPlayer player, BlockPos waypoint) {
        double dx = player.getX() - (waypoint.getX() + 0.5D);
        double dz = player.getZ() - (waypoint.getZ() + 0.5D);
        return dx * dx + dz * dz <= 0.5625D && Math.abs(player.getY() - waypoint.getY()) < 1.7D;
    }

    private static double yawTo(LocalPlayer player, Vec3 point) {
        return Math.toDegrees(Math.atan2(point.z - player.getZ(), point.x - player.getX())) - 90.0D;
    }

    private static BlockPos floor(Vec3 vec) {
        return new BlockPos(Mth.floor(vec.x), Mth.floor(vec.y), Mth.floor(vec.z));
    }

    /** The goal block itself is often occupied by the mob, so fall back to around it. */
    private static BlockPos standableNear(Level level, BlockPos goal) {
        if (BlockNav.canStand(level, goal)) {
            return goal;
        }
        for (int radius = 1; radius <= 3; radius++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    BlockPos candidate = goal.offset(dx, 0, dz);
                    if (!BlockNav.canStand(level, candidate)) {
                        continue;
                    }
                    double distance = candidate.distSqr(goal);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /** Tracks whether we were the one who pressed a key, so a real key is never stolen. */
    private static final class Held {
        private boolean set;
        private boolean owned;

        void apply(KeyMapping key, boolean want, boolean reassert) {
            if (want && (!set || reassert)) {
                if (!set) {
                    owned = !key.isDown();
                }
                key.setDown(true);
                set = true;
            } else if (!want && set) {
                release(key);
            }
        }

        void release(KeyMapping key) {
            if (set && owned) {
                key.setDown(false);
            }
            set = false;
            owned = false;
        }
    }
}
