package com.leaves.ash.client.combat;

import com.leaves.ash.client.config.AshConfig;
import com.leaves.ash.client.nav.MovementController;
import com.leaves.ash.client.util.Names;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;

/**
 * One decision per client tick, one swing per decision at most.
 *
 * The whole point of this class is to make the packet stream indistinguishable from a
 * player holding the attack button:
 *   - at most one ServerboundInteractPacket(ATTACK) per tick, never more than ~4/s
 *   - the packet is only sent after the rotation that explains it has already been
 *     flushed by the vanilla position packet of the previous tick
 *   - gaps are millisecond random, never tick aligned, never constant
 *   - targets are validated against reach measured eye-to-hitbox and a real line of
 *     sight raycast, so no swings travel through walls or beyond vanilla reach
 *   - the camera holds still for a moment after every swing instead of following the
 *     target through its own knockback
 *
 * Two camera modes, selected by rotateToTarget:
 *
 *   true  - the crosshair is moved onto the target first, so the swing always has a
 *           rotation that explains it. Costs you the ability to fight while walking,
 *           because this and MovementController both write yaw every tick.
 *   false - the camera is left alone. Nothing here ever writes setYRot/setXRot, so walking
 *           and fighting do not interact at all. The substitute guard is attackFovDeg:
 *           targets behind you are rejected even though they are inside reach, because a
 *           server that does replay your reported view direction would see the hit first.
 */
public final class CombatEngine {

    public static final CombatEngine INSTANCE = new CombatEngine();

    private final Random random = new Random();
    private final AimController aim = new AimController();

    private AshConfig config;

    private Entity target;
    private long targetLockedAt;
    private long reactionUntil;
    private long nextSwingAllowedAt;
    private long aimFrozenUntil;
    private long retargetAfter;

    /** toggle mode: set by the GLFW key callback, consumed on the next tick */
    private boolean active;
    private boolean togglePending;

    /** true only while this engine is inside MultiPlayerGameMode#attack, so the
     *  debug scanner can tell our swings apart from the player's own */
    private boolean engineSwinging;

    private CombatEngine() {
    }

    public void setConfig(AshConfig config) {
        this.config = config;
    }

    public boolean isEngineSwinging() {
        return engineSwinging;
    }

    /** Called from the GLFW key callback. Toggling is edge triggered, not polled. */
    public void requestToggle() {
        togglePending = true;
    }

    /**
     * Full stop: every timer, the locked target and the engaged flag. Used by #reload,
     * because a target acquired under the old reach or cooldown settings should not be
     * carried over into the new ones - and because leaving the engine half-armed after a
     * config change is exactly the kind of surprise that ends up in a ban log.
     */
    public void resetAll() {
        active = false;
        togglePending = false;
        target = null;
        targetLockedAt = 0L;
        reactionUntil = 0L;
        nextSwingAllowedAt = 0L;
        aimFrozenUntil = 0L;
        retargetAfter = 0L;
        aim.reset();
    }

    public boolean isActive() {
        return active;
    }

    /** Called once per game tick from the head of Minecraft#tick. */
    public void onTick(Minecraft mc) {
        if (config == null || !config.enabled) {
            active = false;
            togglePending = false;
            return;
        }

        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.getConnection() == null) {
            release();
            return;
        }

        boolean engaged = resolveEngagement(mc);
        if (!engaged) {
            release();
            return;
        }

        if (mc.screen != null || player.isDeadOrDying() || player.isSpectator() || player.isUsingItem()) {
            release();
            return;
        }

        long now = System.currentTimeMillis();

        if (target != null && !isValid(target, player, mc)) {
            target = null;
            retargetAfter = now + config.retargetDelayMs;
            debug(mc, "target lost");
        }

        if (target != null && shouldSwitch(mc, player, now)) {
            target = null;
            retargetAfter = now + Math.max(40, config.retargetDelayMs / 2);
            return;
        }

        if (target == null) {
            if (now < retargetAfter) {
                return;
            }
            Entity found = pickTarget(mc, player);
            if (found == null) {
                if (config.autoApproach) {
                    Entity destination = pickApproachTarget(mc, player);
                    if (destination != null) {
                        MovementController.INSTANCE.setGoal(destination.position());
                    } else {
                        MovementController.INSTANCE.clearGoal();
                    }
                }
                return;
            }
            target = found;
            targetLockedAt = now;
            reactionUntil = now + config.reactionMinMs
                    + random.nextInt(Math.max(1, config.reactionMaxMs - config.reactionMinMs));
            debug(mc, "locked " + Names.plain(found));
            MovementController.INSTANCE.clearGoal();
        }

        if (config.rotateToTarget && now >= aimFrozenUntil) {
            aim.aimAt(player, target, config, now);
        }

        if (now < reactionUntil) {
            return;
        }
        if (now < nextSwingAllowedAt) {
            return;
        }
        if (!inReach(target, player, config)) {
            target = null;
            retargetAfter = now + config.retargetDelayMs;
            return;
        }
        if (config.requireLineOfSight && !hasLineOfSight(mc, player, target)) {
            return;
        }
        if (config.rotateToTarget) {
            if (!aim.isAligned(player, target, config)) {
                return;
            }
        } else if (!withinViewCone(player, target)) {
            // it walked behind us while we were locked on; drop it instead of swinging backwards
            target = null;
            retargetAfter = now + config.retargetDelayMs;
            return;
        }
        if (config.waitForWeaponCooldown && player.getAttackStrengthScale(0.0F) < config.weaponCooldownThreshold) {
            return;
        }

        swing(mc, player, target);

        long now2 = System.currentTimeMillis();
        int gap = config.attackGapMinMs + random.nextInt(Math.max(1, config.attackGapMaxMs - config.attackGapMinMs));
        int jitter = config.attackGapJitterMs > 0 ? random.nextInt(config.attackGapJitterMs) : 0;
        nextSwingAllowedAt = now2 + gap + jitter;

        if (config.freezeAimAfterAttack) {
            int lo = config.aimFreezeMinMs;
            int hi = Math.max(lo, config.aimFreezeMaxMs);
            aimFrozenUntil = now2 + lo + random.nextInt(Math.max(1, hi - lo));
        }
    }

    private boolean resolveEngagement(Minecraft mc) {
        if (config.toggleMode) {
            if (togglePending) {
                togglePending = false;
                active = !active;
                aim.reset();
                target = null;
                if (active && !config.isTargetingConfigured()) {
                    // shown whether or not debug is on: this misconfiguration is silent otherwise
                    actionBar(mc, "nameFilters is empty - nothing will be attacked");
                } else if (active && !config.rotateToTarget && config.attackFovDeg >= 360.0) {
                    actionBar(mc, "no aim and no FOV limit: targets directly behind you are fair game");
                }
                debug(mc, active ? "engaged" : "idle");
            }
            return active;
        }
        boolean held = InputConstants.isKeyDown(mc.getWindow().getWindow(), config.keyCode);
        active = held;
        return held;
    }

    private void swing(Minecraft mc, LocalPlayer player, Entity target) {
        // identical to what the vanilla client does when the attack key is pressed:
        // MultiPlayerGameMode#attack sends the interact packet, LocalPlayer#swing
        // sends the swing packet itself (never send a second one by hand)
        engineSwinging = true;
        try {
            mc.gameMode.attack(player, target);
        } finally {
            engineSwinging = false;
        }
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void release() {
        MovementController.INSTANCE.clearGoal();
        if (target != null || aimFrozenUntil != 0L) {
            target = null;
            aimFrozenUntil = 0L;
            aim.reset();
        }
    }

    /** Same filters as pickTarget but ignores reach: this is somewhere to walk to. */
    private Entity pickApproachTarget(Minecraft mc, LocalPlayer player) {
        AABB area = player.getBoundingBox().inflate(config.approachMaxDistance);
        List<Entity> candidates = mc.level.getEntities(player, area, e -> true);

        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : candidates) {
            if (!isValid(entity, player, mc)) {
                continue;
            }
            double distance = player.position().distanceToSqr(entity.position());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private Entity pickTarget(Minecraft mc, LocalPlayer player) {
        double reach = Math.min(config.maxReach, AshConfig.VANILLA_MAX_REACH);
        AABB area = player.getBoundingBox().inflate(reach + 0.5);
        List<Entity> candidates = mc.level.getEntities(player, area, e -> true);

        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : candidates) {
            if (!isValid(entity, player, mc)) {
                continue;
            }
            if (!inReach(entity, player, config)) {
                continue;
            }
            if (!config.rotateToTarget && !withinViewCone(player, entity)) {
                continue;
            }
            if (config.requireLineOfSight && !hasLineOfSight(mc, player, entity)) {
                continue;
            }
            double score = player.getEyePosition().distanceTo(entity.getBoundingBox().getCenter());
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    /**
     * A locked target is kept until it stops qualifying, but once the lock window has
     * passed a different mob that is clearly closer takes over - the same thing a
     * player does when something walks into their face.
     */
    private boolean shouldSwitch(Minecraft mc, LocalPlayer player, long now) {
        if (now - targetLockedAt < config.targetLockMs) {
            return false;
        }
        double current = player.getEyePosition().distanceTo(target.getBoundingBox().getCenter());
        Entity closer = pickTarget(mc, player);
        if (closer == null || closer == target) {
            return false;
        }
        double candidate = player.getEyePosition().distanceTo(closer.getBoundingBox().getCenter());
        return candidate < current - 0.75;
    }

    private boolean isValid(Entity entity, LocalPlayer player, Minecraft mc) {
        if (entity == null || entity == player || !entity.isAlive() || entity.isRemoved()) {
            return false;
        }
        if (!(entity instanceof LivingEntity)) {
            return false;
        }
        if (entity.isSpectator() || !entity.isAttackable() || entity.isInvulnerable()) {
            return false;
        }
        if (!config.attackInvisible && entity.isInvisible()) {
            return false;
        }
        if (entity instanceof ArmorStand) {
            return false;
        }
        if (player.getVehicle() == entity || entity.isPassengerOfSameVehicle(player)) {
            return false;
        }
        if (entity.level() != player.level()) {
            return false;
        }
        if (!matchesNameFilter(entity)) {
            return false;
        }
        return true;
    }

    /**
     * The name list is the whole policy, and the matching rule itself lives in AshConfig so
     * that #near reports exactly what the engine does.
     */
    private boolean matchesNameFilter(Entity entity) {
        return config.matchesFilterText(Names.plain(entity),
                String.valueOf(EntityType.getKey(entity.getType())));
    }

    /** Distance from the eye to the closest point of the target hitbox. */
    public static boolean inReach(Entity entity, LocalPlayer player, AshConfig config) {
        double reach = Math.min(config.maxReach, AshConfig.VANILLA_MAX_REACH);
        Vec3 eye = player.getEyePosition();
        AABB box = entity.getBoundingBox();
        double dx = Math.max(box.minX - eye.x, Math.max(0.0, eye.x - box.maxX));
        double dy = Math.max(box.minY - eye.y, Math.max(0.0, eye.y - box.maxY));
        double dz = Math.max(box.minZ - eye.z, Math.max(0.0, eye.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= reach;
    }

    /** Raycast from the eye to the target centre; anything solid in between blocks the swing. */
    public static boolean hasLineOfSight(Minecraft mc, LocalPlayer player, Entity entity) {
        Vec3 eye = player.getEyePosition();
        Vec3 aimPoint = entity.getBoundingBox().getCenter();
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, aimPoint, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        // a block counts as an obstacle only when it is closer than the target itself
        return hit.getLocation().distanceTo(eye) >= eye.distanceTo(aimPoint) - 0.1;
    }

    private boolean withinViewCone(LocalPlayer player, Entity entity) {
        if (config.attackFovDeg >= 360.0) {
            return true;
        }
        return angleOffCenter(player, entity) <= config.attackFovDeg * 0.5;
    }

    /**
     * Angle between the direction the player is genuinely looking and the direction to the
     * target's upper body. This uses the rotation vanilla already put on the wire - it never
     * writes one, so it stays correct even while MovementController owns the camera.
     */
    public static double angleOffCenter(LocalPlayer player, Entity entity) {
        Vec3 eye = player.getEyePosition();
        AABB box = entity.getBoundingBox();
        Vec3 toTarget = new Vec3((box.minX + box.maxX) * 0.5,
                box.minY + (box.maxY - box.minY) * 0.6,
                (box.minZ + box.maxZ) * 0.5).subtract(eye);
        if (toTarget.lengthSqr() < 1.0E-6D) {
            return 0.0;
        }
        double cos = player.getViewVector(1.0F).dot(toTarget.normalize());
        return Math.toDegrees(Math.acos(Mth.clamp(cos, -1.0D, 1.0D)));
    }

    private void debug(Minecraft mc, String message) {
        if (config.debug && mc.player != null) {
            actionBar(mc, message);
        }
    }

    /**
     * Local display only. LocalPlayer#displayClientMessage routes to the client's own
     * ChatListener and issues no packet, so the server never sees any of this.
     */
    private void actionBar(Minecraft mc, String message) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[ash] " + message), true);
        }
    }
}
