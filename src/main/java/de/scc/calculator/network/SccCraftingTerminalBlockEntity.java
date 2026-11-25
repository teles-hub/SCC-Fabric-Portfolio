package de.scc.calculator.network;

import de.scc.calculator.calc.StackKey;
import de.scc.calculator.registry.SccBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;


/** Persistent 3x3 crafting workspace and component-sensitive refill pattern. */
public final class SccCraftingTerminalBlockEntity extends SccTerminalBlockEntity implements CraftingContainer {
    private final NonNullList<ItemStack> gridItems = NonNullList.withSize(9, ItemStack.EMPTY);
    private final StackKey[] pattern = new StackKey[9];
    private String pickedRecipe = "";
    private long gridVersion;

    public SccCraftingTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(SccBlockEntities.SCC_CRAFTING_TERMINAL.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.scc.crafting_terminal");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new SccCraftingTerminalMenu(containerId, inventory, worldPosition);
    }

    public CraftingContainer grid() { return this; }
    public long gridVersion() { return gridVersion; }

    public StackKey patternAt(int slot) {
        return slot >= 0 && slot < pattern.length ? pattern[slot] : null;
    }

    public void setPattern(int slot, ItemStack stack) {
        if (slot < 0 || slot >= pattern.length) return;
        StackKey next = stack == null || stack.isEmpty() ? null : StackKey.of(stack);
        StackKey current = pattern[slot];
        if (current == null ? next != null : !current.equals(next)) {
            pattern[slot] = next;
            setChanged();
        }
    }

    public boolean hasPattern() {
        for (StackKey key : pattern) if (key != null) return true;
        return false;
    }

    public boolean hasGridItems() {
        for (ItemStack stack : gridItems) if (!stack.isEmpty()) return true;
        return false;
    }

    public String pickedRecipe() { return pickedRecipe; }

    public void setPickedRecipe(String recipeId) {
        String next = recipeId == null ? "" : recipeId;
        if (!pickedRecipe.equals(next)) {
            pickedRecipe = next;
            setChanged();
        }
    }

    /** Drops the persistent grid exactly once when the terminal is removed. */
    public void dropGrid() {
        if (level == null || level.isClientSide) return;
        for (int slot = 0; slot < gridItems.size(); slot++) {
            ItemStack stack = gridItems.get(slot);
            if (!stack.isEmpty()) {
                Block.popResource(level, worldPosition, stack);
                gridItems.set(slot, ItemStack.EMPTY);
            }
        }
        gridVersion++;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompoundTag grid = new CompoundTag();
        ContainerHelper.saveAllItems(grid, gridItems, true, registries);
        tag.put("grid", grid);

        ListTag patterns = new ListTag();
        for (int slot = 0; slot < pattern.length; slot++) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("s", slot);
            entry.putString("i", pattern[slot] == null ? "" : pattern[slot].encode());
            patterns.add(entry);
        }
        tag.put("pattern", patterns);
        if (!pickedRecipe.isEmpty()) tag.putString("pickedRecipe", pickedRecipe);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        gridItems.clear();
        if (tag.contains("grid", CompoundTag.TAG_COMPOUND)) {
            ContainerHelper.loadAllItems(tag.getCompound("grid"), gridItems, registries);
        }
        for (int slot = 0; slot < pattern.length; slot++) pattern[slot] = null;
        ListTag patterns = tag.getList("pattern", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < patterns.size(); index++) {
            CompoundTag entry = patterns.getCompound(index);
            int slot = entry.getInt("s");
            if (slot < 0 || slot >= pattern.length) continue;
            String encoded = entry.getString("i");
            if (!encoded.isEmpty()) pattern[slot] = StackKey.decode(encoded);
        }
        pickedRecipe = tag.getString("pickedRecipe");
        gridVersion++;
    }

    @Override public int getContainerSize() { return gridItems.size(); }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : gridItems) if (!stack.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < gridItems.size() ? gridItems.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= gridItems.size() || amount <= 0) return ItemStack.EMPTY;
        ItemStack result = ContainerHelper.removeItem(gridItems, slot, amount);
        if (!result.isEmpty()) {
            gridVersion++;
            setChanged();
        }
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= gridItems.size()) return ItemStack.EMPTY;
        ItemStack result = gridItems.set(slot, ItemStack.EMPTY);
        if (!result.isEmpty()) {
            gridVersion++;
            setChanged();
        }
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= gridItems.size()) return;
        ItemStack normalized = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack;
        if (!normalized.isEmpty() && normalized.getCount() > getMaxStackSize()) {
            normalized.setCount(getMaxStackSize());
        }
        gridItems.set(slot, normalized);
        gridVersion++;
        setChanged();
    }

    @Override public int getWidth() { return 3; }
    @Override public int getHeight() { return 3; }
    @Override public List<ItemStack> getItems() { return gridItems; }

    @Override
    public void fillStackedContents(StackedContents contents) {
        for (ItemStack stack : gridItems) contents.accountStack(stack);
    }

    @Override public void setChanged() { super.setChanged(); }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && player.distanceToSqr(
                worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
                worldPosition.getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public void clearContent() {
        gridItems.clear();
        gridVersion++;
        setChanged();
    }
}
