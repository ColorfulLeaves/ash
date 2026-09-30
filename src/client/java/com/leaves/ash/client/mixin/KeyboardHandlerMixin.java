package com.leaves.ash.client.mixin;

import com.leaves.ash.client.AshClient;
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
 * Edge triggered toggle. This is the GLFW key callback, so it fires on the exact frame
 * the key goes down - polling inside the tick would drop short taps, since a tick is
 * 50ms and a quick tap can be shorter than that.
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
        if (key != config.keyCode) {
            return;
        }
        CombatEngine.INSTANCE.requestToggle();
    }
}
