package com.leaves.ash.client.combat;

import com.leaves.ash.client.config.AshConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Turns the player toward a target the way a hand on a mouse does: bounded angular
 * speed per tick, shortest-path yaw, plus a slow drifting tremor so the aim never
 * sits perfectly still.
 */
public final class AimController {

    private final Random random = new Random();

    private double jitterYaw;
    private double jitterPitch;
    private long jitterValidUntil;

    /** Rotate one step toward the target. Called once per tick. */
    public void aimAt(LocalPlayer player, Entity target, AshConfig config, long now) {
        refreshJitter(config, now);

        double[] desired = desiredAngles(player, target, jitterYaw, jitterPitch);
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        double deltaYaw = Mth.wrapDegrees(desired[0] - yaw);
        double deltaPitch = desired[1] - pitch;

        double stepYaw = Mth.clamp(deltaYaw, -config.maxYawStepDeg, config.maxYawStepDeg);
        double stepPitch = Mth.clamp(deltaPitch, -config.maxPitchStepDeg, config.maxPitchStepDeg);

        player.setYRot(Mth.wrapDegrees(yaw + (float) stepYaw));
        player.setXRot(Mth.clamp(pitch + (float) stepPitch, -90.0F, 90.0F));
    }

    /** True once the crosshair is inside the tolerance cone around the target. */
    public boolean isAligned(LocalPlayer player, Entity target, AshConfig config) {
        double[] desired = desiredAngles(player, target, jitterYaw, jitterPitch);
        double deltaYaw = Math.abs(Mth.wrapDegrees(desired[0] - player.getYRot()));
        double deltaPitch = Math.abs(desired[1] - player.getXRot());
        return deltaYaw <= config.aimToleranceDeg && deltaPitch <= config.aimToleranceDeg;
    }

    public void reset() {
        jitterYaw = 0.0;
        jitterPitch = 0.0;
        jitterValidUntil = 0L;
    }

    private void refreshJitter(AshConfig config, long now) {
        if (now < jitterValidUntil) {
            return;
        }
        double amp = config.aimJitterDeg;
        jitterYaw = (random.nextDouble() * 2.0 - 1.0) * amp;
        jitterPitch = (random.nextDouble() * 2.0 - 1.0) * amp;
        jitterValidUntil = now + 400L + random.nextInt(500);
    }

    /** Yaw/pitch that would put the crosshair on the upper body of the target. */
    public static double[] desiredAngles(LocalPlayer player, Entity target, double jitterYaw, double jitterPitch) {
        Vec3 eye = player.getEyePosition();
        AABB box = target.getBoundingBox();
        double tx = (box.minX + box.maxX) * 0.5;
        double ty = (box.minY + box.maxY) * 0.5 + (box.maxY - box.minY) * 0.1;
        double tz = (box.minZ + box.maxZ) * 0.5;

        double dx = tx - eye.x;
        double dy = ty - eye.y;
        double dz = tz - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double pitch = -Math.toDegrees(Math.atan2(dy, horizontal));
        return new double[]{yaw + jitterYaw, Mth.clamp(pitch + jitterPitch, -90.0, 90.0)};
    }
}
