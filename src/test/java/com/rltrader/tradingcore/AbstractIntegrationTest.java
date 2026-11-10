package com.rltrader.tradingcore;

import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.sql.DriverManager;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")  // StartupRunner(!test & !smoke) 배제
public abstract class AbstractIntegrationTest {
    private record Database(String url, String user, String password) {}
    private static Database database;

    @DynamicPropertySource
    static synchronized void datasource(DynamicPropertyRegistry properties) throws Exception {
        if (database == null) {
            String base = System.getenv("RL_TEST_PG_BASE_URL");
            if (base == null || base.isBlank()) {
                // Keep the normal Docker-backed developer/CI workflow.
                PostgreSQLContainer container = new PostgreSQLContainer("postgres:16");
                container.start();
                database = new Database(container.getJdbcUrl(),container.getUsername(),container.getPassword());
                Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
            } else {
                URI uri = URI.create(base.substring("jdbc:".length()));
                if (!java.util.List.of("127.0.0.1","localhost","[::1]","::1").contains(uri.getHost()) || uri.getQuery()!=null)
                    throw new IllegalArgumentException("external test PostgreSQL must be loopback without query parameters");
                String user = System.getenv().getOrDefault("RL_TEST_PG_USER","tradingcore");
                String password = System.getenv().getOrDefault("RL_TEST_PG_PASSWORD","");
                String name = "rl_test_" + ProcessHandle.current().pid();
                try (var connection=DriverManager.getConnection(base,user,password);var statement=connection.createStatement()) {
                    statement.execute("CREATE DATABASE \""+name+"\"");
                }
                database = new Database(base.substring(0,base.lastIndexOf('/')+1)+name,user,password);
                Runtime.getRuntime().addShutdownHook(new Thread(()->{
                    try (var connection=DriverManager.getConnection(base,user,password);var statement=connection.createStatement()) {
                        statement.execute("DROP DATABASE \""+name+"\" WITH (FORCE)");
                    } catch (java.sql.SQLException ignored) { /* isolated test DB only */ }
                }));
            }
        }
        properties.add("spring.datasource.url",database::url);
        properties.add("spring.datasource.username",database::user);
        properties.add("spring.datasource.password",database::password);
    }
}
