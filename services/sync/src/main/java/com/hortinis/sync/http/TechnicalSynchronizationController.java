package com.hortinis.sync.http;

import com.hortinis.sync.protocol.OperationResult;
import com.hortinis.sync.service.TechnicalRecordSynchronizationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/sync")
@ConditionalOnProperty(
    prefix = "hortinis.sync.persistence",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TechnicalSynchronizationController {

  private final TechnicalRecordSynchronizationService synchronization;

  public TechnicalSynchronizationController(TechnicalRecordSynchronizationService synchronization) {
    this.synchronization = synchronization;
  }

  @PostMapping("/operations")
  public OperationResult submitOperation(@RequestBody JsonNode body) {
    return synchronization.submit(TechnicalOperationParser.parse(body));
  }
}
