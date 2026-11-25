package de.scc.calculator.client.renderer;

import de.scc.calculator.network.SccCableBusBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;

/**
 * Renders the real block models of all hosted face parts on the Cable Bus.
 *
 * <p>The part blockstates already encode their face orientation and model
 * geometry is authored against the host block's unit cube, so they are rendered
 * at the host origin without translating the model into a second block space.</p>
 */
@Environment(EnvType.CLIENT)
public final class SccCableBusRenderer implements BlockEntityRenderer<SccCableBusBlockEntity> {
    private final BlockRenderDispatcher blockRenderer;

    public SccCableBusRenderer(BlockEntityRendererProvider.Context context) {
        this.blockRenderer = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(SccCableBusBlockEntity bus, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (bus.facade() != null) {
            blockRenderer.renderSingleBlock(bus.facade().defaultBlockState(), poseStack, buffers, packedLight, packedOverlay);
        }
        for (Direction side : Direction.values()) {
            BlockState partState = bus.partState(side);
            if (partState == null || partState.getRenderShape() != RenderShape.MODEL) continue;
            blockRenderer.renderSingleBlock(partState, poseStack, buffers, packedLight, packedOverlay);
        }
    }
}
