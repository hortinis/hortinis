package com.hortinis.sync.authentication;

import com.hortinis.sync.access.ServerAccessMode;
import com.hortinis.sync.authentication.http.AuthenticationBrowserGuard;
import com.hortinis.sync.authentication.http.AuthenticationHttpErrors;
import com.hortinis.sync.authentication.http.AuthenticationLifecycleFilter;
import com.hortinis.sync.authentication.http.AuthenticationProofBoundary;
import com.hortinis.sync.authentication.http.CurrentAuthenticationSecurityContextRepository;
import com.hortinis.sync.authentication.http.HeaderOnlyCsrfTokenRequestHandler;
import com.hortinis.sync.authentication.http.RandomSessionCsrfTokenRepository;
import com.hortinis.sync.authentication.persistence.AuthenticationJdbcSessions;
import com.hortinis.sync.authentication.persistence.AuthenticationLifecyclePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationOperatorTicketPersistence;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authorization.AllRequiredFactorsAuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit S9-only runtime graph for a freshly provisioned sole authenticated account. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hortinis.access", name = "mode", havingValue = "single-user-auth")
@EnableScheduling
@Import({
  AuthenticationPublication.class,
  AuthenticationSetupService.class,
  AuthenticationLoginService.class,
  AuthenticationAccountService.class,
  AuthenticationOperatorRecoveryService.class,
  AuthenticationSessionService.class,
  AuthenticationObservationService.class,
  AuthenticationProofBoundary.class
})
public class AuthenticationRuntimeConfiguration {

  /** Constructs WebAuthn verification for the fixed operator-configured HTTPS origin. */
  @Bean
  public AuthenticationPasskeys authenticationPasskeys(
      Environment environment, CryptoWorkLimiter limiter) {
    String origin = publicOrigin(environment);
    return new AuthenticationPasskeys(origin, limiter);
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
   * Spring owns request authentication and factor authorization; transactions recheck revocation.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public SecurityFilterChain authenticatedSecurity(
      HttpSecurity http,
      RandomSessionCsrfTokenRepository csrf,
      CurrentAuthenticationAuthorityPersistence authority)
      throws Exception {
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
        context ->
            context
                .requireExplicitSave(true)
                .securityContextRepository(
                    new CurrentAuthenticationSecurityContextRepository(authority)));
    var factors =
        AllRequiredFactorsAuthorizationManager.<RequestAuthorizationContext>builder()
            .requireFactor(factor -> factor.passwordAuthority())
            .requireFactor(factor -> factor.webauthnAuthority())
            .build();
    http.exceptionHandling(
        errors ->
            errors
                .authenticationEntryPoint(
                    (request, response, exception) ->
                        (CurrentAuthenticationSecurityContextRepository.isUnavailable(request)
                                ? AuthenticationHttpErrors.UNAVAILABLE
                                : AuthenticationHttpErrors.REQUIRED)
                            .write(response))
                .accessDeniedHandler(
                    (request, response, exception) ->
                        AuthenticationHttpErrors.DENIED.write(response)));
    http.authorizeHttpRequests(
        requests ->
            requests
                .requestMatchers(
                    "/api/v1/server/capabilities",
                    "/api/v1/auth/**",
                    "/actuator/health",
                    "/actuator/health/**",
                    "/error")
                .permitAll()
                .requestMatchers("/api/v1/sync/**")
                .access(factors)
                .anyRequest()
                .denyAll());
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
