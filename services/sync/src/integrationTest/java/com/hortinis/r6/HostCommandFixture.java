package com.hortinis.r6;

import com.hortinis.r4.HostSmtpFixture;
import com.hortinis.sync.HortinisSyncApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Actual isolated JVM, sharing the HTTP fixture's synthetic database; no production selector. */
public final class HostCommandFixture {
  private final String url;
  private final String username;
  private final String password;
  private final String origin;
  private final HostSmtpFixture smtp;

  /** Uses only credentials and transport owned by the disposable local fixture. */
  public HostCommandFixture(
      String url, String username, String password, String origin, HostSmtpFixture smtp) {
    this.url = url;
    this.username = username;
    this.password = password;
    this.origin = origin;
    this.smtp = smtp;
  }

  /** Starts a fresh non-web process and checks its minimized diagnostic output. */
  public int run(String action, String email) throws Exception {
    var invocation = new ArrayList<String>();
    invocation.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    invocation.addAll(smtp.trustArguments());
    invocation.addAll(
        List.of(
            "-cp",
            Objects.requireNonNull(System.getProperty("hortinis.operator.test.classpath")),
            HortinisSyncApplication.class.getName(),
            "operator",
            action));
    var builder = new ProcessBuilder(invocation).redirectErrorStream(true);
    var env = builder.environment();
    env.clear();
    env.put("HORTINIS_ACCESS_MODE", "single-user-auth");
    env.put("HORTINIS_ACCESS_PUBLIC_ORIGIN", origin);
    env.put("SPRING_DATASOURCE_URL", url);
    env.put("SPRING_DATASOURCE_USERNAME", username);
    env.put("SPRING_DATASOURCE_PASSWORD", password);
    env.put("SPRING_MAIL_HOST", "localhost");
    env.put("SPRING_MAIL_PORT", Integer.toString(smtp.port()));
    env.put("SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH", "false");
    env.put("SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE", "true");
    env.put("SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED", "true");
    env.put("SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_CHECKSERVERIDENTITY", "true");
    env.put("HORTINIS_AUTHENTICATION_MAIL_FROM", "sender@example.test");
    var process = builder.start();
    try {
      process.getOutputStream().write(email.getBytes(StandardCharsets.US_ASCII));
      process.getOutputStream().close();
      if (!process.waitFor(40, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Synthetic host command timeout.");
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (output.contains(email) || output.contains("#token=") || output.contains("Exception")) {
        throw new IllegalStateException("Synthetic host diagnostics were not minimized.");
      }
      return process.exitValue();
    } finally {
      process.destroyForcibly();
    }
  }
}
