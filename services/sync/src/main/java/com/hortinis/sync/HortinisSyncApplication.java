package com.hortinis.sync;

import com.hortinis.operator.HortinisOperatorCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class HortinisSyncApplication {

  public static void main(String[] args) {
    if (HortinisOperatorCommand.isInvocation(args)) {
      HortinisOperatorCommand.installUncaughtFailureBoundary();
      System.exit(HortinisOperatorCommand.run(args, System.out, System.err));
      return;
    }
    SpringApplication.run(HortinisSyncApplication.class, args);
  }
}
