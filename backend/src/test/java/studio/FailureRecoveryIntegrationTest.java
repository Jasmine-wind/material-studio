package studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.api.Trigger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class FailureRecoveryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired StudioMapper mapper;
    @Autowired Storage storage;
    @Autowired ThreadPoolTaskExecutor taskExecutor;
    @Autowired FailureSettlement failures;

    // Only the database boundary is faulted: real controller, executor, runner and MyBatis remain active.
    public static class WriteOutage implements Trigger {
        static volatile boolean blocked;
        static final AtomicInteger failedSettlements = new AtomicInteger();
        static final List<String> attemptedStages = new CopyOnWriteArrayList<>();
        @Override public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            attemptedStages.add((String) newRow[4]);
            if (blocked) {
                if ("FAILED".equals(newRow[3])) failedSettlements.incrementAndGet();
                throw new SQLException("regate transient database write outage", "45000");
            }
        }
    }
    @BeforeEach void installFault() {
        WriteOutage.blocked = false;
        WriteOutage.failedSettlements.set(0);
        WriteOutage.attemptedStages.clear();
        jdbc.execute("CREATE TRIGGER regate_write_outage BEFORE UPDATE ON tasks FOR EACH ROW CALL 'studio.FailureRecoveryIntegrationTest$WriteOutage'");
    }
    @AfterEach void removeFault() {
        WriteOutage.blocked = false;
        jdbc.execute("DROP TRIGGER IF EXISTS regate_write_outage");
    }
    String upload() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var response = mvc.perform(multipart("/api/assets").file(new MockMultipartFile("file", "regate.png", "image/png", bytes.toByteArray()))
                .header("X-Demo-User", "alice")).andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asText();
    }
    String submit(String asset, String failStage) throws Exception {
        var simulation = new java.util.LinkedHashMap<String, Object>();
        simulation.put("delayMs", 300); simulation.put("failStage", failStage);
        var response = mvc.perform(post("/api/tasks").header("X-Demo-User", "alice").contentType("application/json")
                .content(json.writeValueAsString(Map.of("templateId", "lunar", "assetIds", List.of(asset), "simulation", simulation))))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asText();
    }
    void eventually(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for automatic failure settlement");
            Thread.sleep(10);
        }
    }
    @Test void stageAndFailureWritesFailThenRecoverWithoutQueriesOrFurtherProduction() throws Exception {
        String id = submit(upload(), null);
        eventually(() -> "CHECK_MATERIAL".equals(mapper.task(id, "alice").stage));
        WriteOutage.blocked = true;
        eventually(() -> WriteOutage.failedSettlements.get() >= 2);
        assertThat(mapper.task(id, "alice").status).isEqualTo("RUNNING");
        eventually(() -> taskExecutor.getActiveCount() == 0); // Retries do not retain a production worker.
        WriteOutage.blocked = false;
        // No HTTP GET is issued until SQL proves the background settlement completed.
        eventually(() -> "FAILED".equals(mapper.task(id, "alice").status));
        mvc.perform(get("/api/tasks/" + id).header("X-Demo-User", "alice"))
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.failedStage").value("GENERATE"))
                .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("regate transient database write outage")))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());
        assertThat(WriteOutage.attemptedStages).doesNotContain("FINALIZE");
        assertThat(Files.exists(storage.temporary(id))).isFalse();
        assertThat(Files.exists(storage.result(id + ".png"))).isFalse();
    }
    @Test void retainsOriginalSimulatedFailureEvenWhenSettlementWritesAlsoFail() throws Exception {
        String id = submit(upload(), "CHECK_MATERIAL");
        eventually(() -> "CHECK_MATERIAL".equals(mapper.task(id, "alice").stage));
        WriteOutage.blocked = true;
        eventually(() -> WriteOutage.failedSettlements.get() >= 2);
        WriteOutage.blocked = false;
        eventually(() -> "FAILED".equals(mapper.task(id, "alice").status));
        var task = mapper.task(id, "alice");
        assertThat(task.failedStage).isEqualTo("CHECK_MATERIAL");
        assertThat(task.errorMessage).isEqualTo("演示：CHECK_MATERIAL 阶段指定失败");
        assertThat(WriteOutage.attemptedStages).doesNotContain("GENERATE", "FINALIZE");
    }
    @ParameterizedTest @ValueSource(strings = {"SUCCEEDED", "FAILED"})
    void delayedSettlementCannotOverwriteAnotherTerminalOutcome(String status) throws Exception {
        String id = submit(upload(), "CHECK_MATERIAL");
        eventually(() -> "CHECK_MATERIAL".equals(mapper.task(id, "alice").stage));
        WriteOutage.blocked = true;
        eventually(() -> WriteOutage.failedSettlements.get() >= 2);
        WriteOutage.blocked = false;
        jdbc.update("UPDATE tasks SET status=?,error_message='already terminal',finished_at=CURRENT_TIMESTAMP WHERE id=?", status, id);
        Thread.sleep(2200);
        var task = mapper.task(id, "alice");
        assertThat(task.status).isEqualTo(status);
        assertThat(task.errorMessage).isEqualTo("already terminal");
    }
    @Test void boundedSettlementCapacityRejectsBeforeInsertAndReleasesAfterCompletion() throws Exception {
        String asset = upload();
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Long.class);
        var reservations = new java.util.ArrayList<String>();
        try {
            for (int i = 0; i < 128; i++) {
                String id = java.util.UUID.randomUUID().toString();
                failures.reserve(id);
                reservations.add(id);
            }
            mvc.perform(post("/api/tasks").header("X-Demo-User", "alice").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("templateId", "lunar", "assetIds", List.of(asset)))))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("FAILURE_SETTLEMENT_FULL"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Long.class)).isEqualTo(before);
        } finally { reservations.forEach(failures::complete); }
        String id = submit(asset, null);
        eventually(() -> "SUCCEEDED".equals(mapper.task(id, "alice").status));
    }
    @Test void executorRejectionUsesTheSameRecoverableFailureSettlement() throws Exception {
        String asset = upload();
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(2);
        try {
            for (int i = 0; i < 2; i++) taskExecutor.execute(() -> {
                started.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 20; i++) taskExecutor.execute(() -> {});
            WriteOutage.blocked = true;
            mvc.perform(post("/api/tasks").header("X-Demo-User", "alice").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("templateId", "lunar", "assetIds", List.of(asset)))))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("QUEUE_FULL"));
            String id = jdbc.queryForObject("SELECT task_id FROM task_assets WHERE asset_id=?", String.class, asset);
            eventually(() -> WriteOutage.failedSettlements.get() >= 2);
            WriteOutage.blocked = false;
            eventually(() -> "FAILED".equals(mapper.task(id, "alice").status));
            var task = mapper.task(id, "alice");
            assertThat(task.failedStage).isEqualTo("NONE");
            assertThat(task.errorMessage).contains("服务繁忙");
            assertThat(task.finishedAt).isNotNull();
            assertThat(WriteOutage.attemptedStages).containsOnly("NONE");
        } finally { release.countDown(); }
    }
}
