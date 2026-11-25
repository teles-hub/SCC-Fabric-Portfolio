package de.scc.calculator.network;

import de.scc.calculator.SccLog;
import de.scc.calculator.calc.StackKey;
import de.scc.calculator.registry.SccBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Server-authoritative stock threshold and redstone output for the SCC network. */
public final class SccRedstoneEmitterBlockEntity extends SccNetworkBlockEntity {
    public static final int MODE_AT_LEAST = 0;
    public static final int MODE_AT_MOST = 1;
    private static final int INTERVAL = 10;
    @Nullable private StackKey item;
    private long amount = 64L;
    private int mode;
    private long have;
    private boolean lit;
    private int cooldown;
    private int quiet;

    public SccRedstoneEmitterBlockEntity(BlockPos pos, BlockState state) {
        super(SccBlockEntities.SCC_REDSTONE_EMITTER.get(), pos, state);
    }

    @Override public boolean canConnect(Direction face) {
        return face != null && getBlockState().hasProperty(SccRedstoneEmitterBlock.propertyFor(face))
                && getBlockState().getValue(SccRedstoneEmitterBlock.propertyFor(face));
    }

    @Nullable public StackKey item() { return item; }
    public long amount() { return amount; }
    public int mode() { return mode; }
    public long have() { return have; }
    public boolean lit() { return lit; }

    public void setItem(@Nullable StackKey value) {
        item = value;
        cooldown = 0;
        sync();
    }

    public void setAmount(long value) {
        amount = Math.max(0L, Math.min(999_999L, value));
        cooldown = 0;
        sync();
    }

    public void setMode(int value) {
        mode = value == MODE_AT_MOST ? MODE_AT_MOST : MODE_AT_LEAST;
        cooldown = 0;
        sync();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SccRedstoneEmitterBlockEntity emitter) {
        if (emitter.cooldown > 0) {
            emitter.cooldown--;
            return;
        }
        emitter.cooldown = INTERVAL;
        long count = 0L;
        if (emitter.item != null) {
            try {
                count = SccNetworkCache.view(level, pos).count(emitter.item);
            } catch (RuntimeException exception) {
                SccLog.caught("emitter", "stock could not be read at " + pos, exception);
                return;
            }
        }
        boolean nextLit = emitter.item != null
                && (emitter.mode == MODE_AT_LEAST ? count >= emitter.amount : count <= emitter.amount);
        boolean changed = count != emitter.have;
        emitter.have = count;
        if (nextLit != emitter.lit) {
            emitter.lit = nextLit;
            emitter.apply(level, pos, state);
            emitter.quiet = 0;
            changed = true;
        }
        if (emitter.quiet > 0) emitter.quiet--;
        if (changed && emitter.quiet <= 0) {
            emitter.quiet = 4;
            emitter.sync();
        } else if (changed) {
            emitter.setChanged();
        }
    }

    private void apply(Level level, BlockPos pos, BlockState state) {
        if (state.hasProperty(SccRedstoneEmitterBlock.LIT)) {
            BlockState next = state.setValue(SccRedstoneEmitterBlock.LIT, lit);
            if (sccHost() != null && sccHostSide() != null) {
                sccHost().updatePartState(sccHostSide(), next);
            } else {
                level.setBlock(pos, next, 3);
            }
        }
        BlockState current = level.getBlockState(pos);
        level.updateNeighborsAt(pos, current.getBlock());
        for (Direction direction : Direction.values()) {
            level.updateNeighborsAt(pos.relative(direction), current.getBlock());
        }
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (item != null) tag.putString("item", item.encode());
        tag.putLong("amount", amount);
        tag.putInt("mode", mode);
        tag.putLong("have", have);
        tag.putBoolean("lit", lit);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        item = tag.contains("item") ? StackKey.decode(tag.getString("item")) : null;
        amount = tag.contains("amount") ? Math.max(0L, Math.min(999_999L, tag.getLong("amount"))) : 64L;
        mode = tag.getInt("mode") == MODE_AT_MOST ? MODE_AT_MOST : MODE_AT_LEAST;
        have = Math.max(0L, tag.getLong("have"));
        lit = tag.getBoolean("lit");
        cooldown = 0;
    }
}
