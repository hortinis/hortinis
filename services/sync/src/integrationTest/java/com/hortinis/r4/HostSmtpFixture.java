package com.hortinis.r4;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Loopback STARTTLS server with runtime-generated synthetic certificate and memory-only
 * receivedMessages.
 */
public final class HostSmtpFixture implements AutoCloseable {
  private static final String PASSWORD = "synthetic-r4-smtp-keystore";
  private final ServerSocket listener;
  private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
  private final SSLContext tls;
  private final Path store;
  private final List<String> receivedMessages = new CopyOnWriteArrayList<>();
  private final AtomicBoolean reject = new AtomicBoolean();
  private final java.util.concurrent.Future<?> serving;

  /** Creates local TLS material and a bounded synthetic listener. */
  public HostSmtpFixture(Path directory) throws Exception {
    store = directory.resolve("smtp.p12");
    var keytool =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "synthetic",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                PASSWORD,
                "-keypass",
                PASSWORD,
                "-noprompt")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (!keytool.waitFor(20, TimeUnit.SECONDS) || keytool.exitValue() != 0) {
      keytool.destroyForcibly();
      throw new IllegalStateException("Synthetic TLS setup failed.");
    }
    var keys = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(store)) {
      keys.load(input, PASSWORD.toCharArray());
    }
    var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    managers.init(keys, PASSWORD.toCharArray());
    tls = SSLContext.getInstance("TLS");
    tls.init(managers.getKeyManagers(), null, null);
    listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    listener.setSoTimeout(1000);
    serving =
        executor.submit(
            () -> {
              while (!listener.isClosed()) {
                try (Socket socket = listener.accept()) {
                  handle(socket);
                } catch (java.net.SocketTimeoutException expected) {
                  /* bounded shutdown observation */
                } catch (java.io.IOException exception) {
                  if (!listener.isClosed()) {
                    throw new IllegalStateException("Synthetic SMTP failed.");
                  }
                }
              }
            });
  }

  /** Returns the loopback listener port. */
  public int port() {
    return listener.getLocalPort();
  }

  /** Returns only synthetic memory-resident messages for assertions. */
  public List<String> messages() {
    return List.copyOf(receivedMessages);
  }

  /** Refuses the next synthetic recipient. */
  public void rejectNext() {
    reject.set(true);
  }

  /** Trusts only this fixture certificate in the isolated host JVM. */
  public List<String> trustArguments() {
    return List.of(
        "-Djavax.net.ssl.trustStore=" + store, "-Djavax.net.ssl.trustStorePassword=" + PASSWORD);
  }

  private void handle(Socket plain) throws java.io.IOException {
    plain.setSoTimeout(10000);
    try (var input = reader(plain);
        var output = writer(plain)) {
      send(output, "220 synthetic.example.test SMTP");
      if (!input.readLine().startsWith("EHLO")) {
        throw new java.io.IOException("Synthetic EHLO required.");
      }
      output.print("250-synthetic.example.test\r\n250 STARTTLS\r\n");
      output.flush();
      if (!"STARTTLS".equals(input.readLine())) {
        throw new java.io.IOException("Synthetic STARTTLS required.");
      }
      send(output, "220 ready");
      try (SSLSocket secure =
          (SSLSocket)
              tls.getSocketFactory().createSocket(plain, "localhost", plain.getPort(), false)) {
        secure.setUseClientMode(false);
        secure.setSoTimeout(10000);
        secure.startHandshake();
        try (var secureInput = reader(secure);
            var secureOutput = writer(secure)) {
          receive(secureInput, secureOutput);
        }
      }
    }
  }

  private void receive(BufferedReader input, PrintWriter output) throws java.io.IOException {
    StringBuilder message = new StringBuilder();
    boolean data = false;
    while (true) {
      String line = input.readLine();
      if (line == null) {
        break;
      }
      if (data && !".".equals(line)) {
        message.append(line).append('\n');
        continue;
      }
      if (data) {
        receivedMessages.add(message.toString());
        data = false;
        send(output, "250 accepted");
      } else if (line.startsWith("EHLO")) {
        send(output, "250 synthetic.example.test");
      } else if (line.startsWith("RCPT TO") && reject.getAndSet(false)) {
        send(output, "550 synthetic rejection");
      } else if (line.equals("DATA")) {
        data = true;
        send(output, "354 continue");
      } else if (line.equals("QUIT")) {
        send(output, "221 bye");
        break;
      } else {
        send(output, "250 ok");
      }
    }
  }

  private static BufferedReader reader(Socket socket) throws java.io.IOException {
    return new BufferedReader(
        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
  }

  private static PrintWriter writer(Socket socket) throws java.io.IOException {
    return new PrintWriter(socket.getOutputStream(), true, StandardCharsets.US_ASCII);
  }

  private static void send(PrintWriter output, String line) {
    output.print(line + "\r\n");
    output.flush();
  }

  @Override
  public void close() throws Exception {
    listener.close();
    executor.shutdown();
    if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
      executor.shutdownNow();
    }
    serving.get(2, TimeUnit.SECONDS);
  }
}
