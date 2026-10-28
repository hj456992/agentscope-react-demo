package dev.garden.demo.supervisor.service;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 业务恢复适配：独立于任务生命周期核对外部写入，不是 Codex 会话恢复的保证。 一个有界工作线程顺序查询；下游忽略中断时仍占用原线程，不创建替代线程。 */
public final class SideEffectReconciler implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(SideEffectReconciler.class.getName());
    private final SideEffectLedger ledger;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread workerThread;
    private final ScheduledExecutorService worker;
    private final ScheduledFuture<?> scheduled;

    public SideEffectReconciler(
            SideEffectLedger ledger, Map<String, SideEffectLedger.Downstream> adapters) {
        this.ledger = Objects.requireNonNull(ledger);
        Map<String, SideEffectLedger.Downstream> registered = Map.copyOf(adapters);
        if (registered.isEmpty()) {
            worker = null;
            scheduled = null;
            return;
        }
        worker =
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            Thread thread = new Thread(task, "side-effect-reconciler");
                            thread.setDaemon(true);
                            workerThread = thread;
                            return thread;
                        });
        scheduled =
                worker.scheduleWithFixedDelay(
                        () -> {
                            if (closed.get()) {
                                return;
                            }
                            try {
                                ledger.reconcile(registered, 20);
                            } catch (RuntimeException failure) {
                                // 周期任务不能因为一次存储故障永久停止；错误细节不写入面向用户日志。
                                LOG.log(
                                        System.Logger.Level.WARNING,
                                        "副作用状态核对失败，已保留操作记录，等待下一次核对");
                            }
                        },
                        0,
                        1,
                        TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || worker == null) {
            return;
        }
        scheduled.cancel(false);
        worker.shutdown();
        // 在短数据库临界区外发出一次中断，避免打断 H2 文件写入。
        ledger.interruptReconciliation(workerThread);
    }
}
