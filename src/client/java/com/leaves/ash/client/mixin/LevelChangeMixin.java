package com.leaves.ash.client.mixin;

import com.leaves.ash.client.cloudpick.CloudPickController;
import com.leaves.ash.client.fish.FishingController;
import com.leaves.ash.client.nav.MovementController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops all movement state when the world is swapped.
 *
 * This used to reload the properties file too; that is gone. Config is read at startup and
 * on #reload, nowhere else. What remains is the part that is not optional: a goal and an A*
 * path computed in the previous world are meaningless in the new one, and replaying them
 * would drive the player into blocks that may not even exist here.
 *
 * The CloudPick handshake is dropped here for the same reason: it belongs to a connection
 * and to a world, so the new one has to declare itself again from scratch.
 */
@Mixin(Minecraft.class)
public class LevelChangeMixin {

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void ash$onSetLevel(ClientLevel level, CallbackInfo ci) {
        MovementController.INSTANCE.hardReset();
        FishingController.INSTANCE.hardReset();
        CloudPickController.INSTANCE.hardReset();
    }

    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"))
    private void ash$onClearLevel(Screen screen, CallbackInfo ci) {
        MovementController.INSTANCE.hardReset();
        FishingController.INSTANCE.hardReset();
        CloudPickController.INSTANCE.hardReset();
    }
}
