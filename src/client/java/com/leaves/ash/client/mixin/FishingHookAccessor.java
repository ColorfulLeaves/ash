package com.leaves.ash.client.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * FishingHook#DATA_BITING is private and has no public reader, but it is the only value
 * that actually carries "a fish took the hook" from the server:
 *
 *   FishingHook#catchingFish      (server) writes DATA_BITING
 *   FishingHook#onSyncedDataUpdated (client) mirrors it into the private field "biting"
 *   FishingHook#tick              (client) drives the splash particles off that field
 *
 * So the synched data is the ground truth on both sides, and reading it directly is both
 * simpler and more reliable than inferring a bite from the bobber's motion.
 */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {

    @Accessor("DATA_BITING")
    static EntityDataAccessor<Boolean> ash$dataBiting() {
        throw new AssertionError();
    }
}
