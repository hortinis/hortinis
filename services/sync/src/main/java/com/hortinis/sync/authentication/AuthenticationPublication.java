package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Event;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Outcome;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence.Reason;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit publication/failure transactions and current consumed-proof binding. */
public final class AuthenticationPublication {
  private final TransactionTemplate transaction;
  private final AuthenticationSecurityEventPersistence events;
  private final Clock clock;

  /** Uses the shared transaction manager and trusted UTC; no body/identity enters audit storage. */
  public AuthenticationPublication(
      PlatformTransactionManager transactions,
      AuthenticationSecurityEventPersistence events,
      Clock clock) {
    transaction = new TransactionTemplate(transactions);
    this.events = events;
    this.clock = clock;
  }

  /** Crypto and committed nonce consumption run outside any caller transaction. */
  public <T> T attempt(Event event, Supplier<T> operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Factor workflows must begin outside a transaction.");
    }
    try {
      return operation.get();
    } catch (AuthenticationFailedException | AuthenticationRequiredException exception) {
      mutate(
          () -> {
            events.append(event, Outcome.DENIED, Reason.INVALID_PROOF);
            return true;
          });
      throw exception;
    } catch (AuthenticationPreconditionException exception) {
      mutate(
          () -> {
            events.append(event, Outcome.DENIED, Reason.PRECONDITION);
            return true;
          });
      throw exception;
    } catch (AuthenticationOverloadedException exception) {
      mutate(
          () -> {
            events.append(event, Outcome.DENIED, Reason.CAPACITY);
            return true;
          });
      throw exception;
    }
  }

  /** Publishes a partial state without claiming completed both-factor authentication. */
  public <T> T mutate(Supplier<T> operation) {
    return Objects.requireNonNull(transaction.execute(status -> operation.get()));
  }

  /** Success evidence commits atomically with factor/authority publication. */
  public <T> T complete(Event event, Supplier<T> operation) {
    return mutate(
        () -> {
          T result = operation.get();
          events.append(event, Outcome.SUCCEEDED, Reason.COMPLETED);
          return result;
        });
  }

  /** A verified credential or spent receipt alone never authorizes any publication. */
  public void requireBound(
      AuthenticationSession parent,
      AuthenticationChallengePersistence.ConsumedChallenge receipt,
      BoundWebAuthnOperations.VerifiedCredential proof,
      AuthenticationChallengePurpose purpose,
      BoundWebAuthnOperations.ProofKind kind,
      String origin) {
    if (receipt == null
        || proof == null
        || receipt.purpose() != purpose
        || proof.kind() != kind
        || !receipt.sessionReference().equals(parent.reference())
        || !Objects.equals(receipt.account(), parent.account())
        || receipt.epoch() != parent.epoch()
        || !clock.instant().isBefore(receipt.expiresAt())
        || receipt.expiresAt().isAfter(parent.expiresAt())
        || !purpose.permits(parent.state(), parent.recoveryKind(), parent.loginAt() != null)
        || !origin.equals(proof.origin())
        || !MessageDigest.isEqual(receipt.challenge(), proof.challenge())) {
      throw new AuthenticationFailedException();
    }
  }
}
