package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dataobject.TaskDO;
import dev.garden.demo.supervisor.repo.Database;
import dev.garden.demo.supervisor.repo.JsonCodec;
import dev.garden.demo.supervisor.repo.TaskRepository;

import java.util.List;

/**
 * 测试用任务仓储持有者。
 *
 * <p>生产 TaskRepository 只接收共享 Database，不负责关闭连接池。测试需要一个可关闭的
 * 资源句柄，因此由本类打开 Database、构造仓储并转发测试用到的方法。
 */
final class TestRepository implements AutoCloseable {
    private final Database database;
    private final TaskRepository repository;

    TestRepository(String url) {
        this.database = TestServices.database(url);
        this.repository = TestServices.taskRepository(database);
    }

    TaskRepository repository() {
        return repository;
    }

    Database database() {
        return database;
    }

    JsonCodec codec() {
        return repository.codec();
    }

    TaskDO get(String id) {
        return repository.get(id);
    }

    List<TaskDO> all() {
        return repository.all();
    }

    void save(TaskDO task) {
        repository.save(task);
    }

    void reserve(String key, int calls, long tokens, int maxCalls, long maxTokens) {
        repository.reserve(key, calls, tokens, maxCalls, maxTokens);
    }

    String encode(Object value) {
        return repository.encode(value);
    }

    @Override
    public void close() {
        database.close();
    }
}
