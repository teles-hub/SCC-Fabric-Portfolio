package de.scc.calculator.network;

import de.scc.calculator.calc.StackKey;
import de.scc.calculator.registry.SccMenus;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Server-authoritative 3x3 crafting terminal.
 *
 * <p>Normal crafting uses Minecraft's RecipeManager and ResultSlot semantics,
 * including returned container items. The persistent pattern is synchronized
 * through hidden menu slots, while the visible grid uses the normal incremental
 * container-slot protocol. Machine autocrafting is deliberately not invoked.</p>
 */
public final class SccCraftingTerminalMenu extends AbstractContainerMenu {
    public static final int ACTION_REFILL = 2_000_000;
    public static final int ACTION_CLEAR = 2_000_001;
    public static final int ACTION_PICK_RECIPE = 2_000_002;

    private final BlockPos pos;
    private final ResourceKey<Level> remoteDimension;
    private final boolean wireless;
    private final Player player;
    private final CraftingContainer grid;
    private final ResultContainer result = new ResultContainer();
    private final SimpleContainer patternSync = new SimpleContainer(9);
    private final ItemStack[] pattern = new ItemStack[9];
    private boolean refilling;
    private boolean pendingRefill = true;
    private int refillCooldown = 20;
    private long seenGridVersion = Long.MIN_VALUE;

    public SccCraftingTerminalMenu(int containerId, Inventory playerInventory, TerminalMenuOpenData openingData) {
        super(SccMenus.CRAFTING_TERMINAL.get(), containerId);
        this.pos = openingData.pos();
        this.remoteDimension = openingData.dimension();
        this.wireless = openingData.wireless();
        this.player = playerInventory.player;

        Level resolvedLevel = networkLevel();
        BlockEntity resolved = resolvedLevel == null ? null : resolvedLevel.getBlockEntity(pos);
        if (resolved instanceof SccCraftingTerminalBlockEntity terminal) {
            this.grid = terminal.grid();
            this.seenGridVersion = terminal.gridVersion();
        } else {
            this.grid = new TransientCraftingContainer(this, 3, 3);
        }
        for (int slot = 0; slot < pattern.length; slot++) pattern[slot] = ItemStack.EMPTY;
        loadPattern();

        addSlot(new NetworkResultSlot(player, grid, result, 0, 116, 35));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                addSlot(new Slot(grid, column + row * 3,
                        26 + column * 18, 17 + row * 18));
            }
        }
        addPlayerInventory(playerInventory);

        for (int slot = 0; slot < 9; slot++) {
            final int patternSlot = slot;
            addSlot(new Slot(patternSync, slot, -10_000, -10_000) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return false; }
            });
            syncPatternSlot(patternSlot);
        }
    }

    /** Local-menu compatibility constructor retained for block interaction and existing tests. */
    public SccCraftingTerminalMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        this(containerId, playerInventory,
                new TerminalMenuOpenData(pos, playerInventory.player.level().dimension(), false, true));
    }

    @org.jetbrains.annotations.Nullable
    private Level networkLevel() {
        if (wireless && player.level().isClientSide) return null;
        if (wireless && !player.level().isClientSide
                && player instanceof ServerPlayer serverPlayer && serverPlayer.getServer() != null) {
            return serverPlayer.getServer().getLevel(remoteDimension);
        }
        return player.level();
    }

    private Level dataLevel() {
        Level level = networkLevel();
        return level == null ? player.level() : level;
    }

    public BlockPos pos() { return pos; }
    public boolean wireless() { return wireless; }
    public boolean wirelessCrafting() { return true; }
    public ResourceKey<Level> remoteDimension() { return remoteDimension; }

    public ItemStack patternItem(int slot) {
        return slot >= 0 && slot < 9 ? patternSync.getItem(slot) : ItemStack.EMPTY;
    }

    private SccCraftingTerminalBlockEntity crafting() {
        Level level = networkLevel();
        return level != null && level.getBlockEntity(pos) instanceof SccCraftingTerminalBlockEntity terminal
                ? terminal : null;
    }

    private SccNetworkView network() {
        Level level = networkLevel();
        return level == null ? SccNetworkView.empty(player.level(), pos) : SccNetworkCache.view(level, pos);
    }

    private void syncPatternSlot(int slot) {
        if (slot < 0 || slot >= 9) return;
        patternSync.setItem(slot, pattern[slot].isEmpty()
                ? ItemStack.EMPTY : pattern[slot].copyWithCount(1));
    }

    private void setPatternSlot(int slot, ItemStack stack) {
        if (slot < 0 || slot >= 9) return;
        ItemStack next = stack == null || stack.isEmpty()
                ? ItemStack.EMPTY : stack.copyWithCount(1);
        pattern[slot] = next;
        syncPatternSlot(slot);
        SccCraftingTerminalBlockEntity terminal = crafting();
        if (terminal != null) terminal.setPattern(slot, next);
    }

    private void loadPattern() {
        SccCraftingTerminalBlockEntity terminal = crafting();
        if (terminal == null) return;
        for (int slot = 0; slot < pattern.length; slot++) {
            StackKey key = terminal.patternAt(slot);
            pattern[slot] = key == null ? ItemStack.EMPTY : key.stack(1);
            syncPatternSlot(slot);
        }
    }

    @Override
    public void broadcastChanges() {
        if (!player.level().isClientSide) {
            if (pendingRefill) {
                pendingRefill = false;
                refillFromNetwork();
            }
            SccCraftingTerminalBlockEntity terminal = crafting();
            if (terminal != null && terminal.gridVersion() != seenGridVersion) {
                seenGridVersion = terminal.gridVersion();
                updateResult();
            }
            if (!refilling && --refillCooldown <= 0) {
                refillCooldown = 20;
                if (needsRefill()) refillFromNetwork();
            }
        }
        super.broadcastChanges();
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (container == grid) {
            rememberPattern();
            updateResult();
        }
    }

    private void rememberPattern() {
        if (refilling || player.level().isClientSide) return;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = grid.getItem(slot);
            pattern[slot] = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
            syncPatternSlot(slot);
            SccCraftingTerminalBlockEntity terminal = crafting();
            if (terminal != null) terminal.setPattern(slot, stack);
        }
    }

    private CraftingInput input() {
        List<ItemStack> items = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) items.add(grid.getItem(slot));
        return CraftingInput.of(3, 3, items);
    }

    private List<RecipeHolder<CraftingRecipe>> matchingRecipes(CraftingInput craftingInput) {
        List<RecipeHolder<CraftingRecipe>> matches = new ArrayList<>();
        List<ItemStack> distinctOutputs = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : dataLevel().getRecipeManager()
                .getRecipesFor(RecipeType.CRAFTING, craftingInput, dataLevel())) {
            ItemStack output = holder.value().assemble(craftingInput, player.level().registryAccess());
            if (output.isEmpty()) continue;
            boolean duplicateOutput = false;
            for (ItemStack existing : distinctOutputs) {
                if (ItemStack.isSameItemSameComponents(existing, output)
                        && existing.getCount() == output.getCount()) {
                    duplicateOutput = true;
                    break;
                }
            }
            if (!duplicateOutput) {
                matches.add(holder);
                distinctOutputs.add(output);
                if (matches.size() >= 64) break;
            }
        }
        return matches;
    }

    private void updateResult() {
        if (!(player instanceof ServerPlayer) || player.level().isClientSide) return;
        CraftingInput craftingInput = input();
        Optional<RecipeHolder<CraftingRecipe>> vanillaMatch =
                dataLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, craftingInput, dataLevel());
        List<RecipeHolder<CraftingRecipe>> matches = matchingRecipes(craftingInput);
        if (matches.isEmpty()) {
            result.setItem(0, ItemStack.EMPTY);
            return;
        }

        String picked = crafting() == null ? "" : crafting().pickedRecipe();
        RecipeHolder<CraftingRecipe> selected = null;
        if (!picked.isBlank()) {
            for (RecipeHolder<CraftingRecipe> candidate : matches) {
                if (candidate.id().toString().equals(picked)) {
                    selected = candidate;
                    break;
                }
            }
        }
        if (selected == null && vanillaMatch.isPresent()) {
            for (RecipeHolder<CraftingRecipe> candidate : matches) {
                if (candidate.id().equals(vanillaMatch.get().id())) {
                    selected = candidate;
                    break;
                }
            }
        }
        if (selected == null) selected = matches.getFirst();
        result.setItem(0, selected.value().assemble(craftingInput, dataLevel().registryAccess()));
    }

    /** Cycles the output-distinct matching recipes; the selected recipe id is persisted by the BE. */
    private void pickNextRecipe() {
        List<RecipeHolder<CraftingRecipe>> matches = matchingRecipes(input());
        if (matches.size() < 2) return;

        SccCraftingTerminalBlockEntity terminal = crafting();
        String current = terminal == null ? "" : terminal.pickedRecipe();
        int selected = -1;
        for (int index = 0; index < matches.size(); index++) {
            if (matches.get(index).id().toString().equals(current)) {
                selected = index;
                break;
            }
        }
        RecipeHolder<CraftingRecipe> next = matches.get((selected + 1) % matches.size());
        if (terminal != null) terminal.setPickedRecipe(next.id().toString());
        updateResult();
        super.broadcastChanges();
    }

    private boolean needsRefill() {
        for (int slot = 0; slot < 9; slot++) {
            if (grid.getItem(slot).isEmpty() && !pattern[slot].isEmpty()) return true;
        }
        return false;
    }

    private ItemStack takeFromPlayer(StackKey key) {
        if (key == null) return ItemStack.EMPTY;
        Inventory inventory = player.getInventory();
        for (int slot = inventory.items.size() - 1; slot >= 0; slot--) {
            ItemStack held = inventory.items.get(slot);
            if (held.isEmpty() || !key.matches(held)) continue;
            ItemStack taken = held.copyWithCount(1);
            held.shrink(1);
            if (held.isEmpty()) inventory.items.set(slot, ItemStack.EMPTY);
            inventory.setChanged();
            return taken;
        }
        return ItemStack.EMPTY;
    }

    /** Fills only empty grid slots and leaves component-sensitive pattern entries intact. */
    public void refillFromNetwork() {
        if (player.level().isClientSide || refilling || !needsRefill()) return;
        SccNetworkView view = network();
        if (view.truncated()) return;
        refilling = true;
        try {
            for (int slot = 0; slot < 9; slot++) {
                if (!grid.getItem(slot).isEmpty() || pattern[slot].isEmpty()) continue;
                ItemStack exemplar = pattern[slot];
                StackKey key = StackKey.of(exemplar);
                ItemStack extracted = view.extract(key, exemplar, 1, false);
                if (extracted.isEmpty()) extracted = takeFromPlayer(key);
                if (!extracted.isEmpty()) grid.setItem(slot, extracted);
            }
        } finally {
            refilling = false;
        }
        SccCraftingTerminalBlockEntity terminal = crafting();
        if (terminal != null) {
            terminal.setChanged();
            seenGridVersion = terminal.gridVersion();
        }
        updateResult();
    }

    private void returnGridToNetwork(boolean clearPattern) {
        if (player.level().isClientSide) return;
        SccNetworkView view = network();
        if (view.truncated()) return;
        refilling = true;
        try {
            for (int slot = 0; slot < 9; slot++) {
                ItemStack current = grid.getItem(slot);
                if (!current.isEmpty()) {
                    ItemStack remainder = view.insert(current.copy(), false);
                    grid.setItem(slot, remainder);
                }
                if (clearPattern) setPatternSlot(slot, ItemStack.EMPTY);
            }
        } finally {
            refilling = false;
        }
        updateResult();
        super.broadcastChanges();
    }

    /**
     * Applies a JEI-selected grid on the server. The supplied variants are not
     * trusted: the selected recipe must still match them in the server RecipeManager.
     * Existing grid contents are returned in one Fabric Transfer transaction first.
     */
    public boolean applyRecipeTransfer(String recipeId, List<String> encodedPattern) {
        if (!(player instanceof ServerPlayer) || player.level().isClientSide
                || encodedPattern == null || encodedPattern.size() != 9) return false;
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        if (id == null) return false;

        List<ItemStack> selectedItems = new ArrayList<>(9);
        try {
            for (String encoded : encodedPattern) {
                if (encoded == null || encoded.isEmpty()) {
                    selectedItems.add(ItemStack.EMPTY);
                } else {
                    StackKey key = StackKey.decode(encoded);
                    if (key == null || key.item() == null) return false;
                    selectedItems.add(key.stack(1));
                }
            }
        } catch (RuntimeException invalidPattern) {
            return false;
        }

        CraftingInput selectedInput = CraftingInput.of(3, 3, selectedItems);
        RecipeHolder<CraftingRecipe> selectedRecipe = null;
        for (RecipeHolder<CraftingRecipe> holder : player.level().getRecipeManager()
                .getRecipesFor(RecipeType.CRAFTING, selectedInput, player.level())) {
            if (holder.id().equals(id)) {
                selectedRecipe = holder;
                break;
            }
        }
        if (selectedRecipe == null || !selectedRecipe.value().matches(selectedInput, player.level())) {
            return false;
        }

        SccNetworkView view = network();
        if (view.truncated()) return false;
        try (Transaction transaction = Transaction.openOuter()) {
            for (int slot = 0; slot < 9; slot++) {
                ItemStack current = grid.getItem(slot);
                if (current.isEmpty()) continue;
                long inserted = view.itemTransferStorage().insert(
                        ItemVariant.of(current), current.getCount(), transaction);
                if (inserted != current.getCount()) return false;
            }
            transaction.commit();
        }

        refilling = true;
        try {
            for (int slot = 0; slot < 9; slot++) {
                grid.setItem(slot, ItemStack.EMPTY);
                setPatternSlot(slot, selectedItems.get(slot));
            }
            SccCraftingTerminalBlockEntity terminal = crafting();
            if (terminal != null) terminal.setPickedRecipe(id.toString());
        } finally {
            refilling = false;
        }
        refillFromNetwork();
        updateResult();
        super.broadcastChanges();
        return true;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (player != this.player || player.level().isClientSide) return false;
        if (id == ACTION_REFILL) {
            refillFromNetwork();
            return true;
        }
        if (id == ACTION_CLEAR) {
            returnGridToNetwork(true);
            return true;
        }
        if (id == ACTION_PICK_RECIPE) {
            pickNextRecipe();
            return true;
        }
        return false;
    }

    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        8 + column * 18, 84 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column,
                    8 + column * 18, 142));
        }
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // Pattern synchronization slots are transport-only and never player-interactive.
        if (slotId >= 46 && slotId < 55) return;
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= 46 || index >= slots.size()) return ItemStack.EMPTY;
        Slot source = slots.get(index);
        if (!source.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = source.getItem();
        ItemStack original = stack.copy();

        if (index == 0) {
            if (!moveItemStackTo(stack, 10, 46, true)) return ItemStack.EMPTY;
            source.onQuickCraft(stack, original);
            source.onTake(player, stack);
        } else if (index >= 1 && index <= 9) {
            if (!moveItemStackTo(stack, 10, 46, true)) return ItemStack.EMPTY;
            if (stack.isEmpty()) source.set(ItemStack.EMPTY);
            else source.setChanged();
        } else {
            if (player.level().isClientSide) return ItemStack.EMPTY;
            SccNetworkView view = network();
            if (view.truncated()) return ItemStack.EMPTY;
            ItemStack remainder = view.insert(stack.copy(), false);
            if (remainder.getCount() == stack.getCount()) return ItemStack.EMPTY;
            source.set(remainder);
        }

        if (stack.isEmpty()) source.set(ItemStack.EMPTY);
        else source.setChanged();
        updateResult();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        if (wireless) {
            return player instanceof ServerPlayer
                    && WirelessAccess.stillValid(player, pos, remoteDimension, true);
        }
        Level level = networkLevel();
        return level != null && level.getBlockEntity(pos) instanceof SccCraftingTerminalBlockEntity
                && player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                    pos.getZ() + 0.5D) <= 64.0D;
    }

    private final class NetworkResultSlot extends ResultSlot {
        private NetworkResultSlot(Player player, CraftingContainer crafting,
                                  net.minecraft.world.Container resultContainer,
                                  int slot, int x, int y) {
            super(player, crafting, resultContainer, slot, x, y);
        }

        @Override
        public void onTake(Player player, ItemStack stack) {
            // Vanilla ResultSlot consumes ingredients and inserts/drops crafting
            // remainders. Suppress pattern rewrites while those mutations occur,
            // otherwise the final consumed item would erase its refill pattern.
            refilling = true;
            try {
                super.onTake(player, stack);
            } finally {
                refilling = false;
            }
            refillFromNetwork();
            updateResult();
        }
    }
}
