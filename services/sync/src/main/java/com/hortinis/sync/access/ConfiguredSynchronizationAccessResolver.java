package com.hortinis.sync.access;

import com.hortinis.sync.persistence.ServerInstallationPersistence;

/** Explicit no-auth resolution from current persisted installation configuration. */
public final class ConfiguredSynchronizationAccessResolver
    implements SynchronizationAccessResolver {
  private final ServerInstallationPersistence installation;

  /** Uses only controlled installation state; no identity comes from the HTTP request. */
  public ConfiguredSynchronizationAccessResolver(ServerInstallationPersistence installation) {
    this.installation = installation;
  }

  @Override
  public SynchronizationAccessContext resolve() {
    var identity = installation.technicalIdentity();
    return SynchronizationAccessContext.configured(identity.instance(), identity.scope());
  }
}
