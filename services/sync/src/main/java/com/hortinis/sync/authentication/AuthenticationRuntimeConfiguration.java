package com.hortinis.sync.authentication;

import com.hortinis.sync.access.ServerAccessMode;
import com.hortinis.sync.authentication.http.AuthenticationBrowserGuard;
import com.hortinis.sync.authentication.http.AuthenticationLifecycleFilter;
import com.hortinis.sync.authentication.http.AuthenticationProofBoundary;
import com.hortinis.sync.authentication.http.HeaderOnlyCsrfTokenRequestHandler;
import com.hortinis.sync.authentication.http.RandomSessionCsrfTokenRepository;
import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationAccountSecurityPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationChallengePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationJdbcSessions;
import com.hortinis.sync.authentication.persistence.AuthenticationLifecyclePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationOperatorRecoveryPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationOperatorTicketPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationRecoveryCodePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSecurityEventPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionAdmissionPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionManagementPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionPublicationPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSessionRemovalPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationSetupPersistence;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit S9-only runtime graph for a freshly provisioned sole authenticated account. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hortinis.access", name = "mode", havingValue = "single-user-auth")
@EnableScheduling
public class AuthenticationRuntimeConfiguration {

  /** Supplies the shared transaction-published security event boundary. */
  @Bean
  public AuthenticationPublication authenticationPublication(
      PlatformTransactionManager transactions,
      AuthenticationSecurityEventPersistence events,
      Clock clock) {
    return new AuthenticationPublication(transactions, events, clock);
  }

  /** Constructs WebAuthn verification for the fixed operator-configured HTTPS origin. */
  @Bean
  public AuthenticationPasskeys authenticationPasskeys(
      Environment environment, CryptoWorkLimiter limiter) {
    String origin = publicOrigin(environment);
    return new AuthenticationPasskeys(origin, limiter);
  }

  /** Publishes a controlled first-owner ceremony through the selected stores and factors. */
  @Bean
  public AuthenticationSetupService authenticationSetupService(
      AuthenticationPublication publication,
      AuthenticationSetupPersistence setup,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationChallengePersistence challenges,
      PasswordPolicy policy,
      PasswordVerifier passwords,
      AuthenticationPasskeys passkeys) {
    return new AuthenticationSetupService(
        publication, setup, sessions, challenges, policy, passwords, passkeys);
  }

  /** Publishes normal password-plus-passkey sessions without framework login defaults. */
  @Bean
  public AuthenticationLoginService authenticationLoginService(
      AuthenticationPublication publication,
      AuthenticationAccountPersistence accounts,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationChallengePersistence challenges,
      PasswordVerifier passwords,
      AuthenticationPasskeys passkeys) {
    return new AuthenticationLoginService(
        publication, accounts, sessions, challenges, passwords, passkeys);
  }

  /** Builds account credential management and its recent-proof authorization boundary. */
  @Bean
  public AuthenticationCredentialService authenticationCredentialService(
      AuthenticationPublication publication,
      AuthenticationAccountPersistence accounts,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationChallengePersistence challenges,
      AuthenticationAccountSecurityPersistence security,
      AuthenticationRecoveryCodePersistence codes,
      CurrentAuthenticationAuthorityPersistence authority,
      PasswordPolicy policy,
      PasswordVerifier passwords,
      AuthenticationPasskeys passkeys) {
    return new AuthenticationCredentialService(
        publication,
        accounts,
        sessions,
        challenges,
        security,
        codes,
        authority,
        policy,
        passwords,
        passkeys);
  }

  /** Builds two-proof account recovery and its restricted intermediate authority. */
  @Bean
  public AuthenticationRecoveryService authenticationRecoveryService(
      AuthenticationPublication publication,
      AuthenticationAccountPersistence accounts,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationChallengePersistence challenges,
      AuthenticationRecoveryCodePersistence codes,
      AuthenticationAccountSecurityPersistence security,
      PasswordPolicy policy,
      PasswordVerifier passwords,
      AuthenticationPasskeys passkeys) {
    return new AuthenticationRecoveryService(
        publication, accounts, sessions, challenges, codes, security, policy, passwords, passkeys);
  }

  /** Builds exceptional host-authorized recovery without creating normal operator sessions. */
  @Bean
  public AuthenticationOperatorRecoveryService authenticationOperatorRecoveryService(
      AuthenticationPublication publication,
      AuthenticationOperatorRecoveryPersistence recovery,
      AuthenticationAccountPersistence accounts,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationChallengePersistence challenges,
      PasswordPolicy policy,
      PasswordVerifier passwords,
      AuthenticationPasskeys passkeys) {
    return new AuthenticationOperatorRecoveryService(
        publication, recovery, accounts, sessions, challenges, policy, passwords, passkeys);
  }

  /** Coordinates ordinary session listing, revocation and explicit sign-out. */
  @Bean
  public AuthenticationSessionService authenticationSessionService(
      AuthenticationPublication publication,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationSessionManagementPersistence management,
      AuthenticationSessionRemovalPersistence removal) {
    return new AuthenticationSessionService(publication, sessions, management, removal);
  }

  /** Supplies bounded, non-renewing public and authenticated status observations. */
  @Bean
  public AuthenticationObservationService authenticationObservationService(
      AuthenticationPublication publication,
      AuthenticationSessionAdmissionPersistence admission,
      AuthenticationSessionPublicationPersistence sessions,
      AuthenticationAccountPersistence accounts) {
    return new AuthenticationObservationService(publication, admission, sessions, accounts);
  }

  /** Ensures proof challenges and minimized security events publish atomically. */
  @Bean
  public AuthenticationProofBoundary authenticationProofBoundary(
      AuthenticationChallengePersistence challenges,
      AuthenticationAttemptLimiter attempts,
      AuthenticationPublication publication,
      AuthenticationSecurityEventPersistence events) {
    return new AuthenticationProofBoundary(challenges, attempts, publication, events);
  }

  /** Restricts ticket storage to the existing operator-owned directory. */
  @Bean
  public AuthenticationOperatorTickets authenticationOperatorTickets(
      AuthenticationOperatorTicketPersistence tickets,
      Environment environment,
      PlatformTransactionManager transactions) {
    String directory = environment.getRequiredProperty("hortinis.operator.directory");
    return new AuthenticationOperatorTickets(
        tickets, new OperatorTicketFiles(Path.of(directory)), transactions);
  }

  /** Runs expiry reconciliation after database initialization and before service admission. */
  @Bean
  public AuthenticationLifecycle authenticationLifecycle(
      JdbcClient jdbc,
      Clock clock,
      AuthenticationOperatorTickets tickets,
      AuthenticationAttemptLimiter attempts,
      PlatformTransactionManager transactions,
      ServerAccessMode mode) {
    return new AuthenticationLifecycle(
        new AuthenticationLifecyclePersistence(jdbc, clock, transactions), tickets, attempts);
  }

  /** Makes next-access physical cleanup available to the servlet boundary. */
  @Bean
  public AuthenticationLifecycleFilter authenticationLifecycleFilter(
      AuthenticationLifecycle lifecycle) {
    return new AuthenticationLifecycleFilter(lifecycle);
  }

  /** Guards same-origin requests and emits fixed browser security headers. */
  @Bean
  public AuthenticationBrowserGuard authenticationBrowserGuard(Environment environment) {
    String origin = publicOrigin(environment);
    boolean validatedTls =
        environment.getProperty("hortinis.access.tls-validated", Boolean.class, false);
    return new AuthenticationBrowserGuard(origin, validatedTls);
  }

  /** Uses random, session-bound CSRF values without implicit cookie creation. */
  @Bean
  public RandomSessionCsrfTokenRepository authenticationCsrfTokenRepository() {
    return new RandomSessionCsrfTokenRepository();
  }

  /** Runs browser-origin validation before lifecycle, session, CSRF and request parsing. */
  @Bean
  public FilterRegistrationBean<AuthenticationBrowserGuard> authenticationBrowserGuardRegistration(
      AuthenticationBrowserGuard filter) {
    return registration(filter, Ordered.HIGHEST_PRECEDENCE + 10);
  }

  /** Performs lifecycle checks after the browser guard but before native session loading. */
  @Bean
  public FilterRegistrationBean<AuthenticationLifecycleFilter> authenticationLifecycleRegistration(
      AuthenticationLifecycleFilter filter) {
    return registration(filter, Ordered.HIGHEST_PRECEDENCE + 20);
  }

  /** Installs only controlled JDBC sessions and the host-only cookie resolver. */
  @Bean
  public FilterRegistrationBean<SessionRepositoryFilter<?>> authenticationSessionRegistration(
      JdbcOperations jdbc, PlatformTransactionManager transactions) {
    SessionRepositoryFilter<?> filter =
        new SessionRepositoryFilter<>(AuthenticationJdbcSessions.create(jdbc, transactions));
    filter.setHttpSessionIdResolver(new ControlledSessionIdResolver());
    return registration(filter, Ordered.HIGHEST_PRECEDENCE + 30);
  }

  /**
   * Selects the explicit stateless Spring boundary; application authority stays in its adapters.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public SecurityFilterChain authenticatedSecurity(
      HttpSecurity http, RandomSessionCsrfTokenRepository csrf) throws Exception {
    http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER));
    http.csrf(
        configurer ->
            configurer
                .csrfTokenRepository(csrf)
                .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler()));
    http.headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts.disable()));
    http.logout(logout -> logout.disable());
    http.formLogin(login -> login.disable());
    http.httpBasic(basic -> basic.disable());
    http.rememberMe(remember -> remember.disable());
    http.requestCache(cache -> cache.disable());
    http.securityContext(
        context -> context.securityContextRepository(new NullSecurityContextRepository()));
    http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
    return http.build();
  }

  private static String publicOrigin(Environment environment) {
    String forwarding = environment.getProperty("server.forward-headers-strategy", "none");
    if (!"none".equalsIgnoreCase(forwarding)) {
      throw new IllegalStateException(
          "Authenticated access requires forwarded-header interpretation to remain disabled.");
    }
    String origin = environment.getRequiredProperty("hortinis.access.public-origin");
    URI uri = URI.create(origin);
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalStateException("Authenticated access requires a configured HTTPS origin.");
    }
    return origin;
  }

  private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> registration(
      T filter, int order) {
    FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
    registration.setOrder(order);
    registration.setMatchAfter(false);
    return registration;
  }
}
