package com.hortinis.sync.authentication;

import com.hortinis.sync.authentication.http.AuthenticationBrowserGuard;
import com.hortinis.sync.authentication.http.AuthenticationHttpErrors;
import com.hortinis.sync.authentication.http.AuthenticationInputGuard;
import com.hortinis.sync.authentication.http.HeaderOnlyCsrfTokenRequestHandler;
import com.hortinis.sync.authentication.persistence.AccountActionPersistence;
import com.hortinis.sync.authentication.persistence.ActionTokenPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import com.hortinis.sync.authentication.persistence.AuthenticationNativeSessions;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Native security and account actions; production admission stays closed during replacement. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hortinis.access", name = "mode", havingValue = "single-user-auth")
@EnableScheduling
@Import({
  AuthenticationObservationService.class,
  AuthenticationLifecycle.class,
  AuthenticationAudit.class
})
public class AuthenticationRuntimeConfiguration {
  /** Account actions reuse native password and session infrastructure. */
  @Bean
  public AccountActionService accountActionService(
      AccountActionPersistence accounts,
      ActionTokenPersistence tokens,
      CurrentAuthenticationAuthorityPersistence authority,
      AuthenticationNativeSessions sessions,
      PasswordPolicy policy,
      PasswordVerifier passwords,
      ActionTokenDelivery delivery,
      PlatformTransactionManager transactions) {
    return new AccountActionService(
        accounts, tokens, authority, sessions, policy, passwords, delivery, transactions);
  }

  /** The provider alone checks passwords and runs unknown-user mitigation. */
  @Bean
  public DaoAuthenticationProvider authenticationPasswordProvider(
      AuthenticationAccountPersistence accounts, PasswordVerifier encoder) {
    var provider = new DaoAuthenticationProvider(new AuthenticationPasswordService(accounts));
    provider.setPasswordEncoder(encoder);
    return provider;
  }

  /** Fixed configured HTTPS origin and first-party browser security headers. */
  @Bean
  public AuthenticationBrowserGuard authenticationBrowserGuard(Environment environment) {
    String forwarding = environment.getProperty("server.forward-headers-strategy", "none");
    String origin = environment.getRequiredProperty("hortinis.access.public-origin");
    if (!"none".equalsIgnoreCase(forwarding)
        || !"https".equalsIgnoreCase(URI.create(origin).getScheme())) {
      throw new IllegalStateException("Authenticated access requires a fixed HTTPS origin.");
    }
    return new AuthenticationBrowserGuard(
        origin, environment.getProperty("hortinis.access.tls-validated", Boolean.class, false));
  }

  /** Origin checks precede the native session filter, CSRF and body parsing. */
  @Bean
  public FilterRegistrationBean<AuthenticationBrowserGuard> authenticationBrowserGuardRegistration(
      AuthenticationBrowserGuard filter) {
    var registration = new FilterRegistrationBean<>(filter);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  /** Spring owns token generation, deferred storage and lifecycle invalidation. */
  @Bean
  public static HttpSessionCsrfTokenRepository authenticationCsrfTokenRepository() {
    var repository = new HttpSessionCsrfTokenRepository();
    repository.setSessionAttributeName("hortinis.csrf");
    return repository;
  }

  /** Native password/session/fixation/CSRF/logout; fixed handlers never establish a context. */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public SecurityFilterChain authenticatedSecurity(
      HttpSecurity http, HttpSessionCsrfTokenRepository csrf, AuthenticationAttemptLimiter attempts)
      throws Exception {
    var contexts = new HttpSessionSecurityContextRepository();
    contexts.setDisableUrlRewriting(true);
    var json = JsonMapper.builder().build();
    http.sessionManagement(
        session ->
            session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(fixation -> fixation.changeSessionId()));
    http.securityContext(
        context -> context.requireExplicitSave(true).securityContextRepository(contexts));
    http.csrf(
        config ->
            config
                .csrfTokenRepository(csrf)
                .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler()));
    http.addFilterBefore(new AuthenticationInputGuard(attempts), CsrfFilter.class);
    http.formLogin(
        login ->
            login
                .loginPage("/api/v1/auth/session")
                .loginProcessingUrl("/api/v1/auth/login")
                .usernameParameter("email")
                .authenticationDetailsSource(request -> null)
                .securityContextRepository(contexts)
                .successHandler(
                    (request, response, authentication) -> {
                      response.setHeader("Cache-Control", "no-store");
                      response.setContentType("application/json");
                      response
                          .getWriter()
                          .write(
                              json.writeValueAsString(
                                  AuthenticationObservationService.authenticated(
                                      AuthenticationIdentity.from(authentication))));
                    })
                .failureHandler(
                    (request, response, exception) -> {
                      if (exception.getCause() instanceof AuthenticationOverloadedException) {
                        AuthenticationHttpErrors.THROTTLED.write(response);
                      } else if (exception instanceof InternalAuthenticationServiceException) {
                        AuthenticationHttpErrors.UNAVAILABLE.write(response);
                      } else {
                        AuthenticationHttpErrors.FAILED.write(response);
                      }
                    }));
    http.logout(
        logout ->
            logout
                .logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler(
                    (request, response, authentication) -> {
                      response.setHeader("Cache-Control", "no-store");
                      response.setContentType("application/json");
                      response.getWriter().write("{\"completed\":true}");
                    }));
    http.headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts.disable()));
    http.httpBasic(basic -> basic.disable());
    http.rememberMe(remember -> remember.disable());
    http.requestCache(cache -> cache.disable());
    http.exceptionHandling(
        errors ->
            errors
                .authenticationEntryPoint(
                    (request, response, exception) ->
                        AuthenticationHttpErrors.REQUIRED.write(response))
                .accessDeniedHandler(
                    (request, response, exception) ->
                        AuthenticationHttpErrors.DENIED.write(response)));
    http.authorizeHttpRequests(
        requests ->
            requests
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/v1/server/capabilities",
                    "/api/v1/auth/csrf",
                    "/api/v1/auth/session",
                    "/actuator/health",
                    "/actuator/health/**")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/logout")
                .permitAll()
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/auth/invitations/accept",
                    "/api/v1/auth/password-reset/request",
                    "/api/v1/auth/password-reset/complete")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/password")
                .authenticated()
                .requestMatchers("/api/v1/sync/**")
                .authenticated()
                .anyRequest()
                .denyAll());
    return http.build();
  }
}
