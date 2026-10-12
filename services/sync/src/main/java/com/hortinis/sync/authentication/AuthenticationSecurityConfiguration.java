package com.hortinis.sync.authentication;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;

/** Explicit mode boundary while authenticated activation remains closed during implementation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthenticationSecurityConfiguration {
  /**
   * Suppresses framework defaults and permits only the selected no-auth or isolated test runtime.
   */
  @Bean
  @ConditionalOnExpression("'${hortinis.access.mode:}' != 'single-user-auth'")
  public SecurityFilterChain applicationSecurity(HttpSecurity http, Environment environment)
      throws Exception {
    boolean noAuth = "single-user-no-auth".equals(environment.getProperty("hortinis.access.mode"));
    boolean isolatedScaffold =
        "false".equals(environment.getProperty("hortinis.sync.persistence.enabled"));
    http.sessionManagement(
        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    // No-auth mutations retain the independent exact-Origin guard. Neither branch installs a user,
    // a login fallback or a session; supported authenticated configuration remains denied at
    // startup.
    http.csrf(csrf -> csrf.disable());
    // HSTS is enabled only after explicit deployment TLS validation, not by framework default.
    http.headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts.disable()));
    http.logout(logout -> logout.disable());
    http.formLogin(login -> login.disable());
    http.httpBasic(basic -> basic.disable());
    http.rememberMe(remember -> remember.disable());
    http.requestCache(cache -> cache.disable());
    http.securityContext(
        context -> context.securityContextRepository(new NullSecurityContextRepository()));
    http.authorizeHttpRequests(
        requests -> {
          if (noAuth || isolatedScaffold) {
            requests.anyRequest().permitAll();
          } else {
            requests.anyRequest().denyAll();
          }
        });
    return http.build();
  }
}
