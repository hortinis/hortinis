package com.hortinis.sync.http;

import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import com.hortinis.sync.service.TechnicalRecordSynchronizationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/sync")
@SynchronizationPersistenceEnabled
public class TechnicalSynchronizationController {

  private final TechnicalRecordSynchronizationService synchronization;

  public TechnicalSynchronizationController(TechnicalRecordSynchronizationService synchronization) {
    this.synchronization = synchronization;
  }

  @PostMapping("/operations")
  public TechnicalSynchronizationWire.ResultBody submitOperation(@RequestBody JsonNode body) {
    return TechnicalSynchronizationWire.toResult(
        synchronization.submit(TechnicalOperationParser.parse(body)));
  }

  @GetMapping("/changes")
  public TechnicalSynchronizationWire.ChangePageBody pullChanges(
      @RequestParam(required = false) String cursor) {
    return TechnicalSynchronizationWire.toPage(synchronization.pull(cursor));
  }
}
