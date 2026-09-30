package com.leaves.ash.client.mixin;

import com.leaves.ash.client.config.AshConfig;
import com.leaves.ash.client.nav.MovementController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches for the server telling the client where it is.
 *
 * This packet is only sent when somebody teleports us: /tp, a plugin, a respawn anchor, or
 * an anti-cheat that decided our reported motion was impossible and pulled us back. All
 * four cases have the same correct response - stop driving immediately.
 *
 * The alternative is much worse. If we keep pressing forward after being teleported we are
 * arguing with the server about our own position, and that argument is exactly what the
 * movement checks are looking for.
 *
 * The packet itself is never cancelled: vanilla has to apply it or the desync gets worse.
 */
@Mixin(ClientPacketListener.class)
public class PositionResetMixin {

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void ash$onServerTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        AshConfig config = com.leaves.ash.client.AshClient.CONFIG;
        if (mc == null || config == null || !config.stopOnServerTeleport) {
            return;
        }
        // a direct dependency would loop back through the command dispatcher
        MovementController.INSTANCE.emergencyStop(mc, "server reset our position");
    }
}
