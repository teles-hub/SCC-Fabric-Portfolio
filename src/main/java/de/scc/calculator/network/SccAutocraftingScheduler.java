package de.scc.calculator.network;

import de.scc.calculator.platform.SccMachineAdapters;
import de.scc.calculator.platform.SccMachineProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.HashMap;
import java.util.UUID;

/**
 * Bounded, server-thread autocrafting coordinator for one loaded SCC terminal.
 *
 * <p>One job transition is processed per terminal every five server ticks. All
 * machine calls are gated by an explicitly registered SccMachineProcessor.
 * The coordinator never loads chunks and never treats inventory-only adapters
 * as executors. All external dispatches are preceded by a world save barrier.</p>
 */
public final class SccAutocraftingScheduler {
    private static final long TICK_INTERVAL = 5L;
    private static final Map<ServerLevel, Map<Long, Long>> LAST_JOB_BY_TERMINAL = new WeakHashMap<>();

    private SccAutocraftingScheduler() { }

    public static void tick(ServerLevel level, BlockPos terminalPos,
                            SccTerminalBlockEntity terminal) {
        if (level == null || terminalPos == null || terminal == null
                || !level.getServer().isSameThread()
                || level.getGameTime() % TICK_INTERVAL != 0L
                || terminal.isRemoved() || !level.hasChunkAt(terminalPos)) return;

        SccNetworkView network = SccNetworkCache.view(level, terminalPos);
        if (network == null || network.truncated() || !network.online()
                || !network.terminals().contains(terminalPos)) return;
        List<SccItemStore> stores = network.itemStores();
        if (stores.isEmpty()) return;

        SccOperationJournal worldJournal = SccWorldOperationData.get(level.getServer()).journal();
        reconcileWorldJournal(terminal, worldJournal);
        if (recoverUndispatchedIntents(level, terminalPos, terminal, stores, worldJournal)) return;

        List<SccCraftJobQueue.Job> jobs = terminal.craftJobs().jobs();
        if (jobs.isEmpty()) return;
        long cursor = lastJobId(level, terminalPos);
        int start = 0;
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).id() > cursor) { start = i; break; }
        }
        for (int offset = 0; offset < jobs.size(); offset++) {
            SccCraftJobQueue.Job job = jobs.get((start + offset) % jobs.size());
            if (job == null) continue;
            rememberJobId(level, terminalPos, job.id());
            if (job.terminal()) continue;
            SccCraftJobQueue.Escrow escrow = job.escrow();
            if (escrow != null && (SccCraftJobQueue.EscrowPhase.DISPATCHING.key().equals(escrow.phase())
                    || SccCraftJobQueue.EscrowPhase.DISPATCHED.key().equals(escrow.phase())
                    || SccCraftJobQueue.EscrowPhase.OUTPUT_CONFIRMED.key().equals(escrow.phase())
                    || SccCraftJobQueue.EscrowPhase.RECOVERY_REQUIRED.key().equals(escrow.phase()))) {
                tickExistingOperation(level, terminalPos, terminal, stores, worldJournal, job);
                return;
            }
            if (SccCraftJobQueue.Status.PLANNED.key().equals(job.status())
                    || SccCraftJobQueue.Status.WAITING_FOR_MACHINE.key().equals(job.status())
                    || SccCraftJobQueue.Status.WAITING_FOR_MATERIALS.key().equals(job.status())
                    || SccCraftJobQueue.Status.RUNNING.key().equals(job.status())) {
                startNextStep(level, terminalPos, terminal, stores, network, worldJournal, job);
                return;
            }
        }
    }

    private static void startNextStep(ServerLevel level, BlockPos terminalPos,
                                      SccTerminalBlockEntity terminal, List<SccItemStore> stores,
                                      SccNetworkView network, SccOperationJournal worldJournal,
                                      SccCraftJobQueue.Job original) {
        SccCraftJobQueue queue = terminal.craftJobs();
        SccCraftJobQueue.Job job = queue.byId(original.id());
        if (job == null || job.terminal() || !job.hasCompleteIngredientPlan()
                || job.completedSteps() >= job.stepCount()) return;
        // A journal entry outside a terminal phase blocks new operation IDs
        // for this job. This prevents retrying around an unresolved escrow ledger.
        for (SccOperationJournal.Entry pending : terminal.operationJournal().entries()) {
            if (pending.jobId() != job.id()) continue;
            SccOperationJournal.Phase pendingPhase = SccOperationJournal.Phase.from(pending.phase());
            if (pendingPhase != SccOperationJournal.Phase.ACKNOWLEDGED
                    && pendingPhase != SccOperationJournal.Phase.ABORTED) return;
        }

        if (SccCraftJobQueue.Status.WAITING_FOR_MATERIALS.key().equals(job.status())) {
            if (!queue.markPlanned(job.id(), level.getGameTime())) return;
            terminal.setChanged();
            job = queue.byId(job.id());
        }

        int stepIndex = job.completedSteps();
        String operationId = operationId(level, terminalPos, job.id(), stepIndex);
        SccMachineProcessor.Operation preflight =
                SccMachineOperationFactory.forPlannedStep(job, stepIndex, operationId);
        if (preflight == null) {
            queue.markWaitingForMachine(job.id(), level.getGameTime(),
                    "The saved recipe step cannot be converted into a safe machine operation");
            terminal.setChanged();
            return;
        }

        MachineChoice choice = chooseMachine(level, network, preflight);
        if (choice == null) {
            queue.markWaitingForMachine(job.id(), level.getGameTime(),
                    "No connected machine has a registered processor that accepts this recipe");
            terminal.setChanged();
            return;
        }

        List<SccOperationJournal.ReservationLot> journalInputs = new ArrayList<>();
        for (SccMachineProcessor.Ingredient ingredient : preflight.ingredients()) {
            journalInputs.add(new SccOperationJournal.ReservationLot(
                    ingredient.itemKey(), ingredient.amount(), ingredient.consumed()));
        }
        List<SccOperationJournal.OutputLot> expectedOutputs = preflight.products().stream()
                .map(product -> new SccOperationJournal.OutputLot(product.itemKey(), product.amount()))
                .toList();
        SccOperationJournal.Entry intent;
        try {
            intent = new SccOperationJournal.Entry(job.id(), stepIndex, operationId,
                    choice.endpoint().alias().toString(), choice.endpoint().position().getX(),
                    choice.endpoint().position().getY(), choice.endpoint().position().getZ(),
                    preflight.recipeId(), preflight.recipeType(), preflight.crafts(),
                    SccOperationJournal.Phase.PREPARING.key(), expectedOutputs, List.of(), journalInputs);
        } catch (IllegalArgumentException invalidIntent) {
            queue.markWaitingForMachine(job.id(), level.getGameTime(), "Machine operation journal rejected the plan");
            terminal.setChanged();
            return;
        }

        SccOperationJournal localJournal = terminal.operationJournal();
        if (!localJournal.begin(intent)) return;
        if (!worldJournal.begin(intent)) {
            localJournal.abortBeforeDispatch(operationId);
            terminal.setChanged();
            return;
        }
        if (!SccWorldSaveBarrier.saveAllNow(level.getServer())) return;

        List<SccDurableMaterialEscrow.Input> demand = job.plannedSteps().get(stepIndex).inputs().stream()
                .map(input -> new SccDurableMaterialEscrow.Input(
                        input.itemKey(), input.count(), input.consumed()))
                .toList();
        SccDurableMaterialEscrow.Outcome reserved =
                SccDurableMaterialEscrow.reserve(operationId, stores, demand,
                        job.plannedSteps().get(stepIndex).crafts());
        if (!reserved.successful()) {
            boolean released = SccDurableMaterialEscrow.release(operationId, stores);
            boolean acknowledged = released && SccDurableMaterialEscrow.acknowledge(operationId, stores);
            if (!released || !acknowledged) {
                localJournal.markRecoveryRequired(operationId);
                worldJournal.markRecoveryRequired(operationId);
                terminal.setChanged();
                SccWorldSaveBarrier.saveAllNow(level.getServer());
                return;
            }
            localJournal.abortBeforeDispatch(operationId);
            worldJournal.abortBeforeDispatch(operationId);
            if ("reservation_insufficient_materials".equals(reserved.status())) {
                queue.markWaitingForMaterials(job.id(), level.getGameTime(),
                        "Required ingredients are not currently available in SCC storage");
            } else {
                queue.markWaitingForMachine(job.id(), level.getGameTime(),
                        "Durable material escrow rejected the operation: " + reserved.status());
            }
            terminal.setChanged();
            SccWorldSaveBarrier.saveAllNow(level.getServer());
            return;
        }

        List<SccCraftJobQueue.EscrowLot> queueLots = reserved.lots().stream()
                .map(lot -> new SccCraftJobQueue.EscrowLot(lot.itemKey(), lot.amount(), lot.consumed()))
                .toList();
        if (!queue.reserveMaterials(job.id(), stepIndex, queueLots, level.getGameTime())
                || !localJournal.markReserved(operationId)
                || !worldJournal.markReserved(operationId)) {
            boolean released = SccDurableMaterialEscrow.release(operationId, stores);
            boolean acknowledged = released && SccDurableMaterialEscrow.acknowledge(operationId, stores);
            if (!released || !acknowledged) {
                localJournal.markRecoveryRequired(operationId);
                worldJournal.markRecoveryRequired(operationId);
                terminal.setChanged();
                SccWorldSaveBarrier.saveAllNow(level.getServer());
                return;
            }
            SccCraftJobQueue.Job afterReserve = queue.byId(job.id());
            if (afterReserve != null && afterReserve.escrow() != null
                    && SccCraftJobQueue.EscrowPhase.RESERVED.key().equals(afterReserve.escrow().phase())) {
                queue.markEscrowReturned(job.id(), stepIndex, level.getGameTime());
            }
            localJournal.abortBeforeDispatch(operationId);
            worldJournal.abortBeforeDispatch(operationId);
            terminal.setChanged();
            SccWorldSaveBarrier.saveAllNow(level.getServer());
            return;
        }
        terminal.setChanged();
        if (!SccWorldSaveBarrier.saveAllNow(level.getServer())) return;

        if (!queue.markDispatching(job.id(), stepIndex, choice.endpoint().alias().toString(),
                operationId, level.getGameTime())
                || !localJournal.markDispatching(operationId)
                || !worldJournal.markDispatching(operationId)) {
            queue.markRecoveryRequired(job.id(), level.getGameTime(),
                    "Could not persist all dispatch intent records; external dispatch was not attempted");
            localJournal.markRecoveryRequired(operationId);
            worldJournal.markRecoveryRequired(operationId);
            terminal.setChanged();
            SccWorldSaveBarrier.saveAllNow(level.getServer());
            return;
        }
        terminal.setChanged();
        if (!SccWorldSaveBarrier.saveAllNow(level.getServer())) {
            queue.markRecoveryRequired(job.id(), level.getGameTime(),
                    "Save barrier failed before machine dispatch");
            localJournal.markRecoveryRequired(operationId);
            worldJournal.markRecoveryRequired(operationId);
            terminal.setChanged();
            return;
        }

        SccMachineProcessor.DispatchResult dispatched;
        try {
            dispatched = choice.processor().dispatch(level, choice.endpoint().position(), preflight);
        } catch (Throwable failure) {
            dispatched = SccMachineProcessor.DispatchResult.unknown("Processor threw during dispatch");
        }
        if (dispatched == null) dispatched = SccMachineProcessor.DispatchResult.unknown("Processor returned no dispatch result");

        if (dispatched.state() == SccMachineProcessor.DispatchState.REJECTED) {
            boolean released = SccDurableMaterialEscrow.release(operationId, stores);
            if (released && queue.markDispatchRejected(job.id(), stepIndex, operationId, level.getGameTime())
                    && queue.markEscrowReturned(job.id(), stepIndex, level.getGameTime())) {
                localJournal.markDispatchRejected(operationId);
                worldJournal.markDispatchRejected(operationId);
                SccDurableMaterialEscrow.acknowledge(operationId, stores);
            } else {
                queue.markRecoveryRequired(job.id(), level.getGameTime(),
                        "Definitive rejection could not be fully rolled back");
                localJournal.markRecoveryRequired(operationId);
                worldJournal.markRecoveryRequired(operationId);
            }
            terminal.setChanged();
            SccWorldSaveBarrier.saveAllNow(level.getServer());
            return;
        }
        if (dispatched.state() != SccMachineProcessor.DispatchState.ACCEPTED
                || !queue.markDispatched(job.id(), stepIndex, operationId, level.getGameTime())
                || !localJournal.markDispatched(operationId)
                || !worldJournal.markDispatched(operationId)) {
            queue.markRecoveryRequired(job.id(), level.getGameTime(),
                    "Dispatch outcome is ambiguous; operation token must be reconciled");
            localJournal.markRecoveryRequired(operationId);
            worldJournal.markRecoveryRequired(operationId);
            terminal.setChanged();
            SccWorldSaveBarrier.saveAllNow(level.getServer());
            return;
        }
        if (!SccCraftJobQueue.Status.RUNNING.key().equals(job.status())) {
            queue.markRunning(job.id(), level.getGameTime());
        }
        terminal.setChanged();
        SccWorldSaveBarrier.saveAllNow(level.getServer());
    }

    private static void tickExistingOperation(ServerLevel level, BlockPos terminalPos,
                                               SccTerminalBlockEntity terminal, List<SccItemStore> stores,
                                               SccOperationJournal worldJournal,
                                               SccCraftJobQueue.Job job) {
        SccCraftJobQueue.Escrow escrow = job.escrow();
        if (escrow == null || escrow.operationId().isBlank()) return;
        String operationId = escrow.operationId();
        SccOperationJournal local = terminal.operationJournal();
        SccOperationJournal.Entry entry = local.byOperationId(operationId);
        SccOperationJournal.Entry worldEntry = worldJournal.byOperationId(operationId);
        if (entry == null) entry = worldEntry;
        if (entry == null) {
            terminal.craftJobs().markRecoveryRequired(job.id(), level.getGameTime(),
                    "Operation escrow has no durable journal entry");
            terminal.setChanged();
            return;
        }

        SccOperationJournal.Phase phase = SccOperationJournal.Phase.from(entry.phase());
        if (phase == SccOperationJournal.Phase.PREPARING || phase == SccOperationJournal.Phase.RESERVED) {
            if (!SccDurableMaterialEscrow.release(operationId, stores)) return;
            if (!SccDurableMaterialEscrow.acknowledge(operationId, stores)) return;
            if (SccCraftJobQueue.EscrowPhase.RESERVED.key().equals(escrow.phase())) {
                terminal.craftJobs().markEscrowReturned(job.id(), entry.stepIndex(), level.getGameTime());
            }
            local.abortBeforeDispatch(operationId);
            worldJournal.abortBeforeDispatch(operationId);
            terminal.setChanged();
            SccWorldSaveBarrier.saveAllNow(level.getServer());
            return;
        }

        // Once the journal is ACKNOWLEDGED, completion is entirely backed by
        // SCC storage receipts and no longer depends on the machine still existing.
        SccMachineProcessor processor = phase == SccOperationJournal.Phase.ACKNOWLEDGED
                ? null : processorForEntry(level, entry);
        if (phase != SccOperationJournal.Phase.ACKNOWLEDGED && processor == null) return;

        if (SccCraftJobQueue.EscrowPhase.RECOVERY_REQUIRED.key().equals(escrow.phase())) {
            if (phase == SccOperationJournal.Phase.OUTPUT_OBSERVED
                    || phase == SccOperationJournal.Phase.OUTPUT_COMMITTED
                    || phase == SccOperationJournal.Phase.ACKNOWLEDGED) {
                if (!terminal.craftJobs().resumeRecoveredOutput(job.id(), entry.stepIndex(),
                        operationId, level.getGameTime())) return;
            } else {
                SccMachineProcessor.OperationState observed;
                try { observed = processor.state(level, machinePosition(entry), operationId); }
                catch (Throwable failure) { observed = SccMachineProcessor.OperationState.UNKNOWN; }
                if (observed != SccMachineProcessor.OperationState.QUEUED
                        && observed != SccMachineProcessor.OperationState.RUNNING
                        && observed != SccMachineProcessor.OperationState.OUTPUT_READY) return;
                if (!terminal.craftJobs().resumeRecoveredDispatch(job.id(), entry.stepIndex(),
                        operationId, level.getGameTime())) return;
                if (phase == SccOperationJournal.Phase.RECOVERY_REQUIRED) {
                    local.reconcileAccepted(operationId);
                    worldJournal.reconcileAccepted(operationId);
                }
            }
            terminal.setChanged();
            if (!SccWorldSaveBarrier.saveAllNow(level.getServer())) return;
            job = terminal.craftJobs().byId(job.id());
            escrow = job == null ? null : job.escrow();
            entry = local.byOperationId(operationId);
            if (entry == null) entry = worldJournal.byOperationId(operationId);
            phase = entry == null ? null : SccOperationJournal.Phase.from(entry.phase());
        }

        if (phase == SccOperationJournal.Phase.DISPATCHED
                && escrow != null
                && SccCraftJobQueue.EscrowPhase.DISPATCHED.key().equals(escrow.phase())) {
            SccMachineProcessor.OperationState state;
            try { state = processor.state(level, machinePosition(entry), operationId); }
            catch (Throwable failure) { state = SccMachineProcessor.OperationState.UNKNOWN; }
            if (state == SccMachineProcessor.OperationState.OUTPUT_READY) {
                SccMachineProcessor.OutputSnapshot snapshot;
                try { snapshot = processor.outputs(level, machinePosition(entry), operationId); }
                catch (Throwable failure) { snapshot = null; }
                if (!matchesExpected(snapshot, entry.expectedOutputs())) return;
                List<SccOperationJournal.OutputLot> observed = snapshot.products().stream()
                        .map(product -> new SccOperationJournal.OutputLot(product.itemKey(), product.amount()))
                        .toList();
                if (!terminal.craftJobs().markOutputConfirmed(job.id(), entry.stepIndex(),
                        operationId, level.getGameTime())
                        || !local.observeOutputs(operationId, observed)
                        || !worldJournal.observeOutputs(operationId, observed)) return;
                terminal.setChanged();
                if (!SccWorldSaveBarrier.saveAllNow(level.getServer())) return;
                job = terminal.craftJobs().byId(job.id());
                escrow = job == null ? null : job.escrow();
                entry = local.byOperationId(operationId);
                phase = entry == null ? null : SccOperationJournal.Phase.from(entry.phase());
            } else if (state == SccMachineProcessor.OperationState.FAILED
                    || state == SccMachineProcessor.OperationState.REJECTED
                    || state == SccMachineProcessor.OperationState.UNKNOWN) {
                terminal.craftJobs().markRecoveryRequired(job.id(), level.getGameTime(),
                        "Machine operation did not reach an authoritative output-ready state");
                local.markRecoveryRequired(operationId);
                worldJournal.markRecoveryRequired(operationId);
                terminal.setChanged();
                SccWorldSaveBarrier.saveAllNow(level.getServer());
                return;
            } else {
                return;
            }
        }

        if (phase == SccOperationJournal.Phase.OUTPUT_OBSERVED
                || phase == SccOperationJournal.Phase.OUTPUT_COMMITTED
                || phase == SccOperationJournal.Phase.ACKNOWLEDGED) {
            List<SccDurableMaterialEscrow.Product> products = entry.observedOutputs().isEmpty()
                    ? entry.expectedOutputs().stream()
                    .map(output -> new SccDurableMaterialEscrow.Product(output.itemKey(), output.amount())).toList()
                    : entry.observedOutputs().stream()
                    .map(output -> new SccDurableMaterialEscrow.Product(output.itemKey(), output.amount())).toList();
            SccDurableMaterialEscrow.OutputOutcome inserted =
                    SccDurableMaterialEscrow.insertOutputs(operationId, stores, products);
            if (!inserted.successful()) return;
            if (phase == SccOperationJournal.Phase.OUTPUT_OBSERVED) {
                if (!local.markOutputCommitted(operationId) || !worldJournal.markOutputCommitted(operationId)) return;
                terminal.setChanged();
                if (!SccWorldSaveBarrier.saveAllNow(level.getServer())) return;
                phase = SccOperationJournal.Phase.OUTPUT_COMMITTED;
            }
            if (!SccDurableMaterialEscrow.consume(operationId, stores)) return;
            if (!SccWorldSaveBarrier.saveAllNow(level.getServer())) return;

            if (phase != SccOperationJournal.Phase.ACKNOWLEDGED) {
                boolean acknowledged;
                try { acknowledged = processor.acknowledgeOutputs(level, machinePosition(entry), operationId); }
                catch (Throwable failure) { acknowledged = false; }
                if (!acknowledged) return;
                if (!local.markAcknowledged(operationId) || !worldJournal.markAcknowledged(operationId)) return;
            }
            SccCraftJobQueue.Job current = terminal.craftJobs().byId(job.id());
            if (current == null) return;
            SccCraftJobQueue.Escrow currentEscrow = current.escrow();
            if (currentEscrow != null
                    && SccCraftJobQueue.EscrowPhase.OUTPUT_CONFIRMED.key().equals(currentEscrow.phase())) {
                terminal.craftJobs().completeStepWithEscrow(job.id(), entry.stepIndex(),
                        operationId, level.getGameTime());
            }
            terminal.setChanged();
            if (!SccWorldSaveBarrier.saveAllNow(level.getServer())) return;
            SccDurableMaterialEscrow.acknowledgeOutputs(operationId, stores);
            SccDurableMaterialEscrow.acknowledge(operationId, stores);
            SccWorldSaveBarrier.saveAllNow(level.getServer());
        }
    }

    private static void reconcileWorldJournal(SccTerminalBlockEntity terminal,
                                              SccOperationJournal worldJournal) {
        SccOperationJournal local = terminal.operationJournal();
        for (SccOperationJournal.Entry entry : local.entries()) {
            SccOperationJournal.Phase phase = SccOperationJournal.Phase.from(entry.phase());
            if (phase == SccOperationJournal.Phase.ACKNOWLEDGED) {
                worldJournal.reconcileAcknowledged(entry.operationId());
            } else if (phase == SccOperationJournal.Phase.ABORTED) {
                worldJournal.reconcileAborted(entry.operationId());
            }
        }
    }

    private static boolean recoverUndispatchedIntents(ServerLevel level, BlockPos terminalPos,
                                                       SccTerminalBlockEntity terminal,
                                                       List<SccItemStore> stores,
                                                       SccOperationJournal worldJournal) {
        for (SccOperationJournal.Entry entry : terminal.operationJournal().entries()) {
            SccOperationJournal.Phase phase = SccOperationJournal.Phase.from(entry.phase());
            if (phase != SccOperationJournal.Phase.PREPARING
                    && phase != SccOperationJournal.Phase.RESERVED) continue;
            if (!releaseUnsentIntent(level, terminal, stores, worldJournal, entry)) return true;
            return true;
        }
        // The world journal can survive a partial save even when the terminal
        // chunk did not. Operation IDs encode the terminal identity so a loaded
        // terminal can reconcile only its own write-ahead intents.
        for (SccOperationJournal.Entry entry : worldJournal.entries()) {
            SccOperationJournal.Phase phase = SccOperationJournal.Phase.from(entry.phase());
            if (phase != SccOperationJournal.Phase.PREPARING
                    && phase != SccOperationJournal.Phase.RESERVED
                    || !belongsToTerminal(level, terminalPos, entry.operationId())) continue;
            SccOperationJournal.Entry localEntry = terminal.operationJournal().byOperationId(entry.operationId());
            if (localEntry != null) {
                SccOperationJournal.Phase localPhase = SccOperationJournal.Phase.from(localEntry.phase());
                if (localPhase != SccOperationJournal.Phase.PREPARING
                        && localPhase != SccOperationJournal.Phase.RESERVED
                        && localPhase != SccOperationJournal.Phase.ABORTED) continue;
            }
            if (!releaseUnsentIntent(level, terminal, stores, worldJournal, entry)) return true;
            return true;
        }
        return false;
    }

    private static boolean releaseUnsentIntent(ServerLevel level, SccTerminalBlockEntity terminal,
                                               List<SccItemStore> stores,
                                               SccOperationJournal worldJournal,
                                               SccOperationJournal.Entry entry) {
        if (!SccDurableMaterialEscrow.release(entry.operationId(), stores)
                || !SccDurableMaterialEscrow.acknowledge(entry.operationId(), stores)) return false;
        SccCraftJobQueue.Job job = terminal.craftJobs().byId(entry.jobId());
        if (job != null && job.escrow() != null
                && SccCraftJobQueue.EscrowPhase.RESERVED.key().equals(job.escrow().phase())) {
            terminal.craftJobs().markEscrowReturned(job.id(), entry.stepIndex(), level.getGameTime());
        }
        SccOperationJournal.Entry localEntry =
                terminal.operationJournal().byOperationId(entry.operationId());
        if (localEntry != null) {
            SccOperationJournal.Phase localPhase = SccOperationJournal.Phase.from(localEntry.phase());
            if (localPhase == SccOperationJournal.Phase.PREPARING
                    || localPhase == SccOperationJournal.Phase.RESERVED) {
                terminal.operationJournal().abortBeforeDispatch(entry.operationId());
            }
        }
        SccOperationJournal.Phase worldPhase = SccOperationJournal.Phase.from(entry.phase());
        if (worldPhase == SccOperationJournal.Phase.PREPARING
                || worldPhase == SccOperationJournal.Phase.RESERVED) {
            worldJournal.abortBeforeDispatch(entry.operationId());
        }
        terminal.setChanged();
        SccWorldSaveBarrier.saveAllNow(level.getServer());
        return true;
    }

    private static boolean belongsToTerminal(ServerLevel level, BlockPos terminalPos, String operationId) {
        String prefix = "scc-" + Integer.toUnsignedString(
                level.dimension().location().toString().hashCode(), 36) + "-"
                + terminalPos.getX() + "-" + terminalPos.getY() + "-" + terminalPos.getZ() + "-";
        return operationId != null && operationId.startsWith(prefix);
    }

    private static MachineChoice chooseMachine(ServerLevel level, SccNetworkView network,
                                               SccMachineProcessor.Operation operation) {
        for (SccNetworkView.MachineEndpoint endpoint : network.machines()) {
            BlockPos pos = endpoint.position();
            if (!level.hasChunkAt(pos)) continue;
            ResourceLocation currentAlias = SccMachineAdapters.machineAlias(level, pos);
            if (!endpoint.alias().equals(currentAlias)) continue;
            SccMachineProcessor processor = SccMachineAdapters.processor(level, pos);
            if (processor == null) continue;
            try {
                SccMachineProcessor.Validation validation = processor.validate(level, pos, operation);
                if (validation != null && validation.accepted()) return new MachineChoice(endpoint, processor);
            } catch (Throwable ignored) {
                // An optional adapter failure only excludes this endpoint.
            }
        }
        return null;
    }

    private static SccMachineProcessor processorForEntry(ServerLevel level, SccOperationJournal.Entry entry) {
        BlockPos pos = machinePosition(entry);
        if (!level.hasChunkAt(pos)) return null;
        ResourceLocation expected = ResourceLocation.tryParse(entry.machineAlias());
        if (expected == null || !expected.equals(SccMachineAdapters.machineAlias(level, pos))) return null;
        return SccMachineAdapters.processor(level, pos);
    }

    private static BlockPos machinePosition(SccOperationJournal.Entry entry) {
        return new BlockPos(entry.machineX(), entry.machineY(), entry.machineZ());
    }

    private static boolean matchesExpected(SccMachineProcessor.OutputSnapshot snapshot,
                                            List<SccOperationJournal.OutputLot> expected) {
        if (snapshot == null || !snapshot.complete() || expected == null || expected.isEmpty()) return false;
        Map<String, Long> wanted = new TreeMap<>();
        Map<String, Long> actual = new TreeMap<>();
        try {
            for (SccOperationJournal.OutputLot lot : expected) wanted.merge(lot.itemKey(), lot.amount(), Math::addExact);
            for (SccMachineProcessor.Product product : snapshot.products()) actual.merge(product.itemKey(), product.amount(), Math::addExact);
        } catch (ArithmeticException overflow) {
            return false;
        }
        return wanted.equals(actual);
    }

    private static synchronized long lastJobId(ServerLevel level, BlockPos terminalPos) {
        return LAST_JOB_BY_TERMINAL.getOrDefault(level, Map.of())
                .getOrDefault(terminalPos.asLong(), 0L);
    }

    private static synchronized void rememberJobId(ServerLevel level, BlockPos terminalPos, long jobId) {
        LAST_JOB_BY_TERMINAL.computeIfAbsent(level, ignored -> new HashMap<>())
                .put(terminalPos.asLong(), jobId);
    }

    private static String operationId(ServerLevel level, BlockPos terminal, long jobId,
                                      int stepIndex) {
        String dimension = level.dimension().location().toString();
        int dimensionHash = dimension.hashCode();
        return "scc-" + Integer.toUnsignedString(dimensionHash, 36) + "-"
                + terminal.getX() + "-" + terminal.getY() + "-" + terminal.getZ()
                + "-" + jobId + "-" + stepIndex + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    private record MachineChoice(SccNetworkView.MachineEndpoint endpoint,
                                 SccMachineProcessor processor) { }
}
