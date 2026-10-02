package com.leaves.ash.client.mixin;

import com.leaves.ash.client.AshClient;
import com.leaves.ash.client.cloudpick.CloudPickController;
import com.leaves.ash.client.combat.CombatEngine;
import com.leaves.ash.client.config.AshConfig;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Edge triggered hooks into the GLFW key callback, so they fire on the exact frame a key
 * goes down - polling inside the tick would drop short taps, since a tick is 50ms and a
 * quick tap can be shorter than that.
 *
 * Two independent things hang off it: the combat toggle key, and forwarding presses of the
 * keys named in cloudPickKeys to the CloudPick channel.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void ash$onKeyPress(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
        if (action != GLFW.GLFW_PRESS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        AshConfig config = AshClient.CONFIG;
        if (mc == null || config == null || !config.enabled) {
            return;
        }
        // forward physical presses of the configured CloudPick keys; everything inside it is
        // filtered (module off, key not in the list, a screen open) and it only queues
        CloudPickController.INSTANCE.onPhysicalKeyPressed(mc, key);
        if (key != config.keyCode) {
            return;
        }
        CombatEngine.INSTANCE.requestToggle();
    }
}
