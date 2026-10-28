package dev.garden.demo.supervisor;

import java.util.Map;

/** 单独 JVM 在下游提交后强制退出，留下真实未完成账本记录。 */
public final class SideEffectCrashProcess {
    public static void main(String[] args) throws Exception {
        try (var ledger = new SideEffectLedger(args[0]);
                var downstream = new SideEffectLedgerTest.DurableDownstream(args[1])) {
            downstream.crash = true;
            ledger.execute(
                    SideEffectLedgerTest.ALICE,
                    "write_example",
                    "crash",
                    Map.of("amount", 8),
                    downstream);
        }
    }
}
