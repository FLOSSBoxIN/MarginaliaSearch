package nu.marginalia.mqsm;

import com.google.gson.GsonBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import nu.marginalia.mq.MqMessageRow;
import nu.marginalia.mq.MqTestUtil;
import nu.marginalia.mq.persistence.MqPersistence;
import nu.marginalia.mqsm.graph.GraphState;
import nu.marginalia.mqsm.graph.AbstractStateGraph;
import nu.marginalia.mqsm.graph.ResumeBehavior;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("slow")
@Testcontainers
public class StateMachineErrorTest {
    @Container
    static MariaDBContainer<?> mariaDBContainer = new MariaDBContainer<>("mariadb")
            .withDatabaseName("WMSA_prod")
            .withUsername("wmsa")
            .withPassword("wmsa")
            .withInitScript("sql/current/11-message-queue.sql")
            .withNetworkAliases("mariadb");

    static HikariDataSource dataSource;
    static MqPersistence persistence;
    private String inboxId;

    @BeforeEach
    public void setUp() {
        inboxId = UUID.randomUUID().toString();
    }
    @BeforeAll
    public static void setUpAll() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mariaDBContainer.getJdbcUrl());
        config.setUsername("wmsa");
        config.setPassword("wmsa");

        dataSource = new HikariDataSource(config);
        persistence = new MqPersistence(dataSource);
    }

    @AfterAll
    public static void tearDownAll() {
        dataSource.close();
    }

    public static class ErrorHurdles extends AbstractStateGraph {

        public ErrorHurdles(StateFactory stateFactory) {
            super(stateFactory);
        }

        @GraphState(name = "INITIAL", next = "FAILING")
        public void initial() {

        }
        @GraphState(name = "FAILING", next = "OK", resume = ResumeBehavior.RETRY)
        public void resumable() {
            throw new RuntimeException("Boom!");
        }
        @GraphState(name = "OK", next = "END")
        public void ok() {

        }

    }

    @Test
    public void smResumeResumableFromNew() throws Exception {
        var stateFactory = new StateFactory(new GsonBuilder().create());
        var sm = new StateMachine(persistence, inboxId, UUID.randomUUID(), new ErrorHurdles(stateFactory));

        sm.init();

        sm.join();
        sm.stop();

        List<String> states = MqTestUtil.getMessages(dataSource, inboxId)
                .stream()
                .peek(System.out::println)
                .map(MqMessageRow::function)
                .toList();

        assertEquals(List.of("INITIAL", "FAILING", "ERROR"), states);
    }

}
