package com.hortinis.r3;

import static org.assertj.core.api.Assertions.assertThat;

import com.hortinis.sync.authentication.ActionTokenPurpose;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.SmtpActionMailDelivery;
import com.hortinis.sync.authentication.persistence.ActionTokenPersistence.IssuedToken;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Actual Boot-selected JavaMail/Angus transport against local synthetic sockets only. */
@ExtendWith(OutputCaptureExtension.class)
class SmtpDeliveryIntegrationTest {
  private static final String ORIGIN = "https://garden.example.test";
  private static final String FROM = "Hortinis@Example.test";
  private static final String RECIPIENT = "Gardener@Example.test";

  @Test
  void realSmtpAcceptsMinimalMessageWithoutLeakingDiagnostics(CapturedOutput output)
      throws Exception {
    var token = token();
    try (var smtp = new LocalSmtpFixture(false, false)) {
      assertThat(new SmtpActionMailDelivery(smtp.sender(), FROM, ORIGIN).deliver(token)).isTrue();
      assertThat(smtp.message())
          .contains(
              "Subject: Hortinis password reset",
              "#token=",
              token.secret(),
              ORIGIN + "/reset-password")
          .doesNotContain("?token=");
    }
    assertThat(output.getAll()).doesNotContain(FROM, RECIPIENT, token.secret(), ORIGIN);
  }

  @Test
  void rejectionIsFixedFailureWithoutProviderMessageOrRetry(CapturedOutput output)
      throws Exception {
    var token = token();
    try (var smtp = new LocalSmtpFixture(false, true)) {
      assertThat(new SmtpActionMailDelivery(smtp.sender(), FROM, ORIGIN).deliver(token)).isFalse();
      assertThat(smtp.message()).isEmpty();
    }
    assertThat(output.getAll())
        .doesNotContain("synthetic rejection", FROM, RECIPIENT, token.secret());
  }

  @Test
  void silentLocalServerHitsFiveSecondReadTimeout(CapturedOutput output) throws Exception {
    var token = token();
    long started = System.nanoTime();
    try (var smtp = new LocalSmtpFixture(true, false)) {
      assertThat(new SmtpActionMailDelivery(smtp.sender(), FROM, ORIGIN).deliver(token)).isFalse();
    }
    assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(4500L, 15000L);
    assertThat(output.getAll()).doesNotContain(RECIPIENT, token.secret(), "SocketTimeoutException");
  }

  @Test
  void actualTransportPassesFiveSecondConnectTimeoutToSyntheticSocket() {
    var timeout = new AtomicInteger();
    var calls = new AtomicInteger();
    var sender = new JavaMailSenderImpl();
    sender.setHost("127.0.0.1");
    sender.setPort(9);
    sender
        .getJavaMailProperties()
        .put("mail.smtp.socketFactory", new TimeoutSocketFactory(timeout, calls));
    sender.getJavaMailProperties().setProperty("mail.smtp.socketFactory.fallback", "false");
    sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "5000");
    assertThat(new SmtpActionMailDelivery(sender, FROM, ORIGIN).deliver(token())).isFalse();
    assertThat(timeout).hasValue(5000);
    assertThat(calls).hasValue(1);
  }

  @Test
  void selectedProviderInterruptsBlockedLocalWriteAtFiveSeconds() throws Exception {
    // Reflect the runtime-only provider, without promoting it to an application compile dependency.
    try (var listener = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        var client = new Socket()) {
      client.setSendBufferSize(1024);
      client.connect(listener.getLocalSocketAddress(), 5000);
      try (var peer = listener.accept()) {
        peer.setReceiveBufferSize(1024);
        try (var bounded =
            (Socket)
                Class.forName("org.eclipse.angus.mail.util.WriteTimeoutSocket")
                    .getConstructor(Socket.class, int.class)
                    .newInstance(client, 5000)) {
          long started = System.nanoTime();
          org.assertj.core.api.Assertions.assertThatThrownBy(
                  () -> bounded.getOutputStream().write(new byte[8 * 1024 * 1024]))
              .isInstanceOf(IOException.class);
          assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis())
              .isBetween(4500L, 15000L);
        }
      }
    }
  }

  private static IssuedToken token() {
    return new IssuedToken(
        RECIPIENT,
        AuthenticationSecrets.randomValue(),
        ActionTokenPurpose.RESET,
        Instant.parse("2026-10-17T12:10:00Z"));
  }

  private static final class TimeoutSocketFactory extends SocketFactory {
    private final AtomicInteger timeout;
    private final AtomicInteger calls;

    private TimeoutSocketFactory(AtomicInteger timeout, AtomicInteger calls) {
      this.timeout = timeout;
      this.calls = calls;
    }

    @Override
    public Socket createSocket() {
      return new Socket() {
        @Override
        public void connect(SocketAddress address, int milliseconds) throws IOException {
          timeout.set(milliseconds);
          calls.incrementAndGet();
          throw new SocketTimeoutException("Synthetic connection timeout.");
        }
      };
    }

    @Override
    public Socket createSocket(String host, int port) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress local, int localPort) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) {
      throw new UnsupportedOperationException();
    }
  }
}
