package de.scc.calculator.platform.storage;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

/**
 * Abre uma transacao segura: aninhada se ja houver uma aberta na thread, externa caso contrario.
 * Transaction.openOuter() lanca excecao quando ja existe transacao aberta; este helper evita isso.
 * (Verificar: Transaction.getCurrentUnsafe() na versao exata da Fabric API.)
 */
public final class SccTransactions {
    private SccTransactions() {
    }

    public static Transaction open() {
        TransactionContext current = Transaction.getCurrentUnsafe();
        return current == null ? Transaction.openOuter() : Transaction.openNested(current);
    }
}
