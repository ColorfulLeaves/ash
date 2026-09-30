package com.leaves.ash.client.mixin;

import com.leaves.ash.client.command.ClientCommands;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ChatScreen#handleChatInput routes a message to sendCommand when it starts with / and to
 * sendChat otherwise, so cancelling here catches everything that is not a vanilla command
 * and leaves / commands completely alone. Cancelling means no ServerboundChatPacket is
 * ever built, so the server never sees the text.
 */
@Mixin(ClientPacketListener.class)
public class ChatSendMixin {

    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void ash$onSendChat(String message, CallbackInfo ci) {
        if (message != null && message.startsWith("#")) {
            ClientCommands.INSTANCE.execute(message);
            ci.cancel();
        }
    }
}
