package tech.lenooby09.crackAuth.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tech.lenooby09.crackAuth.CrackAuth;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CommandFilterMixin {

    @Shadow
    public abstract ServerPlayer getPlayer();

    @Inject(method = "performUnsignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void onUnsignedCommand(String command, CallbackInfo ci) {
        filterCommand(command, ci);
    }

    @Inject(method = "performSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void onSignedCommand(ServerboundChatCommandSignedPacket packet, LastSeenMessages lastSeenMessages, CallbackInfo ci) {
        filterCommand(packet.command(), ci);
    }

    private void filterCommand(String command, CallbackInfo ci) {
        if (CrackAuth.Companion.getAuthManager() == null) return;

        ServerPlayer player = getPlayer();
        if (CrackAuth.Companion.getAuthManager().isAuthenticated(player)) return;

        String cmd = command.toLowerCase().split(" ")[0];
        if (cmd.equals("register") || cmd.equals("login") || cmd.equals("login_as")) {
            return;
        }

        player.sendSystemMessage(Component.literal("§cYou must authenticate first. Use /register or /login"));
        ci.cancel();
    }
}
