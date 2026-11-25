package de.scc.calculator.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import de.scc.calculator.network.SccNetworkBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/** Renders the selected full-block facade over standalone SCC network parts. */
@Environment(EnvType.CLIENT)
public final class SccFacadeRenderer<T extends SccNetworkBlockEntity> implements BlockEntityRenderer<T> {
    private final BlockRenderDispatcher renderer;

    public SccFacadeRenderer(BlockEntityRendererProvider.Context context) {
        this.renderer = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(T entity, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        if (entity.facade() == null) return;
        BlockState appearance = entity.facade().defaultBlockState();
        renderer.renderSingleBlock(appearance, poseStack, buffers, packedLight, packedOverlay);
    }
}
