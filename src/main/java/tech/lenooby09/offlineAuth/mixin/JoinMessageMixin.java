package tech.lenooby09.offlineAuth.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import tech.lenooby09.offlineAuth.OfflineAuth;

@Mixin(PlayerList.class)
public abstract class JoinMessageMixin {

    @Redirect(
            method = "placeNewPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"
            )
    )
    private void offlineAuth$suppressJoinMessage(PlayerList instance, Component message, boolean overlay) {
        if (OfflineAuth.Companion.getConfig().getHideJoinMessageUntilLogin()
                && OfflineAuth.Companion.getAuthManager() != null) {
            // Suppress the join message — it will be sent after authentication
            return;
        }
        instance.broadcastSystemMessage(message, overlay);
    }
}
