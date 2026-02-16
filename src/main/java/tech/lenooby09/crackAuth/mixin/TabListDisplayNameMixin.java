package tech.lenooby09.crackAuth.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tech.lenooby09.crackAuth.CrackAuth;
import tech.lenooby09.crackAuth.auth.AuthAccount;

@Mixin(ServerPlayer.class)
public abstract class TabListDisplayNameMixin {

	@Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
	private void overrideTabListDisplayName(CallbackInfoReturnable<Component> cir) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (CrackAuth.Companion.getAuthManager() != null) {
			AuthAccount account = CrackAuth.Companion.getAuthManager().getAccountMap().get(self.getUUID());
			if (account != null) {
				cir.setReturnValue(Component.literal(account.getUsername()));
			}
		}
	}
}
