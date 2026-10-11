package com.hortinis.sync.http;

import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.access.SynchronizationAccessDeniedException;
import com.hortinis.sync.access.SynchronizationAccessResolver;
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
  private final SynchronizationAccessResolver access;

  /** Delegates access resolution and synchronization; no persistence is exposed here. */
  public TechnicalSynchronizationController(
      TechnicalRecordSynchronizationService synchronization, SynchronizationAccessResolver access) {
    this.synchronization = synchronization;
    this.access = access;
  }

  /** Resolves authority before parsing or accepting an operation. */
  @PostMapping("/operations")
  public TechnicalSynchronizationWire.ResultBody submitOperation(@RequestBody JsonNode body) {
    var context = requireAccess();
    return TechnicalSynchronizationWire.toResult(
        synchronization.submit(context, TechnicalOperationParser.parse(body)));
  }

  /** Resolves authority before looking up changes or decoding a cursor. */
  @GetMapping("/changes")
  public TechnicalSynchronizationWire.ChangePageBody pullChanges(
      @RequestParam(required = false) String cursor) {
    return TechnicalSynchronizationWire.toPage(synchronization.pull(requireAccess(), cursor));
  }

  private SynchronizationAccessContext requireAccess() {
    var context = access.resolve();
    if (context == null) {
      throw new SynchronizationAccessDeniedException();
    }
    return context;
  }
}
