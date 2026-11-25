package de.scc.calculator.network;

import de.scc.calculator.platform.SccMachineProcessor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts one concretely reserved planner step into the machine processor contract.
 *
 * <p>This pure conversion boundary does not reserve items, select a machine,
 * persist state, or dispatch. It rejects summary-only plans, stale escrow,
 * and mismatches between planned ingredient demand and reserved lots.</p>
 */
public final class SccMachineOperationFactory {
    private SccMachineOperationFactory() { }

    /**
     * Builds the immutable machine request before reservation so adapters can
     * validate recipe compatibility without moving any materials. This value
     * is advisory only; dispatch still requires fromReservedStep after durable
     * escrow has been recorded.
     */
    public static SccMachineProcessor.Operation forPlannedStep(
            SccCraftJobQueue.Job job, int stepIndex, String operationId) {
        if (job == null || !job.hasCompleteIngredientPlan()
                || stepIndex != job.completedSteps()
                || stepIndex < 0 || stepIndex >= job.stepCount()
                || !validOperationId(operationId)) return null;
        SccCraftJobQueue.PlannedStep step = job.plannedSteps().get(stepIndex);
        if (step == null || !step.valid() || step.crafts() < 1L || step.produced() < 1L) return null;
        Map<LotKey, Long> expected = expectedInputs(step);
        return expected == null ? null : createOperation(step, operationId, expected);
    }

    public static SccMachineProcessor.Operation fromReservedStep(
            SccCraftJobQueue.Job job, int stepIndex, String operationId) {
        if (job == null || !job.hasCompleteIngredientPlan()
                || stepIndex != job.completedSteps()
                || stepIndex < 0 || stepIndex >= job.stepCount()
                || operationId == null || operationId.isBlank()
                || operationId.length() > 256
                || !operationId.matches("[A-Za-z0-9_.:-]+")) return null;

        SccCraftJobQueue.Escrow escrow = job.escrow();
        if (escrow == null || escrow.stepIndex() != stepIndex
                || !SccCraftJobQueue.EscrowPhase.RESERVED.key().equals(escrow.phase())
                || !escrow.machineAlias().isEmpty() || !escrow.operationId().isEmpty()) return null;

        SccCraftJobQueue.PlannedStep step = job.plannedSteps().get(stepIndex);
        if (step == null || !step.valid() || step.crafts() < 1L || step.produced() < 1L) return null;

        Map<LotKey, Long> expected = new LinkedHashMap<>();
        try {
            for (SccCraftJobQueue.PlannedInput input : step.inputs()) {
                long amount = Math.multiplyExact((long) input.count(),
                        input.consumed() ? step.crafts() : 1L);
                expected.merge(new LotKey(input.itemKey(), input.consumed()), amount, Math::addExact);
            }
        } catch (ArithmeticException overflow) {
            return null;
        }

        Map<LotKey, Long> actual = new LinkedHashMap<>();
        try {
            for (SccCraftJobQueue.EscrowLot lot : escrow.materials()) {
                if (lot == null) return null;
                actual.merge(new LotKey(lot.itemKey(), lot.consumed()), lot.amount(), Math::addExact);
            }
        } catch (ArithmeticException overflow) {
            return null;
        }
        if (!expected.equals(actual)) return null;

        return createOperation(step, operationId, expected);
    }

    private static Map<LotKey, Long> expectedInputs(SccCraftJobQueue.PlannedStep step) {
        Map<LotKey, Long> expected = new LinkedHashMap<>();
        try {
            for (SccCraftJobQueue.PlannedInput input : step.inputs()) {
                long amount = Math.multiplyExact((long) input.count(),
                        input.consumed() ? step.crafts() : 1L);
                expected.merge(new LotKey(input.itemKey(), input.consumed()), amount, Math::addExact);
            }
        } catch (ArithmeticException overflow) {
            return null;
        }
        return expected.isEmpty() ? null : expected;
    }

    private static SccMachineProcessor.Operation createOperation(
            SccCraftJobQueue.PlannedStep step, String operationId, Map<LotKey, Long> expected) {
        List<SccMachineProcessor.Ingredient> ingredients = expected.entrySet().stream()
                .map(entry -> new SccMachineProcessor.Ingredient(
                        entry.getKey().itemKey(), entry.getValue(), entry.getKey().consumed()))
                .toList();
        List<SccMachineProcessor.Product> products = List.of(
                new SccMachineProcessor.Product(step.outputKey(), step.produced()));
        try {
            return new SccMachineProcessor.Operation(operationId, step.recipeId(),
                    step.recipeType(), step.crafts(), ingredients, products);
        } catch (IllegalArgumentException invalidOperation) {
            return null;
        }
    }

    private static boolean validOperationId(String operationId) {
        return operationId != null && !operationId.isBlank() && operationId.length() <= 256
                && operationId.matches("[A-Za-z0-9_.:-]+");
    }

    private record LotKey(String itemKey, boolean consumed) { }
}
