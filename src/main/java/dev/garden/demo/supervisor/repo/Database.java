package dev.garden.demo.supervisor.repo;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 所有仓储共享的数据库入口：HikariCP 连接池 + MyBatis 会话。
 *
 * <p>连接地址、账号、密码和连接池大小全部由配置传入，运行期使用共享关系型数据库。
 * 连接池保持自动提交；跨语句的一致性由 service 层的声明式事务提供。
 *
 * <p>MyBatis 通过数据库产品名选择方言：PostgreSQL 返回 {@code postgres}，其余返回
 * {@code h2}。mapper XML 中带 {@code databaseId="postgres"} 的语句只在 PostgreSQL 上生效，
 * 不带 databaseId 的语句作为 H2 版本，供本地回归测试使用。
 */
public final class Database implements AutoCloseable {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final HikariDataSource pool;
    private final SqlSessionFactory factory;
    private final SqlSessionTemplate template;

    private Database(String url, String username, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(normalize(url));
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(1);
        config.setPoolName("supervisor-" + SEQUENCE.incrementAndGet());
        if (isH2(url)) {
            // H2 文件库：预算与检查点在提交返回前刷盘，避免进程骤停时回退已使用额度。
            config.setConnectionInitSql("SET WRITE_DELAY 0");
        }
        this.pool = new HikariDataSource(config);
        try {
            SqlSessionFactoryBean builder = new SqlSessionFactoryBean();
            builder.setDataSource(pool);
            builder.setTransactionFactory(new SpringManagedTransactionFactory());
            builder.setMapperLocations(
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath*:mapper/*.xml"));
            builder.setDatabaseIdProvider(new ProductNameDatabaseIdProvider());
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

    /** 本地回归测试入口：沿用 H2 的固定账号。 */
    public static Database open(String url) {
        return new Database(url, "sa", "", 16);
    }

    public static Database open(String url, String username, String password, int poolSize) {
        return new Database(url, username, password, poolSize);
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

    /** 按 JDBC 产品名选择 mapper 方言；识别失败时按 H2 处理。 */
    private static final class ProductNameDatabaseIdProvider implements DatabaseIdProvider {
        @Override
        public String getDatabaseId(DataSource dataSource) throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                String product = connection.getMetaData().getDatabaseProductName();
                if (product != null
                        && product.toLowerCase(Locale.ROOT).contains("postgres")) {
                    return "postgres";
                }
                return "h2";
            }
        }
    }

    private static boolean isH2(String url) {
        return url != null && url.startsWith("jdbc:h2:");
    }

    private static String normalize(String url) {
        if (url == null || !url.startsWith("jdbc:h2:mem:") || url.contains("DB_CLOSE_DELAY")) {
            return url;
        }
        return url + (url.endsWith(";") ? "" : ";") + "DB_CLOSE_DELAY=-1";
    }
}
