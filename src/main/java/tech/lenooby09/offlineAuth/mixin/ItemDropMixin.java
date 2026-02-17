package tech.lenooby09.offlineAuth.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tech.lenooby09.offlineAuth.OfflineAuth;

/**
 * Prevents unauthenticated players from dropping items.
 * This closes the window between player join (with vanilla inventory) and inventory clear,
 * as well as any edge cases during auth state transitions.
 */
@Mixin(ServerPlayer.class)
public abstract class ItemDropMixin {

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"), cancellable = true)
    private void onDrop(ItemStack itemStack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (OfflineAuth.Companion.getAuthManager() != null
                && !OfflineAuth.Companion.getAuthManager().isAuthenticated(self)) {
            cir.setReturnValue(null);
        }
    }
}
