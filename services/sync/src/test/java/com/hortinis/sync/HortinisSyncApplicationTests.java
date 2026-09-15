package com.hortinis.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class HortinisSyncApplicationTests {

  @Autowired private MockMvc mockMvc;

  @Test
  void contextLoads() {}

  @Test
  void healthEndpointsExposeOnlyStatus() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(
            content().json("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}", true));
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}", true));
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}", true));
    mockMvc.perform(get("/actuator/info")).andExpect(status().isNotFound());
  }

  @Test
  void requestLogsExcludeSensitiveRequestData(CapturedOutput output) throws Exception {
    String canary = "c6-sensitive-" + UUID.randomUUID();
    mockMvc
        .perform(
            get("/actuator/health")
                .queryParam("secret", canary)
                .header("Authorization", "Bearer " + canary)
                .header("X-Secret", canary)
                .content(canary))
        .andExpect(status().isOk());

    String eventLine =
        output
            .getOut()
            .lines()
            .filter(line -> line.contains("\"event\":\"request_completed\""))
            .reduce((first, second) -> second)
            .orElseThrow();
    JsonNode event = JsonMapper.builder().build().readTree(eventLine);
    assertThat(event.propertyNames())
        .contains("request_id", "trace_id", "event", "method", "route", "status", "duration_ms")
        .doesNotContain(
            "headers", "query", "query_string", "request_body", "response_body", "cookies");
    assertThat(event.get("request_id").textValue()).isNotBlank();
    assertThat(event.get("trace_id").textValue()).isNotBlank();
    assertThat(event.path("route").textValue()).isEqualTo("/actuator/health");
    assertThat(output.getOut()).doesNotContain(canary);
    assertThat(output.getOut()).doesNotContain("Authorization");
    assertThat(output.getOut()).doesNotContain("X-Secret");
    assertThat(output.getOut()).doesNotContain("secret=");
    assertThat(MDC.get("request_id")).isNull();
    assertThat(MDC.get("trace_id")).isNull();
  }
}
