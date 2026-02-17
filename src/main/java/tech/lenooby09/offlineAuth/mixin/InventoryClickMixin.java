package tech.lenooby09.offlineAuth.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tech.lenooby09.offlineAuth.OfflineAuth;

/**
 * Prevents unauthenticated players from clicking in inventory screens.
 * This blocks item movement, crafting, and any container manipulation
 * while a player is in the unauthenticated state.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class InventoryClickMixin {

    @Shadow
    public abstract ServerPlayer getPlayer();

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void onContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        ServerPlayer player = getPlayer();
        if (OfflineAuth.Companion.getAuthManager() != null
                && !OfflineAuth.Companion.getAuthManager().isAuthenticated(player)) {
            ci.cancel();
        }
    }
}
