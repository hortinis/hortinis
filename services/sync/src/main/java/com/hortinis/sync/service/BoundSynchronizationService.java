package com.hortinis.sync.service;

import com.hortinis.sync.access.BindingPreconditionFailedException;
import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import com.hortinis.sync.persistence.SynchronizationExpectationPersistence;
import com.hortinis.sync.persistence.SynchronizationScopePersistence;
import com.hortinis.sync.protocol.ChangePage;
import com.hortinis.sync.protocol.OperationResult;
import com.hortinis.sync.protocol.TechnicalRecordOperation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admission, first exchange and publication share a single locked transaction. */
@Service
@SynchronizationPersistenceEnabled
public class BoundSynchronizationService {
  private static final String ESTABLISHED = "established";
  private static final String CONFIRMED = "confirmed";
  private final TechnicalRecordSynchronizationService synchronization;
  private final SynchronizationScopePersistence scopes;
  private final SynchronizationExpectationPersistence expectations;

  /** Requires admission and synchronization adapters within the same transaction manager. */
  public BoundSynchronizationService(
      TechnicalRecordSynchronizationService synchronization,
      SynchronizationScopePersistence scopes,
      SynchronizationExpectationPersistence expectations) {
    this.synchronization = synchronization;
    this.scopes = scopes;
    this.expectations = expectations;
  }

  /**
   * Header provenance is checked before receipt or record lookup and is never canonical operation
   * data.
   */
  @Transactional
  public OperationResult submit(
      SynchronizationAccessContext context,
      String expectation,
      TechnicalRecordOperation operation) {
    admit(context, expectation);
    return synchronization.submit(context, operation);
  }

  /**
   * Initial empty pull establishes an empty local client's channel at the same publication
   * boundary.
   */
  @Transactional
  public ChangePage pull(SynchronizationAccessContext context, String expectation, String cursor) {
    admit(context, expectation);
    return synchronization.pull(context, cursor);
  }

  private void admit(SynchronizationAccessContext context, String expectation) {
    scopes.lockForAcceptance(context);
    String phase = expectations.require(context, expectation);
    if (ESTABLISHED.equals(phase)) {
      return;
    }
    if (!CONFIRMED.equals(phase) || !expectations.isEmpty(context)) {
      throw new BindingPreconditionFailedException();
    }
    // Rolled back together if the operation/pull fails, including protocol validation failures.
    expectations.phase(context, expectation, ESTABLISHED);
  }
}
