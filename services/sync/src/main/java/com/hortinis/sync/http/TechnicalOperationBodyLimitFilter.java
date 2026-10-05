package com.hortinis.sync.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds the complete technical operation body before JSON parsing, including chunked requests. */
@Component
public class TechnicalOperationBodyLimitFilter extends OncePerRequestFilter {

  public static final int MAX_BODY_BYTES = 65_536;
  private static final String OPERATIONS_PATH = "/api/v1/sync/operations";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod())
        || !(request.getServletPath().startsWith(OPERATIONS_PATH)
            || (request.getPathInfo() != null && request.getPathInfo().startsWith(OPERATIONS_PATH))
            || request.getRequestURI().startsWith(request.getContextPath() + OPERATIONS_PATH));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      reject(response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (body.length > MAX_BODY_BYTES) {
      reject(response);
      return;
    }
    filterChain.doFilter(new BufferedRequest(request, body), response);
  }

  private static void reject(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response
        .getWriter()
        .write("{\"code\":\"INVALID_REQUEST\",\"message\":\"The request is invalid.\"}");
  }

  private static final class BufferedRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BufferedRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      return new BufferedInputStream(body);
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }

  private static final class BufferedInputStream extends ServletInputStream {
    private final ByteArrayInputStream input;

    BufferedInputStream(byte[] body) {
      input = new ByteArrayInputStream(body);
    }

    @Override
    public int read() {
      return input.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      return input.read(buffer, offset, length);
    }

    @Override
    public boolean isFinished() {
      return input.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener listener) {
      throw new UnsupportedOperationException(
          "Technical operations use synchronous request reads.");
    }
  }
}
