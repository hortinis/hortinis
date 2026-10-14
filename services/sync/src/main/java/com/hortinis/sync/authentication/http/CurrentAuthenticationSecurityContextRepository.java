package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationSecrets;
import com.hortinis.sync.authentication.SessionAuthorityReference;
import com.hortinis.sync.authentication.persistence.CurrentAuthenticationAuthorityPersistence;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.TransactionException;

/** Rehydrates Spring authority from current database state, never a saved principal snapshot. */
public final class CurrentAuthenticationSecurityContextRepository
    implements SecurityContextRepository {
  private static final String UNAVAILABLE =
      CurrentAuthenticationSecurityContextRepository.class.getName() + ".unavailable";
  private final CurrentAuthenticationAuthorityPersistence authority;

  /** Uses current authority reads; request-context persistence cannot bypass publication locks. */
  public CurrentAuthenticationSecurityContextRepository(
      CurrentAuthenticationAuthorityPersistence authority) {
    this.authority = authority;
  }

  @Override
  public SecurityContext loadContext(HttpRequestResponseHolder holder) {
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    var request = holder.getRequest();
    try {
      var session = request.getSession(false);
      if (session != null
          && AuthenticationSecrets.isRandomValue(session.getId())
          && session.getAttribute("hortinis.authority")
              instanceof SessionAuthorityReference reference) {
        authority
            .findFull(AuthenticationSecrets.digest(session.getId()), reference.value())
            .filter(current -> "single-user-auth".equals(current.mode()))
            .ifPresent(
                current ->
                    context.setAuthentication(
                        new PreAuthenticatedAuthenticationToken(
                            current,
                            null,
                            List.of(
                                FactorGrantedAuthority.withAuthority(
                                        FactorGrantedAuthority.PASSWORD_AUTHORITY)
                                    .issuedAt(current.bothFactorsAt())
                                    .build(),
                                FactorGrantedAuthority.withAuthority(
                                        FactorGrantedAuthority.WEBAUTHN_AUTHORITY)
                                    .issuedAt(current.bothFactorsAt())
                                    .build()))));
      }
    } catch (DataAccessException | TransactionException exception) {
      // Deferred context loading can precede ExceptionTranslationFilter; fail closed and let
      // Spring's entry point produce the existing minimized storage-unavailable response.
      request.setAttribute(UNAVAILABLE, true);
    }
    return context;
  }

  /** Lets the Spring entry point distinguish unavailable storage from absent authentication. */
  public static boolean isUnavailable(HttpServletRequest request) {
    return Boolean.TRUE.equals(request.getAttribute(UNAVAILABLE));
  }

  @Override
  public boolean containsContext(HttpServletRequest request) {
    return loadContext(new HttpRequestResponseHolder(request, null)).getAuthentication() != null;
  }

  @Override
  public void saveContext(
      SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
    throw new UnsupportedOperationException(
        "Authority is published only by authentication transactions.");
  }
}
