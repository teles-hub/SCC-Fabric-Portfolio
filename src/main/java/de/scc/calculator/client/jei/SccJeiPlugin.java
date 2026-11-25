package de.scc.calculator.client.jei;

import de.scc.calculator.api.SccRecipeProviders;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.constants.RecipeTypes;
import net.minecraft.resources.ResourceLocation;

/**
 * Optional JEI bridge. This class is only loaded by JEI when JEI is installed;
 * the SCC common/runtime path has no hard dependency on JEI.
 */
@JeiPlugin
public final class SccJeiPlugin implements IModPlugin {
    private static final SccJeiRecipeProvider PROVIDER = new SccJeiRecipeProvider();

    public SccJeiPlugin() {
        SccRecipeProviders.register(ResourceLocation.fromNamespaceAndPath("scc", "jei_recipes"), PROVIDER);
    }

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("scc", "jei_plugin");
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(
                new SccCraftingTerminalTransferHandler(), RecipeTypes.CRAFTING);
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        PROVIDER.setRuntime(runtime);
        SccRecipeProviders.changed();
    }

    @Override
    public void onRuntimeUnavailable() {
        PROVIDER.setRuntime(null);
        SccRecipeProviders.changed();
    }
}
