package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 跨父子 Agent 共用的可信运行边界，业务日期时钟不能改变实际截止时间。 */
public interface RunControl {
    String runId();

    int revision();

    AccessPolicy.Identity identity();

    void checkActive();

    Duration remaining();

    void reserveModel(int estimatedTokens);

    default void reserveModelStep(String stepKey, int estimatedTokens, int maxAttempts) {
        synchronized (this) {
            String saved = checkpoint(stepKey);
            int attempts = saved == null ? 0 : Integer.parseInt(saved);
            if (attempts >= maxAttempts) throw new BudgetExceeded("模型步骤尝试预算已耗尽");
            reserveModel(estimatedTokens);
            checkpoint(stepKey, Integer.toString(attempts + 1));
        }
    }

    String checkpoint(String key);

    void checkpoint(String key, String value);

    String drainInput();

    void event(String event);

    int modelCalls();

    class Cancelled extends RuntimeException {
        public Cancelled(String message) {
            super(message);
        }
    }

    class BudgetExceeded extends RuntimeException {
        public BudgetExceeded(String message) {
            super(message);
        }
    }

    class ContextLimit extends RuntimeException {
        public ContextLimit(String message) {
            super(message);
        }
    }

    class DeadlineExceeded extends RuntimeException {
        public DeadlineExceeded() {
            super("任务已超过截止时间");
        }
    }

    static RunControl local(Limits limits) {
        return new RunControl() {
            private final String id = UUID.randomUUID().toString();
            private final long started = System.nanoTime();
            private final AtomicInteger calls = new AtomicInteger();
            private final Map<String, String> values = new ConcurrentHashMap<>();

            public String runId() {
                return id;
            }

            public int revision() {
                return 1;
            }

            public AccessPolicy.Identity identity() {
                return AccessPolicy.Identity.local();
            }

            public void checkActive() {
                if (remaining().isZero()) throw new DeadlineExceeded();
            }

            public Duration remaining() {
                return Duration.ofNanos(
                        Math.max(0, limits.timeout().toNanos() - (System.nanoTime() - started)));
            }

            public synchronized void reserveModel(int tokens) {
                checkActive();
                if (tokens < 0) throw new IllegalArgumentException("Token 预留不能为负");
                if (calls.get() >= limits.modelCalls()) throw new BudgetExceeded("模型调用预算已耗尽");
                calls.incrementAndGet();
            }

            public String checkpoint(String key) {
                return values.get(key);
            }

            public void checkpoint(String key, String value) {
                checkActive();
                if (value == null) values.remove(key);
                else values.put(key, value);
            }

            public String drainInput() {
                checkActive();
                return null;
            }

            public void event(String event) {}

            public int modelCalls() {
                return calls.get();
            }
        };
    }
}
