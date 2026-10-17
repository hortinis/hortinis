package com.hortinis.r3;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Loopback-only synthetic SMTP; message content never leaves memory or enters diagnostics. */
final class LocalSmtpFixture implements AutoCloseable {
  private final ServerSocket listener;
  private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Future<String> received;
  private volatile Socket connection;

  LocalSmtpFixture(boolean silent, boolean reject) throws Exception {
    listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    received =
        executor.submit(
            () -> {
              try (Socket socket = listener.accept()) {
                connection = socket;
                socket.setSoTimeout(15000);
                var input =
                    new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                if (silent) {
                  input.readLine();
                  return "";
                }
                var output =
                    new PrintWriter(socket.getOutputStream(), true, StandardCharsets.US_ASCII);
                output.print("220 synthetic.example.test SMTP\r\n");
                output.flush();
                var message = new StringBuilder();
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
                    data = false;
                    output.print("250 accepted\r\n");
                  } else if (line.startsWith("EHLO") || line.startsWith("HELO")) {
                    output.print("250 synthetic.example.test\r\n");
                  } else if (line.startsWith("MAIL FROM")) {
                    output.print(reject ? "550 synthetic rejection\r\n" : "250 ok\r\n");
                  } else if (line.startsWith("RCPT TO")) {
                    output.print("250 ok\r\n");
                  } else if (line.equals("DATA")) {
                    output.print("354 continue\r\n");
                    data = true;
                  } else if (line.equals("QUIT")) {
                    output.print("221 bye\r\n");
                    output.flush();
                    break;
                  } else {
                    output.print("250 ok\r\n");
                  }
                  output.flush();
                }
                return message.toString();
              }
            });
  }

  JavaMailSenderImpl sender() {
    var sender = new JavaMailSenderImpl();
    sender.setHost(listener.getInetAddress().getHostAddress());
    sender.setPort(listener.getLocalPort());
    // Only the synthetic loopback fixture omits TLS; production configuration requires STARTTLS.
    for (String name : java.util.List.of("connectiontimeout", "timeout", "writetimeout")) {
      sender.getJavaMailProperties().setProperty("mail.smtp." + name, "5000");
    }
    sender.getJavaMailProperties().setProperty("mail.smtp.localhost", "synthetic.example.test");
    return sender;
  }

  String message() throws Exception {
    return received.get(15, TimeUnit.SECONDS);
  }

  @Override
  public void close() throws Exception {
    listener.close();
    if (connection != null) {
      connection.close();
    }
    executor.shutdownNow();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }
}
