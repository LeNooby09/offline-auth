package tech.lenooby09.offlineAuth.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tech.lenooby09.offlineAuth.OfflineAuth;
import tech.lenooby09.offlineAuth.auth.AuthAccount;

@Mixin(Player.class)
public abstract class DisplayNameMixin {

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void overrideDisplayName(CallbackInfoReturnable<Component> cir) {
        //noinspection ConstantValue
        if ((Object) this instanceof ServerPlayer self) {
            if (OfflineAuth.Companion.getAuthManager() != null) {
                AuthAccount account = OfflineAuth.Companion.getAuthManager().getAccountMap().get(self.getUUID());
                if (account != null) {
                    Component name = Component.literal(account.getUsername());
                    PlayerTeam team = self.level().getScoreboard().getPlayersTeam(account.getUsername());
                    if (team != null) {
                        name = PlayerTeam.formatNameForTeam(team, name);
                    }
                    cir.setReturnValue(name);
                }
            }
        }
    }
}
