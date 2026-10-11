package com.hortinis.sync.http;

import com.hortinis.sync.protocol.AccessWireContracts;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Access endpoints expose their declared fixed unavailable contract, without provider diagnostics.
 */
@RestControllerAdvice(assignableTypes = SynchronizationBindingController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SynchronizationBindingErrorHandler {
  /** No expectation, destination, credential or provider exception is retained in the response. */
  @ExceptionHandler({
    TransientDataAccessException.class,
    DataAccessResourceFailureException.class,
    CannotCreateTransactionException.class
  })
  public ResponseEntity<AccessWireContracts.AccessError> unavailable() {
    return ResponseEntity.status(503)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.RETRY_AFTER, "1")
        .body(
            new AccessWireContracts.AccessError(
                "ACCESS_UNAVAILABLE", "The access service is unavailable."));
  }
}
