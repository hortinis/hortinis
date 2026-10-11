package com.hortinis.sync.http;

import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.access.SynchronizationAccessDeniedException;
import com.hortinis.sync.access.SynchronizationAccessResolver;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import com.hortinis.sync.protocol.AccessContractParser;
import com.hortinis.sync.protocol.AccessWireContracts;
import com.hortinis.sync.service.SynchronizationBindingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Discovery is public; protected observations always resolve current access before delegation. */
@RestController
@SynchronizationPersistenceEnabled
public class SynchronizationBindingController {
  private final SynchronizationBindingService binding;
  private final SynchronizationAccessResolver access;

  /** Delegates workflows and resolution without exposing persistence. */
  public SynchronizationBindingController(
      SynchronizationBindingService binding, SynchronizationAccessResolver access) {
    this.binding = binding;
    this.access = access;
  }

  /** Public discovery does not select a synchronization scope. */
  @GetMapping("/api/v1/server/capabilities")
  public SynchronizationBindingWire.CapabilitiesBody capabilities() {
    return SynchronizationBindingWire.capabilities(binding.capabilities());
  }

  /** Resolves authority before observing population or issuing protocol state. */
  @GetMapping("/api/v1/sync/bootstrap")
  public SynchronizationBindingWire.BootstrapBody bootstrap(HttpServletRequest request) {
    var context = requireAccess();
    var observation =
        request.getHeader("Sync-Expectation") == null
            ? binding.bootstrap(context)
            : binding.reobserve(context, SynchronizationExpectationHeader.require(request));
    return SynchronizationBindingWire.bootstrap(observation);
  }

  /** Resolves authority before closed validation and transactional confirmation. */
  @PostMapping("/api/v1/sync/bindings/confirm")
  public SynchronizationBindingWire.BootstrapBody confirm(@RequestBody JsonNode body) {
    var context = requireAccess();
    var confirmation =
        (AccessWireContracts.Confirmation)
            AccessContractParser.parse("ConfirmEmptyBindingRequest", body);
    return SynchronizationBindingWire.bootstrap(
        binding.confirm(context, confirmation.expectation()));
  }

  private SynchronizationAccessContext requireAccess() {
    var context = access.resolve();
    if (context == null) {
      throw new SynchronizationAccessDeniedException();
    }
    return context;
  }
}
