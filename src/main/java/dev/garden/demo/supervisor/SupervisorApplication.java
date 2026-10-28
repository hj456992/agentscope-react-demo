package dev.garden.demo.supervisor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** HTTP 服务入口。只扫描当前 package，不加载旁边的独立学习示例。 */
@SpringBootApplication
public class SupervisorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SupervisorApplication.class, args);
    }
}
