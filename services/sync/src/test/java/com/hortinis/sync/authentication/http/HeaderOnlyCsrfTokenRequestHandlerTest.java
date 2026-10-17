package com.hortinis.sync.authentication.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class HeaderOnlyCsrfTokenRequestHandlerTest {
  @Test
  void usesNativeBreachMaskingButRejectsDuplicateHeadersAndParameterFallback() {
    var handler = new HeaderOnlyCsrfTokenRequestHandler();
    var stored = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "synthetic-native-csrf-token");
    var request = new MockHttpServletRequest();
    handler.handle(request, new MockHttpServletResponse(), () -> stored);
    String masked = ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken();
    assertThat(masked).isNotEqualTo(stored.getToken());
    request.setParameter("_csrf", masked);
    assertThat(handler.resolveCsrfTokenValue(request, stored)).isNull();
    request.addHeader("X-CSRF-TOKEN", masked);
    assertThat(handler.resolveCsrfTokenValue(request, stored)).isEqualTo(stored.getToken());
    request.addHeader("X-CSRF-TOKEN", masked);
    assertThat(handler.resolveCsrfTokenValue(request, stored)).isNull();
  }
}
