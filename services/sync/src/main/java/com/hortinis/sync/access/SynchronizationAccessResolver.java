package com.hortinis.sync.access;

/**
 * Resolves trusted authority for each data request without accepting browser-selected identities.
 */
@FunctionalInterface
public interface SynchronizationAccessResolver {

  /** Returns immutable current authority or denies access before workflow execution. */
  SynchronizationAccessContext resolve();
}
