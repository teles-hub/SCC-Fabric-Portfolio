package de.scc.calculator.network;

import de.scc.calculator.registry.SccBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.block.state.BlockState;

/** Non-combat network terminal node used to open the SCC network overview and storage endpoint. */
public class SccTerminalBlockEntity extends SccNetworkBlockEntity implements MenuProvider {
    private final OrderBook orderBook = new OrderBook();
    private final SccCraftJobQueue craftJobs = new SccCraftJobQueue();
    private final SccOperationJournal operationJournal = new SccOperationJournal(this::setChanged);

    public SccTerminalBlockEntity(BlockPos pos, BlockState state) {
        this(SccBlockEntities.SCC_TERMINAL.get(), pos, state);
    }

    /** Constructor retained for specialized terminal BlockEntities sharing this state model. */
    protected SccTerminalBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public OrderBook orderBook() {
        return orderBook;
    }

    public SccCraftJobQueue craftJobs() {
        return craftJobs;
    }

    public SccOperationJournal operationJournal() {
        return operationJournal;
    }

    /** Server ticker entry point; the scheduler only inspects this already-loaded terminal. */
    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SccTerminalBlockEntity terminal) {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            SccAutocraftingScheduler.tick(serverLevel, pos, terminal);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        orderBook.save(tag);
        craftJobs.save(tag);
        operationJournal.save(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        orderBook.load(tag);
        craftJobs.load(tag);
        operationJournal.load(tag);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.scc.terminal");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new SccTerminalMenu(containerId, inventory, worldPosition);
    }

}
