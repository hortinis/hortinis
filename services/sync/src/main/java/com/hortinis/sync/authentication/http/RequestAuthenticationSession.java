package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationIdentity;
import com.hortinis.sync.authentication.AuthenticationRuntimeConfiguration;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.AuthenticationSessionTransport;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Concrete servlet transport; it installs no filter, principal or runtime access mode. */
public final class RequestAuthenticationSession implements AuthenticationSessionTransport {
  private static final String AUTHORITY_ATTRIBUTE = "hortinis.authority";
  private final HttpServletRequest request;
  private final HttpServletResponse response;
  private final HttpSessionCsrfTokenRepository csrf =
      AuthenticationRuntimeConfiguration.authenticationCsrfTokenRepository();
  private final HttpSessionSecurityContextRepository contexts =
      new HttpSessionSecurityContextRepository();
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
        || observe().isPresent()) {
      throw new IllegalStateException("Ceremony admission requires explicit CSRF bootstrap.");
    }
    HttpSession session = request.getSession(true);
    requireFrameworkId(session.getId());
    SessionAuthorityReference reference = Objects.requireNonNull(admission.apply(session.getId()));
    session.setAttribute(AUTHORITY_ATTRIBUTE, reference);
    session.removeAttribute(AuthenticationSessionTransport.LOGOUT_TARGET_ATTRIBUTE);
    contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
    SecurityContextHolder.clearContext();
    refreshCsrf();
    return new Observation(reference, AuthenticationSecrets.digest(session.getId()));
  }

  @Override
  public Observation rotate(SessionAuthorityReference replacement) {
    requirePublicationTransaction();
    Observation previous = observe().orElseThrow(RequestAuthenticationSession::unavailable);
    if (replacement == null || replacement.equals(previous.reference())) {
      throw new IllegalArgumentException("Rotation requires a fresh reference.");
    }
    String identifier = request.changeSessionId();
    requireFrameworkId(identifier);
    HttpSession session = Objects.requireNonNull(request.getSession(false));
    // With the selected immediate JDBC repository, this attribute flush saves the rotated ID too.
    session.setAttribute(AUTHORITY_ATTRIBUTE, replacement);
    session.removeAttribute(AuthenticationSessionTransport.LOGOUT_TARGET_ATTRIBUTE);
    contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
    SecurityContextHolder.clearContext();
    refreshCsrf();
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
    if (request.getSession(false) == null) {
      throw unavailable();
    }
    csrf.saveToken(csrf.generateToken(request), request, response);
  }

  @Override
  public void authenticate(UUID account, long epoch, Instant verifiedAt, String logoutTarget) {
    requirePublicationTransaction();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(AuthenticationIdentity.completed(account, epoch, verifiedAt));
    Objects.requireNonNull(request.getSession(false))
        .setAttribute(AuthenticationSessionTransport.LOGOUT_TARGET_ATTRIBUTE, logoutTarget);
    contexts.saveContext(context, request, response);
    SecurityContextHolder.setContext(context);
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
