package com.leaves.ash.client.mixin;

import com.leaves.ash.client.command.ClientCommands;
import com.leaves.ash.client.combat.CombatEngine;
import com.leaves.ash.client.fish.FishingController;
import com.leaves.ash.client.nav.MovementController;
import com.leaves.ash.client.player.RespawnHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs at the head of the game tick, before Minecraft#handleKeybinds and before the
 * player entity is ticked. That ordering matters for both subsystems:
 *   - rotation written here is picked up by the vanilla position packet of this same
 *     tick, so a swing issued on the next tick is always preceded on the wire by the
 *     rotation that explains it
 *   - movement keys set here are read by LocalPlayer#aiStep -> Input#tick later in the
 *     same tick, which is exactly where a real key press would be read
 */
@Mixin(Minecraft.class)
public class MinecraftTickMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void ash$onTick(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        CombatEngine.INSTANCE.onTick(mc);
        MovementController.INSTANCE.tick(mc);
        FishingController.INSTANCE.tick(mc);
        RespawnHandler.INSTANCE.tick(mc);
        ClientCommands.INSTANCE.tick();
    }
}
