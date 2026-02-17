package tech.lenooby09.offlineAuth.mixin;

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
import tech.lenooby09.offlineAuth.OfflineAuth;

import java.util.Set;

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

    private static final Set<String> AUTH_COMMANDS = Set.of(
            "register", "r", "login", "l", "login_as", "ls", "changepassword"
    );

    private static final Set<String> SENSITIVE_COMMANDS = Set.of(
            "login", "l", "login_as", "ls", "register", "r", "changepassword"
    );

    private void filterCommand(String command, CallbackInfo ci) {
        if (OfflineAuth.Companion.getAuthManager() == null) return;

        ServerPlayer player = getPlayer();

        // Suppress server-side logging of commands containing passwords
        String cmd = command.toLowerCase().split(" ")[0];
        if (SENSITIVE_COMMANDS.contains(cmd)) {
            // Cancel the default handling and execute the command manually
            // This prevents the command from being logged in server logs with arguments
            ci.cancel();
            var sourceStack = player.createCommandSourceStack();
            sourceStack.getServer().getCommands().performPrefixedCommand(sourceStack, command);
            return;
        }

        if (OfflineAuth.Companion.getAuthManager().isAuthenticated(player)) return;

        if (AUTH_COMMANDS.contains(cmd)) {
            return;
        }

        player.sendSystemMessage(Component.literal("§cYou must authenticate first. Use /register or /login"));
        ci.cancel();
    }
}
