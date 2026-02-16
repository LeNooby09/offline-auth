package tech.lenooby09.crackAuth.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tech.lenooby09.crackAuth.CrackAuth;

@Mixin(ServerPlayer.class)
public abstract class PlayerMovementMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (CrackAuth.Companion.getAuthManager() != null) {
            if (!CrackAuth.Companion.getAuthManager().isAuthenticated(self)) {
                CrackAuth.Companion.getAuthManager().freezePlayer(self);
            }
        }
    }
}
