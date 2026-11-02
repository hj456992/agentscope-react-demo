package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.param.AgentReleaseParam;
import dev.garden.demo.supervisor.pojo.param.AgentRollbackParam;
import dev.garden.demo.supervisor.repo.Database;
import dev.garden.demo.supervisor.repo.GovernanceRepository;
import dev.garden.demo.supervisor.service.AgentCatalogService;
import dev.garden.demo.supervisor.service.RegistrySecurityService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;

/** Agent 目录：种子、启动验签、权限过滤、发布和回滚审计。 */
class AgentCatalogServiceTest {
    private static final IdentityDTO WEATHER_ONLY =
            new IdentityDTO("tenant", "weather-user", Set.of("query_weather"));

    @TempDir Path directory;
    private Database database;
    private GovernanceRepository repository;
    private AgentCatalogService agents;

    @BeforeEach
    void setUp() {
        database = TestServices.database("jdbc:h2:file:" + directory.resolve("agents"));
        repository = new GovernanceRepository(database);
        agents =
                new AgentCatalogService(
                        repository, new RegistrySecurityService(), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void seedContainsTwentyFourEntriesAndStartupVerificationRecordsTamperedManifest() {
        assertEquals(24, agents.agents().size());
        assertTrue(agents.agents().stream().allMatch(agent -> agent.signatureVerified()
                || agent.agentId().equals("travel.emergency")));
        boolean tampered =
                agents.auditLog(200).stream()
                        .anyMatch(
                                row ->
                                        row.subject().equals("travel.emergency@1")
                                                && row.outcome().equals("FAIL")
                                                && row.detail().contains("签名校验失败"));
        assertTrue(tampered);
    }

    @Test
    void catalogReportsSchemaBudgetAndFiltersByIdentity() {
        var catalog = agents.catalog(WEATHER_ONLY);
        assertEquals(24, catalog.total());
        assertEquals(4, catalog.executable());
        assertTrue(catalog.filteredByPermission() > 0);
        assertTrue(catalog.candidatesAfterPermission() < catalog.total());
        assertTrue(catalog.schemaBytes() > catalog.schemaBudgetBytes());
        assertTrue(catalog.overBudget());
        assertTrue(catalog.events().stream().anyMatch(event -> event.contains("SCHEMA_BUDGET_EXCEEDED")));
        assertFalse(catalog.entries().isEmpty());
    }

    @Test
    void releaseAndRollbackAreRecordedInReleaseLogAndAudit() {
        var released =
                agents.release(new AgentReleaseParam("travel.weather", "1", 50, "platform-admin"));
        assertEquals("RELEASE", released.action());
        assertEquals(50, released.trafficPercent());

        var releases = agents.releases("travel.weather");
        assertTrue(releases.stream().anyMatch(row -> row.action().equals("RELEASE")
                && row.trafficPercent() == 50));

        var rolledBack =
                agents.rollback(new AgentRollbackParam("travel.weather", "1", "platform-admin"));
        assertEquals("ROLLBACK", rolledBack.action());
        assertEquals(100, rolledBack.trafficPercent());
        assertTrue(agents.auditLog(200).stream()
                .anyMatch(row -> row.action().equals("ROLLBACK")
                        && row.subject().equals("travel.weather@1")));
    }

    @Test
    void releaseRejectsUnknownVersion() {
        ResponseStatusException missing =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                agents.release(
                                        new AgentReleaseParam(
                                                "travel.weather", "9", 50, "platform-admin")));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }
}
