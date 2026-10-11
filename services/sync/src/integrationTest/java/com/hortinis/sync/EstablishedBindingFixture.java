package com.hortinis.sync;

import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.service.BoundSynchronizationService;
import com.hortinis.sync.service.SynchronizationBindingService;

/** G2 fixtures begin after an authorized binding; S6 tests exercise the handshake itself. */
final class EstablishedBindingFixture {
  private EstablishedBindingFixture() {}

  static String establish(
      SynchronizationBindingService binding,
      BoundSynchronizationService exchange,
      SynchronizationAccessContext context) {
    var observation = binding.bootstrap(context);
    if (observation.empty()) {
      binding.confirm(context, observation.expectation());
    }
    exchange.pull(context, observation.expectation(), null);
    return observation.expectation();
  }
}
