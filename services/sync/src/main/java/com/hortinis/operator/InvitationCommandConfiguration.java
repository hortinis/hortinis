package com.hortinis.operator;

import com.hortinis.sync.authentication.ActionMailConfiguration;
import com.hortinis.sync.authentication.AuthenticationPolicyConfiguration;
import com.hortinis.sync.authentication.persistence.ActionTokenPersistence;
import com.hortinis.sync.authentication.persistence.InvitationPersistence;
import java.time.Clock;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Explicit non-web host graph: no application scan, security chain or startup admission bypass. */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
@Import({
  AuthenticationPolicyConfiguration.class,
  ActionMailConfiguration.class,
  ActionTokenPersistence.class
})
public class InvitationCommandConfiguration {
  @Bean
  InvitationPersistence invitationPersistence(JdbcClient jdbc, Clock clock) {
    return new InvitationPersistence(jdbc, clock);
  }
}
