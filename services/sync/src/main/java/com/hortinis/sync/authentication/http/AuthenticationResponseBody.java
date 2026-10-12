package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationCredentialService;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;

/** Explicit closed contract projections; framework/provider objects never serialize directly. */
public final class AuthenticationResponseBody {
  private static final String TYPE = "type";
  private static final String ID = "id";
  private static final String PUBLIC_KEY = "public-key";
  private static final String REQUIRED = "required";
  private static final long TIMEOUT = 300_000;

  private AuthenticationResponseBody() {}

  /** Registration wire policy excludes attestation metadata, transports and provider extensions. */
  public static Map<String, Object> registration(PublicKeyCredentialCreationOptions options) {
    return Map.of(
        "challenge", options.getChallenge().toBase64UrlString(),
        "rp", Map.of(ID, options.getRp().getId(), "name", "Hortinis"),
        "user",
            Map.of(
                ID,
                options.getUser().getId().toBase64UrlString(),
                "name",
                options.getUser().getName(),
                "displayName",
                "Hortinis account"),
        "pubKeyCredParams",
            options.getPubKeyCredParams().stream()
                .map(
                    parameter ->
                        Map.<String, Object>of(
                            TYPE, PUBLIC_KEY, "alg", parameter.getAlg().getValue()))
                .toList(),
        "timeout", TIMEOUT,
        "excludeCredentials",
            options.getExcludeCredentials().stream()
                .map(
                    credential ->
                        Map.of(TYPE, PUBLIC_KEY, ID, credential.getId().toBase64UrlString()))
                .toList(),
        "authenticatorSelection", Map.of("residentKey", REQUIRED, "userVerification", REQUIRED),
        "attestation", "none");
  }

  /** Discoverable assertions intentionally omit allowCredentials entirely. */
  public static Map<String, Object> assertion(PublicKeyCredentialRequestOptions options) {
    return Map.of(
        "challenge",
        options.getChallenge().toBase64UrlString(),
        "rpId",
        options.getRpId(),
        "timeout",
        TIMEOUT,
        "userVerification",
        REQUIRED);
  }

  /** Current-account choices contain only a canonical identifier and the optional user label. */
  public static Map<String, Object> credentials(
      List<AuthenticationCredentialService.CredentialSummary> credentials) {
    return Map.of(
        "credentials",
        credentials.stream()
            .map(
                credential -> {
                  Map<String, String> body = new LinkedHashMap<>();
                  body.put(
                      ID, Base64.getUrlEncoder().withoutPadding().encodeToString(credential.id()));
                  if (credential.label() != null) {
                    body.put("label", credential.label());
                  }
                  return body;
                })
            .toList());
  }
}
