package tech.lenooby09.crackAuth.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tech.lenooby09.crackAuth.CrackAuth;
import tech.lenooby09.crackAuth.auth.AuthAccount;

@Mixin(Entity.class)
public abstract class NameTagMixin {

	@Inject(method = "getName", at = @At("RETURN"), cancellable = true)
	private void overrideNameTag(CallbackInfoReturnable<Component> cir) {
		//noinspection ConstantValue
		if ((Object) this instanceof ServerPlayer self) {
			if (CrackAuth.Companion.getAuthManager() != null) {
				AuthAccount account = CrackAuth.Companion.getAuthManager().getAccountMap().get(self.getUUID());
				if (account != null) {
					cir.setReturnValue(Component.literal(account.getUsername()));
				}
			}
		}
	}
}
