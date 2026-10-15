package com.hortinis.sync.authentication.http;

import com.hortinis.sync.access.SynchronizationAccessUnavailableException;
import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationLifecycle;
import com.hortinis.sync.authentication.AuthenticationSecrets;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Independent next-access cleanup before established native-session loading/authority resolution.
 */
public final class AuthenticationLifecycleFilter extends OncePerRequestFilter {
  private final AuthenticationLifecycle lifecycle;
  private final HttpSessionIdResolver identifiers;

  /** The selected runtime owns filter order; no native session is allocated here. */
  public AuthenticationLifecycleFilter(
      AuthenticationLifecycle lifecycle, HttpSessionIdResolver identifiers) {
    this.lifecycle = lifecycle;
    this.identifiers = identifiers;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var ids = identifiers.resolveSessionIds(request);
    try {
      lifecycle.onAccess(
          ids.size() == 1 && AuthenticationSecrets.isRandomValue(ids.getFirst())
              ? AuthenticationSecrets.digest(ids.getFirst())
              : null);
    } catch (DataAccessException
        | TransactionException
        | AuthenticationFailedException
        | SynchronizationAccessUnavailableException
        | IllegalStateException exception) {
      AuthenticationHttpErrors.UNAVAILABLE.write(response);
      return;
    }
    chain.doFilter(request, response);
  }
}
