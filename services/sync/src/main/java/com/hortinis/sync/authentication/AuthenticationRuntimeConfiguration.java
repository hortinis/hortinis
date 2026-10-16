package com.hortinis.sync.authentication;

import com.hortinis.sync.access.ServerAccessMode;
import com.hortinis.sync.authentication.http.AuthenticationBrowserGuard;
import com.hortinis.sync.authentication.http.AuthenticationHttpErrors;
import com.hortinis.sync.authentication.http.AuthenticationProofBoundary;
import com.hortinis.sync.authentication.http.HeaderOnlyCsrfTokenRequestHandler;
import com.hortinis.sync.authentication.persistence.AuthenticationLifecyclePersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationOperatorTicketPersistence;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authorization.AllRequiredFactorsAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.FactorAuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit S9-only runtime graph for a freshly provisioned sole authenticated account. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hortinis.access", name = "mode", havingValue = "single-user-auth")
@EnableScheduling
@Import({
  AuthenticationPublication.class,
  AuthenticationSetupService.class,
  AuthenticationLoginService.class,
  AuthenticationPasswordService.class,
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

  /** Guards same-origin requests and emits fixed browser security headers. */
  @Bean
  public AuthenticationBrowserGuard authenticationBrowserGuard(Environment environment) {
    String origin = publicOrigin(environment);
    boolean validatedTls =
        environment.getProperty("hortinis.access.tls-validated", Boolean.class, false);
    return new AuthenticationBrowserGuard(origin, validatedTls);
  }

  /** Uses native Spring token generation, deferred loading and session storage. */
  @Bean
  public static HttpSessionCsrfTokenRepository authenticationCsrfTokenRepository() {
    var repository = new HttpSessionCsrfTokenRepository();
    repository.setSessionAttributeName("hortinis.csrf");
    return repository;
  }

  /** Runs browser-origin validation before session, CSRF and request parsing. */
  @Bean
  public FilterRegistrationBean<AuthenticationBrowserGuard> authenticationBrowserGuardRegistration(
      AuthenticationBrowserGuard filter) {
    return registration(filter, Ordered.HIGHEST_PRECEDENCE + 10);
  }

  /**
   * Spring owns request authentication and factor authorization; transactions recheck revocation.
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public SecurityFilterChain authenticatedSecurity(
      HttpSecurity http, HttpSessionCsrfTokenRepository csrf, Clock clock) throws Exception {
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
                .securityContextRepository(new HttpSessionSecurityContextRepository()));
    var factors =
        AllRequiredFactorsAuthorizationManager.<RequestAuthorizationContext>builder()
            .requireFactor(
                factor ->
                    factor.passwordAuthority().validDuration(AuthenticationLifetime.FULL_ABSOLUTE))
            .requireFactor(
                factor ->
                    factor.webauthnAuthority().validDuration(AuthenticationLifetime.FULL_ABSOLUTE))
            .build();
    factors.setClock(clock);
    http.exceptionHandling(
        errors ->
            errors
                .authenticationEntryPoint(
                    (request, response, exception) ->
                        AuthenticationHttpErrors.REQUIRED.write(response))
                .accessDeniedHandler(
                    (request, response, exception) -> {
                      if (exception instanceof AuthorizationDeniedException denied
                          && denied.getAuthorizationResult()
                              instanceof FactorAuthorizationDecision) {
                        AuthenticationHttpErrors.REQUIRED.write(response);
                      } else {
                        AuthenticationHttpErrors.DENIED.write(response);
                      }
                    }));
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
