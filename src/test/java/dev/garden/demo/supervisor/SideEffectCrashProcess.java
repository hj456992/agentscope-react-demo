package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.pojo.param.*;
import dev.garden.demo.supervisor.pojo.req.*;
import dev.garden.demo.supervisor.pojo.res.*;
import dev.garden.demo.supervisor.pojo.dataobject.*;
import dev.garden.demo.supervisor.service.*;
import dev.garden.demo.supervisor.repo.*;
import dev.garden.demo.supervisor.config.*;

import java.util.Map;

/** 单独 JVM 在下游提交后强制退出，留下真实未完成账本记录。 */
public final class SideEffectCrashProcess {
    public static void main(String[] args) throws Exception {
        try (var ledger = new TestLedger(args[0]);
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
