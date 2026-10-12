package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationSecrets;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Established session-backed CSRF storage with independent random values and bounded publication.
 */
public final class RandomSessionCsrfTokenRepository implements CsrfTokenRepository {
  private final HttpSessionCsrfTokenRepository delegate = new HttpSessionCsrfTokenRepository();

  /** Shared closed storage attribute; no cookie/URL/local-storage CSRF transport. */
  public RandomSessionCsrfTokenRepository() {
    delegate.setHeaderName("X-CSRF-TOKEN");
    delegate.setParameterName("_csrf");
    delegate.setSessionAttributeName("hortinis.csrf");
  }

  @Override
  public CsrfToken generateToken(HttpServletRequest request) {
    return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", AuthenticationSecrets.randomValue());
  }

  @Override
  public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("CSRF storage requires a publication transaction.");
    }
    if (token != null && !valid(token)) {
      throw new IllegalArgumentException("Invalid session CSRF value.");
    }
    delegate.saveToken(token, request, response);
  }

  @Override
  public CsrfToken loadToken(HttpServletRequest request) {
    CsrfToken token = delegate.loadToken(request);
    return token != null && valid(token) ? token : null;
  }

  /** Missing filter tokens deny unsafe requests without allocating or saving native sessions. */
  @Override
  public DeferredCsrfToken loadDeferredToken(
      HttpServletRequest request, HttpServletResponse response) {
    CsrfToken stored = loadToken(request);
    CsrfToken token = stored == null ? generateToken(request) : stored;
    return new DeferredCsrfToken() {
      @Override
      public CsrfToken get() {
        return token;
      }

      @Override
      public boolean isGenerated() {
        return stored == null;
      }
    };
  }

  private static boolean valid(CsrfToken token) {
    return "X-CSRF-TOKEN".equals(token.getHeaderName())
        && "_csrf".equals(token.getParameterName())
        && AuthenticationSecrets.isRandomValue(token.getToken());
  }
}
