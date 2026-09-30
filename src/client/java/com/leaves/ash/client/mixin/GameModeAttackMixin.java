package com.leaves.ash.client.mixin;

import com.leaves.ash.client.debug.EntityScanner;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every attack the local player resolves goes through here, including the ones this mod
 * issues. The scanner filters those out itself, so the log only ever contains entities
 * the player actually aimed at and hit by hand.
 */
@Mixin(MultiPlayerGameMode.class)
public class GameModeAttackMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void ash$onAttack(Player player, Entity target, CallbackInfo ci) {
        EntityScanner.INSTANCE.onManualAttack(player, target);
    }
}
