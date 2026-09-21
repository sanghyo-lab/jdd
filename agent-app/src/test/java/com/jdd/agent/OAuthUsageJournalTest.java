package com.jdd.agent;

import com.jdd.agent.domain.ModelUsage;
import com.jdd.agent.infra.JdbcOAuthCallJournal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"jdd.agent.worker.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:oauth-journal;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.create-schemas=true"})
class OAuthUsageJournalTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Test void observedAndUnknownOauthUsageNeverMutatePaidBudgetOrInventZeroCost() {
        var journal = new JdbcOAuthCallJournal(jdbc, json);
        journal.start("known", "synthetic", 1, "workspace-model");
        var usage = new ModelUsage(100L, 40L, 20L, null, 10L);
        journal.finish("known", "actual-model", usage, "HTTP_200_completed", 42);
        journal.finish("known", "actual-model", usage, "HTTP_200_completed", 42);
        journal.start("unknown", "synthetic", 2, "workspace-model");
        journal.finish("unknown", null, null, "TRANSPORT_FAILURE_USAGE_UNKNOWN", 45);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.oauth_model_calls", Integer.class)).isEqualTo(2);
        var observed = json.readValue(jdbc.queryForObject("SELECT usage_json FROM agent.oauth_model_calls WHERE call_id='known'", String.class), ModelUsage.class);
        assertThat(observed).isEqualTo(usage);
        assertThat(jdbc.queryForObject("SELECT usage_json FROM agent.oauth_model_calls WHERE call_id='unknown'", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.model_calls", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.demo_budget", Integer.class)).isZero();
    }
}
