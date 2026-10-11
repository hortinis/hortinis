package com.hortinis.sync.access;

import com.hortinis.sync.persistence.ServerInstallationPersistence;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;

/**
 * Trusted fixture mapping only; this source set is absent from every production runtime/artifact.
 */
public final class TopologyAccessResolver implements SynchronizationAccessResolver {
  /** Stable synthetic A identity retained through real service restart. */
  public static final String SCOPE_A = "01890f3e-7c5a-7b12-8abc-00000000000a";

  /** Stable synthetic B identity retained through real service restart. */
  public static final String SCOPE_B = "01890f3e-7c5a-7b12-8abc-00000000000b";

  private static final int SINGLE_MARKER_COUNT = 1;
  private final ServerInstallationPersistence installation;
  private final HttpServletRequest request;

  /** Uses validated installation state and a closed synthetic device mapping. */
  public TopologyAccessResolver(
      ServerInstallationPersistence installation, HttpServletRequest request) {
    this.installation = installation;
    this.request = request;
  }

  @Override
  public SynchronizationAccessContext resolve() {
    var current = installation.technicalIdentity();
    var markers = Collections.list(request.getHeaders("X-Hortinis-Test-Device"));
    // Existing G2/S6 scenarios retain their separate designated technical scope.
    if (markers.isEmpty()) {
      return SynchronizationAccessContext.configured(current.instance(), current.scope());
    }
    if (markers.size() != SINGLE_MARKER_COUNT) {
      throw new SynchronizationAccessDeniedException();
    }
    String scope =
        switch (markers.getFirst()) {
          case "A1", "A2" -> SCOPE_A;
          case "B" -> SCOPE_B;
          default -> throw new SynchronizationAccessDeniedException();
        };
    return SynchronizationAccessContext.configured(
        current.instance(), new SynchronizationScopeId(scope));
  }
}
