package collector.bu;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmartFinanceApplicationTests {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    /**
     * 验证公开健康接口返回 HTTP 200 和 status=UP。
     */
    @Test void healthEndpointReturnsUp() {
        var response = rest.getForEntity("/api/health", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    /**
     * 验证数据库连接可用，且 Spring Batch 所需表已初始化。
     */
    @Test void databaseAndBatchSchemaAreAvailable() {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE", Integer.class)).isZero();
    }
}
