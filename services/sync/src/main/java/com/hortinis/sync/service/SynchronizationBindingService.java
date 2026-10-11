package com.hortinis.sync.service;

import com.hortinis.sync.access.BindingPreconditionFailedException;
import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import com.hortinis.sync.persistence.ServerInstallationPersistence;
import com.hortinis.sync.persistence.SynchronizationExpectationPersistence;
import com.hortinis.sync.persistence.SynchronizationScopePersistence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Observes the resolved destination and confirms only an unchanged empty scope. */
@Service
@SynchronizationPersistenceEnabled
public class SynchronizationBindingService {
  private static final String OBSERVED = "observed";
  private final ServerInstallationPersistence installation;
  private final SynchronizationScopePersistence scopes;
  private final SynchronizationExpectationPersistence expectations;

  /** Uses dedicated installation, scope-lock, and protocol-state persistence. */
  public SynchronizationBindingService(
      ServerInstallationPersistence installation,
      SynchronizationScopePersistence scopes,
      SynchronizationExpectationPersistence expectations) {
    this.installation = installation;
    this.scopes = scopes;
    this.expectations = expectations;
  }

  /** Contains no scope/account/content/setup information. */
  @Transactional(readOnly = true)
  public ServerInstallationPersistence.PublicIdentity capabilities() {
    return installation.publicIdentity();
  }

  /** The observation is serialized with publication, but alone never confirms initial exchange. */
  @Transactional
  public Observation bootstrap(SynchronizationAccessContext context) {
    scopes.lockForAcceptance(context);
    boolean empty = expectations.isEmpty(context);
    return new Observation(context, empty, expectations.issue(context, empty));
  }

  /** Reobserves current authority without allocating state or extending an expectation lifetime. */
  @Transactional
  public Observation reobserve(SynchronizationAccessContext context, String expectation) {
    scopes.lockForAcceptance(context);
    expectations.require(context, expectation);
    return new Observation(context, expectations.isEmpty(context), expectation);
  }

  /** Idempotent within the original lifetime; neither confirmation nor replay extends expiry. */
  @Transactional
  public Observation confirm(SynchronizationAccessContext context, String expectation) {
    scopes.lockForAcceptance(context);
    String phase = expectations.require(context, expectation);
    if (!expectations.isEmpty(context)) {
      throw new BindingPreconditionFailedException();
    }
    if (OBSERVED.equals(phase)) {
      expectations.phase(context, expectation, "confirmed");
    }
    return new Observation(context, true, expectation);
  }

  /** Internal observation is not an authority factory or credential-bearing diagnostic. */
  public record Observation(
      SynchronizationAccessContext context, boolean empty, String expectation) {
    @Override
    public String toString() {
      return "BindingObservation[redacted]";
    }
  }
}
