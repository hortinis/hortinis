package com.hortinis.sync.authentication.http;

import com.hortinis.sync.authentication.AuthenticationFailedException;
import com.hortinis.sync.authentication.AuthenticationOverloadedException;
import com.hortinis.sync.authentication.AuthenticationPreconditionException;
import com.hortinis.sync.authentication.AuthenticationRequiredException;
import com.hortinis.sync.protocol.InvalidRequestException;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Fixed no-store failures for authentication controllers; no supplied text or cause is returned.
 */
@RestControllerAdvice(
    assignableTypes = {AuthenticationReadController.class, AuthenticationMutationController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AuthenticationErrorHandler {
  /** Account existence and factor validity remain indistinguishable. */
  @ExceptionHandler(AuthenticationFailedException.class)
  public ResponseEntity<?> failed() {
    return AuthenticationHttpErrors.FAILED.response();
  }

  /** Current full authority is independently required by protected services. */
  @ExceptionHandler(AuthenticationRequiredException.class)
  public ResponseEntity<?> required() {
    return AuthenticationHttpErrors.REQUIRED.response();
  }

  /** Fixed retry interval for work/admission capacity. */
  @ExceptionHandler(AuthenticationOverloadedException.class)
  public ResponseEntity<?> throttled() {
    return AuthenticationHttpErrors.THROTTLED.response();
  }

  /** Target or ceremony replacement never silently mutates a newer login. */
  @ExceptionHandler(AuthenticationPreconditionException.class)
  public ResponseEntity<?> precondition() {
    return AuthenticationHttpErrors.PRECONDITION.response();
  }

  /** Parse/I/O errors discard all received and provider diagnostic details. */
  @ExceptionHandler({InvalidRequestException.class, IOException.class})
  public ResponseEntity<?> invalid() {
    return AuthenticationHttpErrors.INVALID.response();
  }

  /** Authentication cannot fall back when its transaction/storage is unavailable. */
  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<?> unavailable() {
    return AuthenticationHttpErrors.UNAVAILABLE.response();
  }
}
