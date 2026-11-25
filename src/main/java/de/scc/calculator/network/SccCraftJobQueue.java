package de.scc.calculator.network;

import de.scc.calculator.calc.StackKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent, bounded queue of server-authoritative SCC craft plans.
 *
 * <p>This queue records validated plans and their material requirements. It does
 * not pretend to execute machines: dispatch/progress transitions are exposed
 * for the later machine scheduler and are only accepted through this server-side
 * object. The current port can therefore persist, inspect and cancel a plan
 * without claiming that the original processing-machine scheduler is complete.</p>
 */
public final class SccCraftJobQueue {
    public static final int MAX_JOBS = 64;
    public static final int MAX_SYNC_JOBS = 16;
    public static final int MAX_REQUIREMENTS = 64;
    public static final int MAX_KEY_LENGTH = 4_000;
    public static final int MAX_REASON_LENGTH = 128;
    public static final long MAX_AMOUNT = 1_000_000L;
    public static final int MAX_STEPS = 2_048;
    public static final int MAX_INPUTS_PER_STEP = 64;
    public static final int MAX_INGREDIENT_KEY_LENGTH = 256;
    public static final long MAX_CRAFTS = 1_000_000_000L;
    public static final int MAX_RECIPE_ID_LENGTH = 256;
    public static final int MAX_RECIPE_TYPE_NAME_LENGTH = 128;
    public static final int MAX_OPERATION_ID_LENGTH = 256;
    public static final long MAX_ESCROW_AMOUNT = 1_000_000_000_000L;

    private final List<Job> jobs = new ArrayList<>();
    private long nextId = 1L;

    public synchronized List<Job> jobs() {
        return List.copyOf(jobs);
    }

    public synchronized List<Job> latest(int limit) {
        int count = Math.max(0, Math.min(MAX_SYNC_JOBS, limit));
        int from = Math.max(0, jobs.size() - count);
        List<Job> result = new ArrayList<>(jobs.subList(from, jobs.size()));
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    public synchronized Job byId(long id) {
        for (Job job : jobs) if (job.id() == id) return job;
        return null;
    }

    /**
     * Compatibility entry point for older callers. Jobs created through this overload
     * retain their summary but have no executable recipe plan; a scheduler must never
     * dispatch them until they have been replanned through enqueuePlanned.
     */
    public synchronized Job enqueue(String targetKey, long amount, UUID owner,
                                     Collection<Requirement> missing, int steps,
                                     long crafts, boolean truncated, long gameTime) {
        return enqueueInternal(targetKey, amount, owner, missing, steps, crafts,
                List.of(), truncated, gameTime);
    }

    /** Stores a complete, ordered recipe plan. The number of persisted steps must match the summary. */
    public synchronized Job enqueuePlanned(String targetKey, long amount, UUID owner,
                                            Collection<Requirement> missing,
                                            Collection<PlannedStep> plannedSteps,
                                            long crafts, boolean truncated, long gameTime) {
        List<PlannedStep> normalizedSteps = normalizePlannedSteps(plannedSteps);
        if (normalizedSteps == null || !validExecutionOrder(normalizedSteps)) return null;
        return enqueueInternal(targetKey, amount, owner, missing, normalizedSteps.size(), crafts,
                normalizedSteps, truncated, gameTime);
    }

    private Job enqueueInternal(String targetKey, long amount, UUID owner,
                                Collection<Requirement> missing, int steps, long crafts,
                                List<PlannedStep> plannedSteps, boolean truncated, long gameTime) {
        if (truncated || targetKey == null || targetKey.isBlank()
                || targetKey.length() > MAX_KEY_LENGTH || StackKey.decode(targetKey) == null
                || amount < 1L || amount > MAX_AMOUNT || owner == null
                || steps < 0 || steps > MAX_STEPS || crafts < 0L || crafts > MAX_CRAFTS) return null;

        List<Requirement> normalized = normalizeRequirements(missing);
        if (normalized == null || plannedSteps == null || plannedSteps.size() > MAX_STEPS) return null;

        makeRoom();
        if (jobs.size() >= MAX_JOBS) return null;
        long id = nextId++;
        if (id <= 0L) {
            nextId = 2L;
            id = 1L;
        }
        String status = !normalized.isEmpty() ? Status.WAITING_FOR_MATERIALS.key()
                : steps > 0 ? Status.PLANNED.key() : Status.READY.key();
        Job job = new Job(id, owner.toString(), targetKey, amount, status,
                steps, 0, crafts, normalized, gameTime, gameTime, "", plannedSteps);
        jobs.add(job);
        return job;
    }

    /** True when a non-terminal job already targets the same exact component-sensitive stack key. */
    public synchronized boolean hasActiveTarget(String targetKey) {
        if (targetKey == null || targetKey.isBlank()) return false;
        return jobs.stream().anyMatch(job -> !job.terminal() && targetKey.equals(job.targetKey()));
    }

    /** Only the player who created the plan may cancel it. */
    public synchronized boolean cancel(long id, UUID actor, long gameTime) {
        if (actor == null) return false;
        int index = indexOf(id);
        if (index < 0) return false;
        Job current = jobs.get(index);
        if (!actor.toString().equals(current.owner()) || current.terminal()
                || (current.escrow() != null && current.escrow().blocksUnsafeTransition())) return false;
        jobs.set(index, current.withState(Status.CANCELLED, current.completedSteps(),
                gameTime, "Cancelled by owner"));
        return true;
    }

    /**
     * Persists the exact escrow materials for the next incomplete step. The caller
     * must first commit SccMaterialReservation.reserve and then persist this queue;
     * a durable cross-save journal is still required to close that crash window.
     */
    public synchronized boolean reserveMaterials(long id, int stepIndex,
                                                 Collection<EscrowLot> materials, long gameTime) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        if (!job.hasCompleteIngredientPlan() || stepIndex != job.completedSteps()
                || stepIndex < 0 || stepIndex >= job.stepCount()
                || !(Status.PLANNED.key().equals(job.status())
                || Status.WAITING_FOR_MACHINE.key().equals(job.status())
                || Status.RUNNING.key().equals(job.status()))) return false;
        if (job.escrow() != null && job.escrow().blocksUnsafeTransition()) return false;
        List<EscrowLot> normalized = normalizeEscrowLots(materials);
        if (normalized == null || !matchesStepDemand(job.plannedSteps().get(stepIndex), normalized)) return false;
        Escrow escrow = new Escrow(stepIndex, EscrowPhase.RESERVED.key(), "", "", normalized);
        jobs.set(index, job.withEscrow(escrow, gameTime, ""));
        return true;
    }

    /** Persists machine identity and idempotency token before making an external dispatch call. */
    public synchronized boolean markDispatching(long id, int stepIndex, String machineAlias,
                                                String operationId, long gameTime) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        Escrow escrow = job.escrow();
        if (escrow == null || !EscrowPhase.RESERVED.key().equals(escrow.phase())
                || escrow.stepIndex() != stepIndex || stepIndex != job.completedSteps()
                || !validResourceId(machineAlias) || !validOperationId(operationId)
                || job.terminal()) return false;
        Escrow next = new Escrow(stepIndex, EscrowPhase.DISPATCHING.key(), machineAlias,
                operationId, escrow.materials());
        jobs.set(index, job.withEscrow(next, gameTime, ""));
        return true;
    }

    /** Records processor acceptance of the previously persisted operation token. */
    public synchronized boolean markDispatched(long id, int stepIndex, String operationId, long gameTime) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        Escrow escrow = job.escrow();
        if (escrow == null || !EscrowPhase.DISPATCHING.key().equals(escrow.phase())
                || escrow.stepIndex() != stepIndex || stepIndex != job.completedSteps()
                || !validOperationId(operationId) || !operationId.equals(escrow.operationId())
                || job.terminal()) return false;
        jobs.set(index, job.withEscrow(escrow.withPhase(EscrowPhase.DISPATCHED), gameTime, ""));
        return true;
    }

    /** Changes the job status only after the processor accepted a persisted dispatch token. */
    public synchronized boolean markRunning(long id, long gameTime) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        Escrow escrow = job.escrow();
        if (!job.hasCompleteIngredientPlan() || job.stepCount() == 0 || escrow == null
                || !EscrowPhase.DISPATCHED.key().equals(escrow.phase())
                || escrow.stepIndex() != job.completedSteps()
                || !(Status.PLANNED.key().equals(job.status())
                || Status.WAITING_FOR_MACHINE.key().equals(job.status()))) return false;
        jobs.set(index, job.withState(Status.RUNNING, job.completedSteps(), gameTime, ""));
        return true;
    }

    /** A machine output must be observed before an escrow can be finalized as consumed. */
    public synchronized boolean markOutputConfirmed(long id, int stepIndex, String operationId, long gameTime) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        Escrow escrow = job.escrow();
        if (escrow == null || !EscrowPhase.DISPATCHED.key().equals(escrow.phase())
                || escrow.stepIndex() != stepIndex || stepIndex != job.completedSteps()
                || !validOperationId(operationId) || !operationId.equals(escrow.operationId())
                || !Status.RUNNING.key().equals(job.status())) return false;
        Escrow next = escrow.withPhase(EscrowPhase.OUTPUT_CONFIRMED);
        jobs.set(index, job.withEscrow(next, gameTime, ""));
        return true;
    }

    /**
     * Finalizes a step only after the caller observed its output and successfully
     * returned reusable inputs. Progress and escrow finalization are one queue mutation.
     */
    public synchronized boolean completeStepWithEscrow(long id, int stepIndex,
                                                        String operationId, long gameTime) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        Escrow escrow = job.escrow();
        if (escrow == null || !EscrowPhase.OUTPUT_CONFIRMED.key().equals(escrow.phase())
                || escrow.stepIndex() != stepIndex || stepIndex != job.completedSteps()
                || !validOperationId(operationId) || !operationId.equals(escrow.operationId())
                || !Status.RUNNING.key().equals(job.status())) return false;
        int completed = job.completedSteps() + 1;
        Status nextStatus = completed == job.stepCount() ? Status.COMPLETED : Status.RUNNING;
        jobs.set(index, job.withEscrowAndState(escrow.withPhase(EscrowPhase.CONSUMED),
                nextStatus, completed, gameTime, ""));
        return true;
    }

    /**
     * Reopens the reservation only when a processor has definitively rejected the
     * dispatch and guarantees the operation token was never accepted. Ambiguous
     * timeouts must use markRecoveryRequired instead.
     */
    public synchronized boolean markDispatchRejected(long id, int stepIndex,
                                                     String operationId, long gameTime) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        Escrow escrow = job.escrow();
        if (escrow == null || !EscrowPhase.DISPATCHING.key().equals(escrow.phase())
                || escrow.stepIndex() != stepIndex || stepIndex != job.completedSteps()
                || !validOperationId(operationId) || !operationId.equals(escrow.operationId())) return false;
        jobs.set(index, job.withEscrow(escrow.asReserved(), gameTime,
                "Processor definitively rejected dispatch; reservation may be refunded or retried"));
        return true;
    }

    /** A reserved batch can be refunded only before a machine dispatch was recorded. */
    public synchronized boolean markEscrowReturned(long id, int stepIndex, long gameTime) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        Escrow escrow = job.escrow();
        if (escrow == null || !EscrowPhase.RESERVED.key().equals(escrow.phase())
                || escrow.stepIndex() != stepIndex || stepIndex != job.completedSteps()) return false;
        jobs.set(index, job.withEscrow(escrow.withPhase(EscrowPhase.RETURNED), gameTime, ""));
        return true;
    }

    /** Fails closed when a dispatched operation cannot be reconciled after an interruption. */
    public synchronized boolean markRecoveryRequired(long id, long gameTime, String reason) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        Escrow escrow = job.escrow();
        if (escrow == null || !(EscrowPhase.DISPATCHING.key().equals(escrow.phase())
                || EscrowPhase.DISPATCHED.key().equals(escrow.phase())
                || EscrowPhase.OUTPUT_CONFIRMED.key().equals(escrow.phase()))) return false;
        jobs.set(index, job.withEscrowAndState(escrow.withPhase(EscrowPhase.RECOVERY_REQUIRED),
                Status.WAITING_FOR_MACHINE, job.completedSteps(), gameTime,
                reason == null || reason.isBlank() ? "Machine state requires reconciliation" : reason));
        return true;
    }

    /** Unqualified progress updates are deliberately rejected; use completeStepWithEscrow. */
    @Deprecated
    public synchronized boolean updateProgress(long id, int completedSteps, long gameTime) {
        return false;
    }

    /** Moves a material-blocked plan back to PLANNED after a fresh reservation preflight. */
    public synchronized boolean markPlanned(long id, long gameTime) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        if (!job.hasCompleteIngredientPlan() || job.terminal()
                || job.escrow() != null && job.escrow().blocksUnsafeTransition()
                || !Status.WAITING_FOR_MATERIALS.key().equals(job.status())) return false;
        jobs.set(index, job.withState(Status.PLANNED, job.completedSteps(), gameTime, ""));
        return true;
    }

    /** Marks a plan as waiting for stock without reserving or consuming any materials. */
    public synchronized boolean markWaitingForMaterials(long id, long gameTime, String reason) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        if (!job.hasCompleteIngredientPlan() || job.terminal()
                || job.escrow() != null && job.escrow().blocksUnsafeTransition()
                || !(Status.PLANNED.key().equals(job.status())
                || Status.WAITING_FOR_MACHINE.key().equals(job.status())
                || Status.RUNNING.key().equals(job.status()))) return false;
        jobs.set(index, job.withState(Status.WAITING_FOR_MATERIALS, job.completedSteps(), gameTime, reason));
        return true;
    }

    /** Restores an interrupted dispatch only after the machine reports an authoritative accepted state. */
    public synchronized boolean resumeRecoveredDispatch(long id, int stepIndex,
                                                        String operationId, long gameTime) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        Escrow escrow = job.escrow();
        if (escrow == null || !EscrowPhase.RECOVERY_REQUIRED.key().equals(escrow.phase())
                || escrow.stepIndex() != stepIndex || stepIndex != job.completedSteps()
                || !validOperationId(operationId) || !operationId.equals(escrow.operationId())
                || job.terminal()) return false;
        jobs.set(index, job.withEscrowAndState(escrow.withPhase(EscrowPhase.DISPATCHED),
                Status.RUNNING, job.completedSteps(), gameTime,
                "Machine confirmed the persisted operation after restart"));
        return true;
    }

    /** Restores the output-confirmed phase from a durable journal/output receipt. */
    public synchronized boolean resumeRecoveredOutput(long id, int stepIndex,
                                                      String operationId, long gameTime) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        Escrow escrow = job.escrow();
        if (escrow == null || !EscrowPhase.RECOVERY_REQUIRED.key().equals(escrow.phase())
                || escrow.stepIndex() != stepIndex || stepIndex != job.completedSteps()
                || !validOperationId(operationId) || !operationId.equals(escrow.operationId())
                || job.terminal()) return false;
        jobs.set(index, job.withEscrowAndState(escrow.withPhase(EscrowPhase.OUTPUT_CONFIRMED),
                Status.RUNNING, job.completedSteps(), gameTime,
                "Durable output journal restored after restart"));
        return true;
    }

    public synchronized boolean markWaitingForMachine(long id, long gameTime, String reason) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        if (!job.hasCompleteIngredientPlan() || job.stepCount() == 0
                || (job.escrow() != null && job.escrow().blocksUnsafeTransition())
                || !(Status.PLANNED.key().equals(job.status()) || Status.RUNNING.key().equals(job.status()))) {
            return false;
        }
        jobs.set(index, job.withState(Status.WAITING_FOR_MACHINE, job.completedSteps(), gameTime, reason));
        return true;
    }

    public synchronized boolean markFailed(long id, long gameTime, String reason) {
        int index = indexOf(id);
        if (index < 0 || (jobs.get(index).escrow() != null
                && jobs.get(index).escrow().blocksUnsafeTransition())) return false;
        return transition(id, Status.FAILED, -1, gameTime, reason);
    }

    private boolean transition(long id, Status next, int ignoredProgress, long gameTime, String reason) {
        int index = indexOf(id);
        if (index < 0) return false;
        Job job = jobs.get(index);
        if (job.terminal()) return false;
        if (!allowed(job.status(), next)) return false;
        jobs.set(index, job.withState(next, job.completedSteps(), gameTime, reason));
        return true;
    }

    private static boolean allowed(String current, Status next) {
        if (Status.PLANNED.key().equals(current)) {
            return next == Status.RUNNING || next == Status.WAITING_FOR_MACHINE || next == Status.FAILED;
        }
        if (Status.WAITING_FOR_MATERIALS.key().equals(current)) {
            return next == Status.PLANNED || next == Status.FAILED;
        }
        if (Status.WAITING_FOR_MACHINE.key().equals(current)) {
            return next == Status.PLANNED || next == Status.RUNNING || next == Status.FAILED;
        }
        if (Status.RUNNING.key().equals(current)) {
            return next == Status.WAITING_FOR_MACHINE || next == Status.FAILED;
        }
        return false;
    }

    public synchronized void save(CompoundTag target) {
        if (target == null) return;
        ListTag encoded = new ListTag();
        for (Job job : jobs) encoded.add(job.write());
        target.put("scc_craft_jobs", encoded);
        target.putLong("scc_craft_job_next", nextId);
    }

    public synchronized void load(CompoundTag source) {
        jobs.clear();
        if (source == null || !source.contains("scc_craft_jobs")) {
            nextId = Math.max(1L, source == null ? 1L : source.getLong("scc_craft_job_next"));
            return;
        }
        ListTag encoded = source.getList("scc_craft_jobs", 10);
        long maxId = 0L;
        for (int i = 0; i < encoded.size() && jobs.size() < MAX_JOBS; i++) {
            Job job = Job.read(encoded.getCompound(i));
            if (job == null || indexOf(job.id()) >= 0) continue;
            if (job.escrow() != null && (EscrowPhase.DISPATCHING.key().equals(job.escrow().phase())
                    || EscrowPhase.DISPATCHED.key().equals(job.escrow().phase())
                    || EscrowPhase.OUTPUT_CONFIRMED.key().equals(job.escrow().phase()))) {
                job = job.withEscrowAndState(job.escrow().withPhase(EscrowPhase.RECOVERY_REQUIRED),
                        Status.WAITING_FOR_MACHINE, job.completedSteps(), job.updatedAt(),
                        "Server restarted during machine operation; reconcile before retry");
            } else if (job.escrow() == null && Status.RUNNING.key().equals(job.status())) {
                job = job.withState(Status.FAILED, job.completedSteps(), job.updatedAt(),
                        "Legacy running job has no escrow receipt; replan required");
            }
            jobs.add(job);
            maxId = Math.max(maxId, job.id());
        }
        nextId = Math.max(maxId + 1L, source.getLong("scc_craft_job_next"));
        if (nextId < 1L) nextId = maxId + 1L;
    }

    private void makeRoom() {
        if (jobs.size() < MAX_JOBS) return;
        for (int i = 0; i < jobs.size() && jobs.size() >= MAX_JOBS;) {
            if (jobs.get(i).terminal()) jobs.remove(i);
            else i++;
        }
    }

    private int indexOf(long id) {
        for (int i = 0; i < jobs.size(); i++) if (jobs.get(i).id() == id) return i;
        return -1;
    }

    private static List<EscrowLot> normalizeEscrowLots(Collection<EscrowLot> materials) {
        if (materials == null || materials.isEmpty() || materials.size() > MAX_INPUTS_PER_STEP) return null;
        Map<EscrowKey, Long> merged = new LinkedHashMap<>();
        for (EscrowLot lot : materials) {
            if (lot == null || lot.itemKey() == null || lot.itemKey().isBlank()
                    || lot.itemKey().length() > MAX_KEY_LENGTH
                    || StackKey.decode(lot.itemKey()) == null || lot.amount() < 1L
                    || lot.amount() > MAX_ESCROW_AMOUNT) return null;
            EscrowKey key = new EscrowKey(lot.itemKey(), lot.consumed());
            long total;
            try { total = Math.addExact(merged.getOrDefault(key, 0L), lot.amount()); }
            catch (ArithmeticException overflow) { return null; }
            if (total > MAX_ESCROW_AMOUNT) return null;
            merged.put(key, total);
        }
        List<EscrowLot> result = new ArrayList<>(merged.size());
        merged.forEach((key, amount) -> result.add(new EscrowLot(key.itemKey(), amount, key.consumed())));
        return List.copyOf(result);
    }

    private static boolean matchesStepDemand(PlannedStep step, List<EscrowLot> materials) {
        Map<EscrowKey, Long> expected = new LinkedHashMap<>();
        for (PlannedInput input : step.inputs()) {
            long multiplier = input.consumed() ? step.crafts() : 1L;
            long amount;
            try { amount = Math.multiplyExact((long) input.count(), multiplier); }
            catch (ArithmeticException overflow) { return false; }
            if (amount < 1L || amount > MAX_ESCROW_AMOUNT) return false;
            EscrowKey key = new EscrowKey(input.itemKey(), input.consumed());
            long total;
            try { total = Math.addExact(expected.getOrDefault(key, 0L), amount); }
            catch (ArithmeticException overflow) { return false; }
            if (total > MAX_ESCROW_AMOUNT) return false;
            expected.put(key, total);
        }
        Map<EscrowKey, Long> actual = new LinkedHashMap<>();
        for (EscrowLot lot : materials) actual.put(new EscrowKey(lot.itemKey(), lot.consumed()), lot.amount());
        return expected.equals(actual);
    }

    private static boolean validOperationId(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_OPERATION_ID_LENGTH
                && value.matches("[A-Za-z0-9_.:/-]+");
    }

    private record EscrowKey(String itemKey, boolean consumed) { }

    private static List<Requirement> normalizeRequirements(Collection<Requirement> missing) {
        if (missing == null || missing.isEmpty()) return List.of();
        Map<String, Long> merged = new LinkedHashMap<>();
        for (Requirement requirement : missing) {
            if (requirement == null || requirement.key() == null || requirement.key().isBlank()
                    || requirement.key().length() > MAX_KEY_LENGTH
                    || StackKey.decode(requirement.key()) == null
                    || requirement.amount() < 1L || requirement.amount() > MAX_AMOUNT) return null;
            if (!merged.containsKey(requirement.key()) && merged.size() >= MAX_REQUIREMENTS) return null;
            merged.merge(requirement.key(), requirement.amount(),
                    (left, right) -> Math.min(MAX_AMOUNT, left + right));
        }
        return merged.entrySet().stream().map(entry -> new Requirement(entry.getKey(), entry.getValue())).toList();
    }

    private static List<PlannedStep> normalizePlannedSteps(Collection<PlannedStep> steps) {
        if (steps == null) return null;
        if (steps.size() > MAX_STEPS) return null;
        List<PlannedStep> normalized = new ArrayList<>(steps.size());
        for (PlannedStep step : steps) {
            if (step == null || !step.valid()) return null;
            normalized.add(step);
        }
        return List.copyOf(normalized);
    }

    /** Checks that every produced intermediate appears before the step that consumes it. */
    private static boolean validExecutionOrder(List<PlannedStep> steps) {
        Map<String, Integer> producerIndexes = new LinkedHashMap<>();
        int previousDepth = -1;
        for (int i = 0; i < steps.size(); i++) {
            PlannedStep step = steps.get(i);
            if (step.depth() < previousDepth) return false;
            previousDepth = step.depth();
            if (producerIndexes.putIfAbsent(step.outputKey(), i) != null) return false;
        }
        for (int i = 0; i < steps.size(); i++) {
            for (PlannedInput input : steps.get(i).inputs()) {
                Integer producer = producerIndexes.get(input.itemKey());
                if (producer != null && producer >= i) return false;
            }
        }
        return true;
    }

    private static boolean validResourceId(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_RECIPE_ID_LENGTH) return false;
        int split = value.indexOf(':');
        if (split <= 0 || split == value.length() - 1 || split != value.lastIndexOf(':')) return false;
        String namespace = value.substring(0, split);
        String path = value.substring(split + 1);
        return namespace.matches("[a-z0-9_.-]+") && path.matches("[a-z0-9/._-]+");
    }

    public enum EscrowPhase {
        RESERVED("reserved"),
        DISPATCHING("dispatching"),
        DISPATCHED("dispatched"),
        OUTPUT_CONFIRMED("output_confirmed"),
        RETURNED("returned"),
        CONSUMED("consumed"),
        RECOVERY_REQUIRED("recovery_required");

        private final String key;
        EscrowPhase(String key) { this.key = key; }
        public String key() { return key; }
        public static EscrowPhase from(String key) {
            for (EscrowPhase value : values()) if (value.key.equals(key)) return value;
            return null;
        }
    }

    public record EscrowLot(String itemKey, long amount, boolean consumed) {
        public EscrowLot {
            if (itemKey == null || itemKey.isBlank() || itemKey.length() > MAX_KEY_LENGTH
                    || StackKey.decode(itemKey) == null || amount < 1L || amount > MAX_ESCROW_AMOUNT) {
                throw new IllegalArgumentException("Invalid escrow lot");
            }
        }
    }

    public record Escrow(int stepIndex, String phase, String machineAlias, String operationId,
                         List<EscrowLot> materials) {
        public Escrow {
            EscrowPhase parsedPhase = EscrowPhase.from(phase);
            if (stepIndex < 0 || stepIndex >= MAX_STEPS || parsedPhase == null
                    || machineAlias == null || machineAlias.length() > MAX_RECIPE_ID_LENGTH
                    || operationId == null || operationId.length() > MAX_OPERATION_ID_LENGTH
                    || materials == null || materials.isEmpty() || materials.size() > MAX_INPUTS_PER_STEP) {
                throw new IllegalArgumentException("Invalid craft escrow");
            }
            if ((parsedPhase == EscrowPhase.DISPATCHING || parsedPhase == EscrowPhase.DISPATCHED
                    || parsedPhase == EscrowPhase.OUTPUT_CONFIRMED || parsedPhase == EscrowPhase.RECOVERY_REQUIRED)
                    && (!validResourceId(machineAlias) || !validOperationId(operationId))) {
                throw new IllegalArgumentException("Dispatched escrow requires machine and operation identity");
            }
            List<EscrowLot> normalized = normalizeEscrowLots(materials);
            if (normalized == null) throw new IllegalArgumentException("Invalid escrow material list");
            materials = normalized;
        }
        public boolean blocksUnsafeTransition() {
            EscrowPhase value = EscrowPhase.from(phase);
            return value == EscrowPhase.RESERVED || value == EscrowPhase.DISPATCHING
                    || value == EscrowPhase.DISPATCHED || value == EscrowPhase.OUTPUT_CONFIRMED
                    || value == EscrowPhase.RECOVERY_REQUIRED;
        }
        public Escrow withPhase(EscrowPhase next) {
            return new Escrow(stepIndex, next.key(), machineAlias, operationId, materials);
        }
        public Escrow asReserved() {
            return new Escrow(stepIndex, EscrowPhase.RESERVED.key(), "", "", materials);
        }
        private CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("step", stepIndex);
            tag.putString("phase", phase);
            tag.putString("machine", machineAlias);
            tag.putString("operation", operationId);
            ListTag lots = new ListTag();
            for (EscrowLot lot : materials) {
                CompoundTag entry = new CompoundTag();
                entry.putString("item", lot.itemKey());
                entry.putLong("amount", lot.amount());
                entry.putBoolean("consumed", lot.consumed());
                lots.add(entry);
            }
            tag.put("materials", lots);
            return tag;
        }
        private static Escrow read(CompoundTag tag) {
            if (tag == null) return null;
            ListTag lots = tag.getList("materials", 10);
            if (lots.isEmpty() || lots.size() > MAX_INPUTS_PER_STEP) return null;
            List<EscrowLot> decoded = new ArrayList<>();
            try {
                for (int i = 0; i < lots.size(); i++) {
                    CompoundTag entry = lots.getCompound(i);
                    decoded.add(new EscrowLot(entry.getString("item"), entry.getLong("amount"),
                            entry.getBoolean("consumed")));
                }
                return new Escrow(tag.getInt("step"), tag.getString("phase"),
                        tag.getString("machine"), tag.getString("operation"), decoded);
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }

    public enum Status {
        WAITING_FOR_MATERIALS("waiting_materials"),
        PLANNED("planned"),
        READY("ready"),
        WAITING_FOR_MACHINE("waiting_machine"),
        RUNNING("running"),
        COMPLETED("completed"),
        CANCELLED("cancelled"),
        FAILED("failed");

        private final String key;
        Status(String key) { this.key = key; }
        public String key() { return key; }
        public static Status from(String key) {
            for (Status status : values()) if (status.key.equals(key)) return status;
            return null;
        }
    }

    public record Requirement(String key, long amount) {
        public Requirement {
            key = key == null ? "" : key;
            amount = Math.max(0L, Math.min(MAX_AMOUNT, amount));
        }
    }

    /** One concrete, selected ingredient for a recipe step. Count is per craft, not total. */
    public record PlannedInput(String itemKey, int count, boolean consumed, String ingredientKey) {
        public PlannedInput {
            itemKey = itemKey == null ? "" : itemKey;
            ingredientKey = cleanIngredientKey(ingredientKey);
        }

        public boolean valid() {
            return itemKey.length() <= MAX_KEY_LENGTH && StackKey.decode(itemKey) != null
                    && count > 0 && count <= MAX_AMOUNT
                    && ingredientKey.length() <= MAX_INGREDIENT_KEY_LENGTH;
        }

        private CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putString("item", itemKey);
            tag.putInt("count", count);
            tag.putBoolean("consumed", consumed);
            tag.putString("ingredient", ingredientKey);
            return tag;
        }

        private static PlannedInput read(CompoundTag tag) {
            if (tag == null) return null;
            PlannedInput input = new PlannedInput(tag.getString("item"), tag.getInt("count"),
                    tag.getBoolean("consumed"), tag.getString("ingredient"));
            return input.valid() ? input : null;
        }

        private static String cleanIngredientKey(String value) {
            if (value == null) return "";
            String normalized = value.strip().replaceAll("[\\p{Cntrl}]", "");
            return normalized.length() <= MAX_INGREDIENT_KEY_LENGTH
                    ? normalized : normalized.substring(0, MAX_INGREDIENT_KEY_LENGTH);
        }
    }

    /** One recipe invocation group in the exact order produced by the calculator. */
    public record PlannedStep(String outputKey, String recipeId, String recipeType,
                              String recipeTypeName, long produced, long needed,
                              long crafts, int depth, List<PlannedInput> inputs) {
        /** Backward-compatible summary constructor; steps created this way are not executable. */
        public PlannedStep(String outputKey, String recipeId, String recipeType,
                           String recipeTypeName, long produced, long needed,
                           long crafts, int depth) {
            this(outputKey, recipeId, recipeType, recipeTypeName, produced, needed,
                    crafts, depth, List.of());
        }

        public PlannedStep {
            outputKey = outputKey == null ? "" : outputKey;
            recipeId = recipeId == null ? "" : recipeId;
            recipeType = recipeType == null ? "" : recipeType;
            recipeTypeName = cleanTypeName(recipeTypeName);
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            // Keep caller values intact; valid() must reject overflow rather than clamp it.
        }

        public boolean validSummary() {
            if (outputKey.length() > MAX_KEY_LENGTH || StackKey.decode(outputKey) == null
                    || !validResourceId(recipeId) || !validResourceId(recipeType)
                    || recipeTypeName.length() > MAX_RECIPE_TYPE_NAME_LENGTH
                    || produced <= 0L || produced > MAX_CRAFTS
                    || needed <= 0L || needed > MAX_CRAFTS
                    || crafts <= 0L || crafts > MAX_CRAFTS
                    || depth < 0 || depth > MAX_STEPS
                    || inputs.size() > MAX_INPUTS_PER_STEP) return false;
            for (PlannedInput input : inputs) if (input == null || !input.valid()) return false;
            return true;
        }

        /** New plans require concrete resolved inputs; summary-only legacy plans remain readable. */
        public boolean valid() {
            return validSummary() && !inputs.isEmpty();
        }

        private CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putString("output", outputKey);
            tag.putString("recipe", recipeId);
            tag.putString("type", recipeType);
            tag.putString("type_name", recipeTypeName);
            tag.putLong("produced", produced);
            tag.putLong("needed", needed);
            tag.putLong("crafts", crafts);
            tag.putInt("depth", depth);
            ListTag encodedInputs = new ListTag();
            for (PlannedInput input : inputs) encodedInputs.add(input.write());
            tag.put("inputs", encodedInputs);
            return tag;
        }

        private static PlannedStep read(CompoundTag tag) {
            if (tag == null) return null;
            List<PlannedInput> inputs = new ArrayList<>();
            if (tag.contains("inputs")) {
                ListTag encodedInputs = tag.getList("inputs", 10);
                if (encodedInputs.size() > MAX_INPUTS_PER_STEP) return null;
                for (int i = 0; i < encodedInputs.size(); i++) {
                    PlannedInput input = PlannedInput.read(encodedInputs.getCompound(i));
                    if (input == null) return null;
                    inputs.add(input);
                }
            }
            PlannedStep step = new PlannedStep(tag.getString("output"), tag.getString("recipe"),
                    tag.getString("type"), tag.getString("type_name"), tag.getLong("produced"),
                    tag.getLong("needed"), tag.getLong("crafts"), tag.getInt("depth"), inputs);
            return step.validSummary() ? step : null;
        }

        private static String cleanTypeName(String value) {
            if (value == null) return "";
            String normalized = value.strip().replaceAll("[\\p{Cntrl}]", "");
            return normalized.length() <= MAX_RECIPE_TYPE_NAME_LENGTH
                    ? normalized : normalized.substring(0, MAX_RECIPE_TYPE_NAME_LENGTH);
        }
    }

    public record Job(long id, String owner, String targetKey, long amount, String status,
                      int stepCount, int completedSteps, long crafts, List<Requirement> missing,
                      long createdAt, long updatedAt, String reason, List<PlannedStep> plannedSteps,
                      Escrow escrow) {
        public Job(long id, String owner, String targetKey, long amount, String status,
                   int stepCount, int completedSteps, long crafts, List<Requirement> missing,
                   long createdAt, long updatedAt, String reason, List<PlannedStep> plannedSteps) {
            this(id, owner, targetKey, amount, status, stepCount, completedSteps, crafts,
                    missing, createdAt, updatedAt, reason, plannedSteps, null);
        }
        public Job(long id, String owner, String targetKey, long amount, String status,
                   int stepCount, int completedSteps, long crafts, List<Requirement> missing,
                   long createdAt, long updatedAt, String reason) {
            this(id, owner, targetKey, amount, status, stepCount, completedSteps, crafts,
                    missing, createdAt, updatedAt, reason, List.of(), null);
        }
        public Job {
            owner = owner == null ? "" : owner;
            targetKey = targetKey == null ? "" : targetKey;
            status = Status.from(status) == null ? Status.FAILED.key() : status;
            stepCount = Math.max(0, Math.min(MAX_STEPS, stepCount));
            completedSteps = Math.max(0, Math.min(stepCount, completedSteps));
            crafts = Math.max(0L, Math.min(MAX_CRAFTS, crafts));
            missing = missing == null ? List.of() : List.copyOf(missing.stream()
                    .filter(java.util.Objects::nonNull).limit(MAX_REQUIREMENTS).toList());
            reason = cleanReason(reason);
            plannedSteps = plannedSteps == null ? List.of() : List.copyOf(plannedSteps.stream()
                    .filter(java.util.Objects::nonNull).limit(MAX_STEPS).toList());
        }

        /** A non-empty recipe job is dispatchable only if every step was persisted. */
        public boolean hasCompletePlan() {
            return stepCount == plannedSteps.size();
        }

        /** A complete count-only plan is retained for compatibility but cannot run machines. */
        public boolean hasCompleteIngredientPlan() {
            if (!hasCompletePlan() || !validExecutionOrder(plannedSteps)) return false;
            return plannedSteps.stream().allMatch(step -> step != null
                    && !step.inputs().isEmpty() && step.inputs().stream().allMatch(PlannedInput::valid));
        }

        public boolean hasActiveEscrow() {
            return escrow != null && escrow.blocksUnsafeTransition();
        }

        public boolean terminal() {
            Status value = Status.from(status);
            return value == Status.COMPLETED || value == Status.CANCELLED || value == Status.FAILED;
        }

        private Job withState(Status next, int progress, long time, String message) {
            return new Job(id, owner, targetKey, amount, next.key(), stepCount,
                    progress < 0 ? completedSteps : progress, crafts, missing,
                    createdAt, Math.max(updatedAt, time), message == null || message.isBlank() ? reason : message,
                    plannedSteps, escrow);
        }

        private Job withEscrow(Escrow nextEscrow, long time, String message) {
            return new Job(id, owner, targetKey, amount, status, stepCount, completedSteps, crafts,
                    missing, createdAt, Math.max(updatedAt, time),
                    message == null || message.isBlank() ? reason : cleanReason(message), plannedSteps, nextEscrow);
        }

        private Job withEscrowAndState(Escrow nextEscrow, Status nextStatus, int progress,
                                       long time, String message) {
            return new Job(id, owner, targetKey, amount, nextStatus.key(), stepCount, progress, crafts,
                    missing, createdAt, Math.max(updatedAt, time),
                    message == null || message.isBlank() ? reason : cleanReason(message), plannedSteps, nextEscrow);
        }

        private CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("id", id);
            tag.putString("owner", owner);
            tag.putString("target", targetKey);
            tag.putLong("amount", amount);
            tag.putString("status", status);
            tag.putInt("steps", stepCount);
            tag.putInt("done", completedSteps);
            tag.putLong("crafts", crafts);
            tag.putLong("created", createdAt);
            tag.putLong("updated", updatedAt);
            tag.putString("reason", reason);
            ListTag requirements = new ListTag();
            for (Requirement requirement : missing) {
                CompoundTag item = new CompoundTag();
                item.putString("k", requirement.key());
                item.putLong("n", requirement.amount());
                requirements.add(item);
            }
            tag.put("missing", requirements);
            if (hasCompletePlan()) {
                ListTag plan = new ListTag();
                for (PlannedStep step : plannedSteps) plan.add(step.write());
                tag.put("plan", plan);
            }
            if (escrow != null) tag.put("escrow", escrow.write());
            return tag;
        }

        private static Job read(CompoundTag tag) {
            if (tag == null) return null;
            long id = tag.getLong("id");
            String owner = tag.getString("owner");
            String target = tag.getString("target");
            String status = tag.getString("status");
            if (id <= 0 || owner.isBlank() || target.length() > MAX_KEY_LENGTH
                    || StackKey.decode(target) == null || Status.from(status) == null) return null;
            long amount = tag.getLong("amount");
            if (amount < 1L || amount > MAX_AMOUNT) return null;
            ListTag encoded = tag.getList("missing", 10);
            List<Requirement> missing = new ArrayList<>();
            Map<String, Long> merged = new LinkedHashMap<>();
            for (int i = 0; i < encoded.size() && merged.size() < MAX_REQUIREMENTS; i++) {
                CompoundTag entry = encoded.getCompound(i);
                String key = entry.getString("k");
                long count = entry.getLong("n");
                if (key.isBlank() || key.length() > MAX_KEY_LENGTH || StackKey.decode(key) == null
                        || count < 1L || count > MAX_AMOUNT) continue;
                merged.merge(key, count, (left, right) -> Math.min(MAX_AMOUNT, left + right));
            }
            merged.forEach((key, count) -> missing.add(new Requirement(key, count)));
            List<PlannedStep> plannedSteps = new ArrayList<>();
            if (tag.contains("plan")) {
                ListTag plan = tag.getList("plan", 10);
                if (plan.size() > MAX_STEPS) return null;
                for (int i = 0; i < plan.size(); i++) {
                    PlannedStep step = PlannedStep.read(plan.getCompound(i));
                    if (step == null) return null;
                    plannedSteps.add(step);
                }
                if (tag.getInt("steps") != plannedSteps.size()) return null;
            }
            if (!plannedSteps.isEmpty() && plannedSteps.stream().allMatch(step -> !step.inputs().isEmpty())
                    && !validExecutionOrder(plannedSteps)) return null;
            Escrow escrow = tag.contains("escrow") ? Escrow.read(tag.getCompound("escrow")) : null;
            if (tag.contains("escrow") && escrow == null) return null;
            if (escrow != null) {
                int completed = Math.max(0, tag.getInt("done"));
                boolean finalized = EscrowPhase.CONSUMED.key().equals(escrow.phase());
                int expectedIndex = finalized ? completed - 1 : completed;
                if (escrow.stepIndex() >= plannedSteps.size() || escrow.stepIndex() != expectedIndex
                        || !matchesStepDemand(plannedSteps.get(escrow.stepIndex()), escrow.materials())) return null;
            }
            return new Job(id, owner, target, amount, status,
                    tag.getInt("steps"), tag.getInt("done"), tag.getLong("crafts"), missing,
                    Math.max(0L, tag.getLong("created")), Math.max(0L, tag.getLong("updated")),
                    tag.getString("reason"), plannedSteps, escrow);
        }
    }

    private static String cleanReason(String value) {
        if (value == null) return "";
        String normalized = value.strip().replaceAll("[\\p{Cntrl}]", "");
        return normalized.length() <= MAX_REASON_LENGTH ? normalized : normalized.substring(0, MAX_REASON_LENGTH);
    }
}
