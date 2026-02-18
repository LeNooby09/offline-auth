package tech.lenooby09.offlineAuth.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(ServerRecipeBook.class)
public interface RecipeBookAccessor {
	@Accessor("known")
	Set<ResourceKey<Recipe<?>>> getKnown();

	@Accessor("highlight")
	Set<ResourceKey<Recipe<?>>> getHighlight();
}
