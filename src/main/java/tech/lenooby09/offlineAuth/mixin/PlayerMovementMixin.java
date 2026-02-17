package tech.lenooby09.offlineAuth.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tech.lenooby09.offlineAuth.OfflineAuth;

@Mixin(ServerPlayer.class)
public abstract class PlayerMovementMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (OfflineAuth.Companion.getAuthManager() != null) {
            if (!OfflineAuth.Companion.getAuthManager().isAuthenticated(self)) {
                OfflineAuth.Companion.getAuthManager().freezePlayer(self);
            }
        }
    }
}
