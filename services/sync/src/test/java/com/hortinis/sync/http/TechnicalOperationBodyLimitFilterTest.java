package com.hortinis.sync.http;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TechnicalOperationBodyLimitFilterTest {

  private static final String CALLED = "called";

  private final TechnicalOperationBodyLimitFilter filter = new TechnicalOperationBodyLimitFilter();

  @Test
  void passesTheExactByteLimitWithoutChangingTheBody() throws Exception {
    byte[] body = new byte[TechnicalOperationBodyLimitFilter.MAX_BODY_BYTES];
    MockHttpServletRequest request = request(body);
    AtomicBoolean invoked = new AtomicBoolean();
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (bounded, response) -> {
          invoked.set(true);
          assertThat(bounded.getInputStream().readAllBytes()).isEqualTo(body);
        });
    assertThat(invoked).isTrue();
  }

  @Test
  void boundsUnknownLengthBodiesWithoutCallingTheController() throws Exception {
    MockHttpServletRequest request =
        request(new byte[TechnicalOperationBodyLimitFilter.MAX_BODY_BYTES + 1]);
    HttpServletRequestWrapper chunked =
        new HttpServletRequestWrapper(request) {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean invoked = new AtomicBoolean();
    filter.doFilter(chunked, response, (bounded, result) -> invoked.set(true));
    assertThat(invoked).isFalse();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getContentAsString())
        .isEqualTo("{\"code\":\"INVALID_REQUEST\",\"message\":\"The request is invalid.\"}");
  }

  @Test
  void rejectsKnownOversizedBodiesWithoutReadingThem() throws Exception {
    HttpServletRequestWrapper request =
        new HttpServletRequestWrapper(
            request(new byte[TechnicalOperationBodyLimitFilter.MAX_BODY_BYTES + 1])) {
          @Override
          public jakarta.servlet.ServletInputStream getInputStream() {
            throw new AssertionError("A known oversized body must not be read.");
          }
        };
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (bounded, result) -> result.getWriter().write(CALLED));
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).doesNotContain(CALLED);
  }

  @Test
  void appliesWithinContextPathsAndMatrixParameterPaths() throws Exception {
    MockHttpServletRequest request =
        request(new byte[TechnicalOperationBodyLimitFilter.MAX_BODY_BYTES + 1]);
    request.setContextPath("/hortinis");
    request.setRequestURI("/hortinis/api/v1/sync/operations;parameter=1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (bounded, result) -> result.getWriter().write(CALLED));
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void leavesOtherRoutesAlone() throws Exception {
    MockHttpServletRequest request =
        request(new byte[TechnicalOperationBodyLimitFilter.MAX_BODY_BYTES + 1]);
    request.setRequestURI("/another-endpoint");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (bounded, result) -> result.getWriter().write(CALLED));
    assertThat(response.getContentAsString()).isEqualTo(CALLED);
  }

  @Test
  void boundsDecodedServletPathsWhenTheRequestUriIsPercentEncoded() throws Exception {
    MockHttpServletRequest request =
        request(new byte[TechnicalOperationBodyLimitFilter.MAX_BODY_BYTES + 1]);
    request.setRequestURI("/api/v1/sync/%6fperations");
    request.setServletPath("/api/v1/sync/operations");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (bounded, result) -> result.getWriter().write(CALLED));
    assertThat(response.getStatus()).isEqualTo(400);
  }

  private static MockHttpServletRequest request(byte[] body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/operations");
    request.setContent(body);
    return request;
  }
}
