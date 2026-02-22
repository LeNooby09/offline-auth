package tech.lenooby09.offlineAuth.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import tech.lenooby09.offlineAuth.OfflineAuth;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class LeaveMessageMixin {

	@Shadow
	private ServerPlayer player;

	@Redirect(
			method = "removePlayerFromWorld",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"
			)
	)
	private void offlineAuth$suppressLeaveMessage(PlayerList instance, Component message, boolean overlay) {
		if (OfflineAuth.Companion.getAuthManager() != null) {
			// Always suppress the vanilla leave message — we broadcast our own
			// leave message in onPlayerDisconnect / prepareAccountSwitch with
			// the correct account display name and team formatting.
			return;
		}
		instance.broadcastSystemMessage(message, overlay);
	}
}
