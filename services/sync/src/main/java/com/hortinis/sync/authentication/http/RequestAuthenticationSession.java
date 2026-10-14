package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationLifetime;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.ControlledSessionIdResolver;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Concrete servlet transport; it installs no filter, principal or runtime access mode. */
public final class RequestAuthenticationSession implements AuthenticationSessionTransport {
  private static final String AUTHORITY_ATTRIBUTE = "hortinis.authority";
  private final HttpServletRequest request;
  private final HttpServletResponse response;
  private final RandomSessionCsrfTokenRepository csrf = new RandomSessionCsrfTokenRepository();
  private final SecurityContextLogoutHandler logout = logoutHandler();

  /** Receives the actual established Spring Session request/response wrappers. */
  public RequestAuthenticationSession(HttpServletRequest request, HttpServletResponse response) {
    this.request = request;
    this.response = response;
  }

  @Override
  public Optional<Observation> observe() {
    HttpSession session = request.getSession(false);
    if (session == null || !AuthenticationSecrets.isRandomValue(session.getId())) {
      return Optional.empty();
    }
    Object reference = session.getAttribute(AUTHORITY_ATTRIBUTE);
    return reference instanceof SessionAuthorityReference authority
        ? Optional.of(new Observation(authority, AuthenticationSecrets.digest(session.getId())))
        : Optional.empty();
  }

  @Override
  public Observation bootstrap(Function<String, SessionAuthorityReference> admission) {
    requirePublicationTransaction();
    if (!"GET".equals(request.getMethod())
        || !"/api/v1/auth/csrf".equals(request.getRequestURI())
        || request.getSession(false) != null) {
      throw new IllegalStateException("Session creation requires explicit fresh CSRF bootstrap.");
    }
    HttpSession session = request.getSession(true);
    requireFrameworkId(session.getId());
    session.setMaxInactiveInterval((int) AuthenticationLifetime.LIMITED_ABSOLUTE.toSeconds());
    SessionAuthorityReference reference = Objects.requireNonNull(admission.apply(session.getId()));
    session.setAttribute(AUTHORITY_ATTRIBUTE, reference);
    refreshCsrf();
    releaseAfterCommit(session.getId(), reference);
    return new Observation(reference, AuthenticationSecrets.digest(session.getId()));
  }

  @Override
  public Observation rotate(SessionAuthorityReference replacement, Duration transportLifetime) {
    requirePublicationTransaction();
    requireCookieWorkflow();
    Observation previous = observe().orElseThrow(RequestAuthenticationSession::unavailable);
    if (replacement == null
        || replacement.equals(previous.reference())
        || (!AuthenticationLifetime.LIMITED_ABSOLUTE.equals(transportLifetime)
            && !AuthenticationLifetime.FULL_ABSOLUTE.equals(transportLifetime))) {
      throw new IllegalArgumentException(
          "Rotation requires a fresh reference and bounded lifetime.");
    }
    String identifier = request.changeSessionId();
    requireFrameworkId(identifier);
    HttpSession session = Objects.requireNonNull(request.getSession(false));
    // With the selected immediate JDBC repository, this attribute flush saves the rotated ID too.
    session.setAttribute(AUTHORITY_ATTRIBUTE, replacement);
    session.setMaxInactiveInterval((int) transportLifetime.toSeconds());
    refreshCsrf();
    releaseAfterCommit(identifier, replacement);
    return new Observation(replacement, AuthenticationSecrets.digest(identifier));
  }

  @Override
  public Optional<String> csrfValue() {
    var token = csrf.loadToken(request);
    return token == null ? Optional.empty() : Optional.of(token.getToken());
  }

  @Override
  public void refreshCsrf() {
    requirePublicationTransaction();
    requireCookieWorkflow();
    if (request.getSession(false) == null) {
      throw unavailable();
    }
    csrf.saveToken(csrf.generateToken(request), request, response);
  }

  @Override
  public void invalidate() {
    requirePublicationTransaction();
    logout.logout(request, response, null);
  }

  private static SecurityContextLogoutHandler logoutHandler() {
    var handler = new SecurityContextLogoutHandler();
    handler.setSecurityContextRepository(new NullSecurityContextRepository());
    return handler;
  }

  private void releaseAfterCommit(String identifier, SessionAuthorityReference reference) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            HttpSession current = request.getSession(false);
            if (current != null
                && identifier.equals(current.getId())
                && reference.equals(current.getAttribute(AUTHORITY_ATTRIBUTE))) {
              ControlledSessionIdResolver.authorizeCookieWrite(request, identifier);
            }
          }
        });
  }

  private void requireCookieWorkflow() {
    if (!ControlledSessionIdResolver.isCookieWorkflow(request)) {
      throw new IllegalStateException("Session rotation requires a controlled cookie workflow.");
    }
  }

  private static void requirePublicationTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Session transport requires a writable publication transaction.");
    }
  }

  private static void requireFrameworkId(String identifier) {
    if (!AuthenticationSecrets.isRandomValue(identifier)) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Current authentication transport is unavailable.");
  }
}
