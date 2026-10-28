package dev.garden.demo.supervisor.repo;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 所有仓储共享的数据库入口：HikariCP 连接池 + MyBatis 会话。
 *
 * <p>连接池保持自动提交；跨语句的一致性由 service 层的声明式事务提供。H2 内存库追加
 * DB_CLOSE_DELAY=-1，避免连接全部归还后数据库被回收。
 */
public final class Database implements AutoCloseable {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final HikariDataSource pool;
    private final SqlSessionFactory factory;
    private final SqlSessionTemplate template;

    private Database(String url) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(normalize(url));
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(16);
        config.setMinimumIdle(1);
        config.setPoolName("supervisor-" + SEQUENCE.incrementAndGet());
        // 预算与检查点在提交返回前刷盘，避免进程骤停时回退已使用额度。
        config.setConnectionInitSql("SET WRITE_DELAY 0");
        this.pool = new HikariDataSource(config);
        try {
            SqlSessionFactoryBean builder = new SqlSessionFactoryBean();
            builder.setDataSource(pool);
            builder.setTransactionFactory(new SpringManagedTransactionFactory());
            builder.setMapperLocations(
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath*:mapper/*.xml"));
            org.apache.ibatis.session.Configuration configuration =
                    new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            builder.setConfiguration(configuration);
            this.factory = builder.getObject();
            this.template = new SqlSessionTemplate(factory);
        } catch (Exception error) {
            pool.close();
            throw new IllegalStateException("数据库初始化失败", error);
        }
    }

    public static Database open(String url) {
        return new Database(url);
    }

    public DataSource dataSource() {
        return pool;
    }

    public SqlSessionFactory factory() {
        return factory;
    }

    public SqlSessionTemplate template() {
        return template;
    }

    /** 获取仓储 mapper；XML mapper 与测试用注解 mapper 都通过这里注册。 */
    public <T> T mapper(Class<T> type) {
        org.apache.ibatis.session.Configuration configuration = factory.getConfiguration();
        synchronized (configuration) {
            if (!configuration.hasMapper(type)) {
                configuration.addMapper(type);
            }
        }
        return template.getMapper(type);
    }

    @Override
    public void close() {
        pool.close();
    }

    private static String normalize(String url) {
        if (url == null || !url.startsWith("jdbc:h2:mem:") || url.contains("DB_CLOSE_DELAY")) {
            return url;
        }
        return url + (url.endsWith(";") ? "" : ";") + "DB_CLOSE_DELAY=-1";
    }
}
