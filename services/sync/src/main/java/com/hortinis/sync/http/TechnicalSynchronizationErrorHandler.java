package com.hortinis.sync.http;

import com.hortinis.sync.protocol.InvalidRequestException;
import com.hortinis.sync.protocol.OperationIdReusedException;
import com.hortinis.sync.protocol.RecordAlreadyExistsException;
import com.hortinis.sync.protocol.RecordNotFoundException;
import com.hortinis.sync.protocol.RevisionConflictException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class TechnicalSynchronizationErrorHandler {

  @ExceptionHandler({InvalidRequestException.class, HttpMessageNotReadableException.class})
  ResponseEntity<InvalidRequestError> invalidRequest(Exception exception) {
    return ResponseEntity.badRequest()
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
  ResponseEntity<Void> unexpectedFailure() {
    return ResponseEntity.internalServerError().build();
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Void> resourceNotFound() {
    return ResponseEntity.notFound().build();
  }

  public record InvalidRequestError(String code, String message) {}

  public record RecordNotFoundError(
      String code, String message, String operationId, String recordId) {}

  public record OperationIdReusedError(String code, String message, String operationId) {}

  public record RecordAlreadyExistsError(
      String code,
      String message,
      String operationId,
      com.hortinis.sync.protocol.TechnicalRecord currentRecord) {}

  public record RevisionConflictError(
      String code,
      String message,
      String operationId,
      String expectedRevision,
      com.hortinis.sync.protocol.TechnicalRecord currentRecord) {}
}
