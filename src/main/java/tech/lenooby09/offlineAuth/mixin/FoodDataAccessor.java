package tech.lenooby09.offlineAuth.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FoodData.class)
public interface FoodDataAccessor {
	@Accessor("exhaustionLevel")
	float getExhaustionLevel();

	@Accessor("exhaustionLevel")
	void setExhaustionLevel(float value);

	@Accessor("tickTimer")
	int getTickTimer();

	@Accessor("tickTimer")
	void setTickTimer(int value);
}
