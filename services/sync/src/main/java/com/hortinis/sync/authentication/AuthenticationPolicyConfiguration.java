package com.hortinis.sync.authentication;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Trusted clock and explicit selected policies; none of these beans enables an access mode. */
@Configuration(proxyBeanMethods = false)
public class AuthenticationPolicyConfiguration {
  private static final String ACCESS_PREFIX = "hortinis.access";
  private static final String MODE_PROPERTY = "mode";
  private static final String AUTH_MODE = "single-user-auth";

  /**
   * Clock replacement is reserved for controlled tests; browser time never drives authorization.
   */
  @Bean
  @ConditionalOnMissingBean(Clock.class)
  public Clock authenticationClock() {
    return Clock.systemUTC();
  }

  /** Missing/corrupt offline reference data fails before selected authentication can activate. */
  @Bean
  @ConditionalOnProperty(prefix = ACCESS_PREFIX, name = MODE_PROPERTY, havingValue = AUTH_MODE)
  public PasswordPolicy authenticationPasswordPolicy() {
    return new PasswordPolicy(PasswordBlocklist.load());
  }

  /** One bounded crypto limiter is shared by every selected password hash or verification job. */
  @Bean
  @ConditionalOnProperty(prefix = ACCESS_PREFIX, name = MODE_PROPERTY, havingValue = AUTH_MODE)
  public PasswordVerifier authenticationPasswordVerifier(CryptoWorkLimiter limiter) {
    return new PasswordVerifier(limiter);
  }

  /** Shares the two-job work ceiling with the passkey verification adapter. */
  @Bean
  @ConditionalOnProperty(prefix = ACCESS_PREFIX, name = MODE_PROPERTY, havingValue = AUTH_MODE)
  public CryptoWorkLimiter authenticationCryptoWorkLimiter() {
    return new CryptoWorkLimiter();
  }

  /**
   * Transient restrictions use one independent process key and fixed production windows/capacity.
   */
  @Bean
  @ConditionalOnProperty(prefix = ACCESS_PREFIX, name = MODE_PROPERTY, havingValue = AUTH_MODE)
  public AuthenticationAttemptLimiter authenticationAttemptLimiter(Clock clock) {
    return new AuthenticationAttemptLimiter(clock);
  }
}
