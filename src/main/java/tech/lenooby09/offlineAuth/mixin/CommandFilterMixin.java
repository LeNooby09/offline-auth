package tech.lenooby09.offlineAuth.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tech.lenooby09.offlineAuth.OfflineAuth;
import tech.lenooby09.offlineAuth.auth.AuthManager;
import tech.lenooby09.offlineAuth.auth.AuthMode;

import java.util.Set;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CommandFilterMixin {

    @Shadow
    public abstract ServerPlayer getPlayer();

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void onUnsignedCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        filterCommand(packet.command(), ci);
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void onSignedCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        filterCommand(packet.command(), ci);
    }

    private static final Set<String> AUTH_COMMANDS = Set.of(
            "register", "r", "login", "l", "login_as", "ls", "changepassword", "2fa"
    );

    private static final Set<String> SENSITIVE_COMMANDS = Set.of(
            "login", "l", "login_as", "ls", "register", "r", "changepassword", "2fa"
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

        AuthManager mgr = OfflineAuth.Companion.getAuthManager();
        if (mgr.isAuthenticated(player)) return;

        boolean bluesky = mgr.getAuthMode() == AuthMode.BLUESKY;
        boolean allowed = bluesky
                ? (cmd.equals("bluesky") || cmd.equals("b"))
                : AUTH_COMMANDS.contains(cmd);
        if (allowed) return;

        String hint = bluesky
                ? "§cYou must authenticate first. Use /bluesky"
                : "§cYou must authenticate first. Use /register or /login";
        player.sendSystemMessage(Component.literal(hint));
        ci.cancel();
    }
}
