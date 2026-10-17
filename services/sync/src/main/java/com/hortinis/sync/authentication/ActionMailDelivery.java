package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.ActionTokenPersistence.IssuedToken;

/** Replaceable synchronous transport; raw mail is transient and never queued or retried. */
@FunctionalInterface
public interface ActionMailDelivery {
  /** Returns only a fixed outcome; implementations never expose provider diagnostics. */
  boolean deliver(IssuedToken token);
}
