package dev.garden.demo.supervisor;

import java.util.Map;

/** 独立 JVM 校验 Map 顺序不会改变业务步骤号。 */
public final class FingerprintProcess {
    public static void main(String[] args) {
        System.out.println(RunContext.fingerprint(Map.of("z", 3, "a", Map.of("x", 1, "y", 2))));
    }
}
