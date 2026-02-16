package tech.lenooby09.crackAuth.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tech.lenooby09.crackAuth.CrackAuth;
import tech.lenooby09.crackAuth.auth.AuthAccount;

@Mixin(Player.class)
public abstract class ScoreboardNameMixin {

	@Inject(method = "getScoreboardName", at = @At("RETURN"), cancellable = true)
	private void overrideScoreboardName(CallbackInfoReturnable<String> cir) {
		//noinspection ConstantValue
		if ((Object) this instanceof ServerPlayer self) {
			if (CrackAuth.Companion.getAuthManager() != null) {
				AuthAccount account = CrackAuth.Companion.getAuthManager().getAccountMap().get(self.getUUID());
				if (account != null) {
					cir.setReturnValue(account.getUsername());
				}
			}
		}
	}
}
