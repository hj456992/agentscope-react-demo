package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.dto.SideEffectResultDTO;
import dev.garden.demo.supervisor.repo.Database;
import dev.garden.demo.supervisor.service.SideEffectLedger;

import java.time.Clock;
import java.util.Map;

/**
 * 测试用副作用账本持有者。
 *
 * <p>生产 SideEffectLedger 只接收共享 SideEffectRepository，不负责关闭连接池。测试需要
 * 可关闭资源句柄，因此由本类打开 Database 并转发测试用到的方法。
 */
final class TestLedger implements AutoCloseable {
    private final Database database;
    private final SideEffectLedger ledger;

    TestLedger(String url) {
        this(url, Clock.systemUTC());
    }

    TestLedger(String url, Clock clock) {
        this.database = TestServices.database(url);
        this.ledger = TestServices.sideEffectLedger(database, clock);
    }

    SideEffectLedger ledger() {
        return ledger;
    }

    SideEffectResultDTO execute(
            IdentityDTO identity,
            String tool,
            String operationId,
            Map<String, ?> parameters,
            SideEffectLedger.Downstream downstream) {
        return ledger.execute(identity, tool, operationId, parameters, downstream);
    }

    SideEffectResultDTO read(IdentityDTO identity, String tool, String operationId) {
        return ledger.read(identity, tool, operationId);
    }

    int reconcile(Map<String, SideEffectLedger.Downstream> downstreams, int batchLimit) {
        return ledger.reconcile(downstreams, batchLimit);
    }

    @Override
    public void close() {
        database.close();
    }
}
