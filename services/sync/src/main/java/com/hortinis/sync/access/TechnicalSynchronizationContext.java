package com.hortinis.sync.access;

import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import com.hortinis.sync.persistence.ServerInstallationPersistence;
import org.springframework.stereotype.Component;

/** Sole-scope compatibility for synthetic G2 data, replaced by S4 access resolution. */
@Component
@SynchronizationPersistenceEnabled
public class TechnicalSynchronizationContext {
  private final ServerInstallationPersistence installation;

  /** Uses only persistent installation metadata, never request-supplied scope identities. */
  public TechnicalSynchronizationContext(ServerInstallationPersistence installation) {
    this.installation = installation;
  }

  /** Reads the explicitly provisioned technical scope without an arbitrary-scope fallback. */
  public SynchronizationAccessContext current() {
    var identity = installation.technicalIdentity();
    return SynchronizationAccessContext.configured(identity.instance(), identity.scope());
  }
}
