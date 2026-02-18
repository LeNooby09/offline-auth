package tech.lenooby09.offlineAuth.mixin;

import com.google.gson.JsonElement;
import net.minecraft.stats.ServerStatsCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerStatsCounter.class)
public interface ServerStatsCounterInvoker {
	@Invoker("toJson")
	JsonElement invokeToJson();
}
