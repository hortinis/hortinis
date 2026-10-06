package com.hortinis.sync.http;

import com.hortinis.sync.persistence.PostgreSqlExceptionTranslator;
import com.hortinis.sync.protocol.InvalidRequestException;
import com.hortinis.sync.protocol.OperationIdReusedException;
import com.hortinis.sync.protocol.RecordAlreadyExistsException;
import com.hortinis.sync.protocol.RecordIdentifierRetiredException;
import com.hortinis.sync.protocol.RecordNotFoundException;
import com.hortinis.sync.protocol.RevisionConflictException;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class TechnicalSynchronizationErrorHandler {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(TechnicalSynchronizationErrorHandler.class);

  @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class})
  ResponseEntity<SynchronizationUnavailableError> synchronizationUnavailable() {
    return ResponseEntity.status(503)
        .header(HttpHeaders.RETRY_AFTER, "1")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(
            new SynchronizationUnavailableError(
                "SYNCHRONIZATION_UNAVAILABLE", "The synchronization service is unavailable."));
  }

  @ExceptionHandler(CannotCreateTransactionException.class)
  ResponseEntity<?> transactionUnavailable(CannotCreateTransactionException exception) {
    for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof TransientDataAccessException
          || cause instanceof DataAccessResourceFailureException) {
        return synchronizationUnavailable();
      }
      if (cause instanceof SQLException sqlException) {
        DataAccessException translated =
            new PostgreSqlExceptionTranslator().translate("Begin transaction", null, sqlException);
        if (translated instanceof TransientDataAccessException
            || translated instanceof DataAccessResourceFailureException) {
          return synchronizationUnavailable();
        }
        return unexpectedFailure(exception);
      }
    }
    return unexpectedFailure(exception);
  }

  @ExceptionHandler({InvalidRequestException.class, HttpMessageNotReadableException.class})
  ResponseEntity<InvalidRequestError> invalidRequest(Exception exception) {
    return ResponseEntity.badRequest()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(new InvalidRequestError("INVALID_REQUEST", "The request is invalid."));
  }

  @ExceptionHandler(RecordNotFoundException.class)
  ResponseEntity<RecordNotFoundError> recordNotFound(RecordNotFoundException exception) {
    return ResponseEntity.status(404)
        .body(
            new RecordNotFoundError(
                "RECORD_NOT_FOUND",
                exception.getMessage(),
                exception.operationId(),
                exception.recordId()));
  }

  @ExceptionHandler(OperationIdReusedException.class)
  ResponseEntity<OperationIdReusedError> operationIdReused(OperationIdReusedException exception) {
    return ResponseEntity.status(409)
        .body(
            new OperationIdReusedError(
                "OPERATION_ID_REUSED", exception.getMessage(), exception.operationId()));
  }

  @ExceptionHandler(RecordAlreadyExistsException.class)
  ResponseEntity<RecordAlreadyExistsError> recordAlreadyExists(
      RecordAlreadyExistsException exception) {
    return ResponseEntity.status(409)
        .body(
            new RecordAlreadyExistsError(
                "RECORD_ALREADY_EXISTS",
                exception.getMessage(),
                exception.operationId(),
                exception.currentRecord()));
  }

  @ExceptionHandler(RecordIdentifierRetiredException.class)
  ResponseEntity<RecordIdentifierRetiredError> recordIdentifierRetired(
      RecordIdentifierRetiredException exception) {
    return ResponseEntity.status(409)
        .body(
            new RecordIdentifierRetiredError(
                "RECORD_IDENTIFIER_RETIRED",
                exception.getMessage(),
                exception.operationId(),
                exception.recordId()));
  }

  @ExceptionHandler(RevisionConflictException.class)
  ResponseEntity<RevisionConflictError> revisionConflict(RevisionConflictException exception) {
    return ResponseEntity.status(409)
        .body(
            new RevisionConflictError(
                "REVISION_CONFLICT",
                exception.getMessage(),
                exception.operationId(),
                exception.expectedRevision(),
                exception.currentRecord()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Void> unexpectedFailure(Exception exception) {
    LOGGER
        .atError()
        .addKeyValue("event", "request_failed")
        .addKeyValue("exception_class", exception.getClass().getName())
        .log("Request failed");
    return ResponseEntity.internalServerError().build();
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Void> resourceNotFound() {
    return ResponseEntity.notFound().build();
  }

  public record InvalidRequestError(String code, String message) {}

  /** Fixed, privacy-safe synchronization persistence failure. */
  public record SynchronizationUnavailableError(String code, String message) {}

  public record RecordNotFoundError(
      String code, String message, String operationId, String recordId) {}

  public record OperationIdReusedError(String code, String message, String operationId) {}

  public record RecordAlreadyExistsError(
      String code,
      String message,
      String operationId,
      com.hortinis.sync.protocol.TechnicalRecord currentRecord) {}

  public record RecordIdentifierRetiredError(
      String code, String message, String operationId, String recordId) {}

  public record RevisionConflictError(
      String code,
      String message,
      String operationId,
      String expectedRevision,
      com.hortinis.sync.protocol.TechnicalRecord currentRecord) {}
}
