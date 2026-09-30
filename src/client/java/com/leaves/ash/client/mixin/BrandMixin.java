package com.leaves.ash.client.mixin;

import com.leaves.ash.client.AshClient;
import net.minecraft.client.ClientBrandRetriever;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric Loader patches ClientBrandRetriever#getClientModName and reports "fabric" on
 * the minecraft:brand channel. Reporting "vanilla" instead keeps the client looking
 * like a stock client to anything that reads the brand. Toggle with
 * restoreVanillaBrand in config/ash-client.properties.
 */
@Mixin(ClientBrandRetriever.class)
public class BrandMixin {

    @Inject(method = "getClientModName", at = @At("HEAD"), cancellable = true)
    private static void ash$vanillaBrand(CallbackInfoReturnable<String> cir) {
        if (AshClient.CONFIG != null && AshClient.CONFIG.restoreVanillaBrand) {
            cir.setReturnValue("vanilla");
        }
    }
}
