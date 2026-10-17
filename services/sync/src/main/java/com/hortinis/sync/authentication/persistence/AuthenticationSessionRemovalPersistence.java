package com.hortinis.sync.authentication.persistence;

import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Account-wide native revocation participates in the caller's account mutation transaction. */
@Repository
@SynchronizationPersistenceEnabled
public class AuthenticationSessionRemovalPersistence {
  private final AuthenticationNativeSessions sessions;

  /** Native session persistence owns removal; no application authority table is maintained. */
  public AuthenticationSessionRemovalPersistence(AuthenticationNativeSessions sessions) {
    this.sessions = sessions;
  }

  /** Caller holds the account lock and advances its security version in the same transaction. */
  public void removeAll(UUID account) {
    sessions.removeAll(account);
  }
}
