package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.repo.TaskRepository;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 测试夹具：验证仓储语句在多语句事务中回滚；生产代码不使用编程式事务。 */
final class TestDatabase {
    private TestDatabase() {}

    static void inTransaction(TaskRepository repository, Runnable work) {
        new TransactionTemplate(
                        new DataSourceTransactionManager(repository.database().dataSource()))
                .execute(
                        status -> {
                            work.run();
                            return null;
                        });
    }

    static TestSqlMapper sql(TaskRepository repository) {
        return repository.database().mapper(TestSqlMapper.class);
    }

    static void inTransaction(TestRepository repository, Runnable work) {
        inTransaction(repository.repository(), work);
    }

    static TestSqlMapper sql(TestRepository repository) {
        return sql(repository.repository());
    }
}
