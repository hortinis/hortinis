package com.hortinis.topology;

import com.hortinis.sync.HortinisSyncApplication;
import org.springframework.boot.SpringApplication;

/** Separate test entry point; the production application never imports this configuration. */
public final class TopologyTestApplication {
  private TopologyTestApplication() {}

  /**
   * Runs the real application with controlled synthetic authority for disposable topology tests.
   */
  public static void main(String[] args) {
    SpringApplication.run(
        new Class<?>[] {HortinisSyncApplication.class, TopologyTestConfiguration.class}, args);
  }
}
