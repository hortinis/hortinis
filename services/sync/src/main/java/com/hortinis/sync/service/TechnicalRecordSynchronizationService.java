package com.hortinis.sync.service;

import com.hortinis.sync.access.SynchronizationAccessContext;
import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import com.hortinis.sync.persistence.SynchronizationScopePersistence;
import com.hortinis.sync.persistence.TechnicalRecordAcceptancePersistence;
import com.hortinis.sync.protocol.ChangePage;
import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.DeleteTechnicalRecordOperation;
import com.hortinis.sync.protocol.ExpectedRevision;
import com.hortinis.sync.protocol.InvalidRequestException;
import com.hortinis.sync.protocol.OperationId;
import com.hortinis.sync.protocol.OperationIdReusedException;
import com.hortinis.sync.protocol.OperationResult;
import com.hortinis.sync.protocol.RecordAlreadyExistsException;
import com.hortinis.sync.protocol.RecordIdentifierRetiredException;
import com.hortinis.sync.protocol.RecordNotFoundException;
import com.hortinis.sync.protocol.RecordOperationResult;
import com.hortinis.sync.protocol.ReplaceTechnicalRecordOperation;
import com.hortinis.sync.protocol.Revision;
import com.hortinis.sync.protocol.RevisionConflictException;
import com.hortinis.sync.protocol.SyncCursorCodec;
import com.hortinis.sync.protocol.TechnicalChange;
import com.hortinis.sync.protocol.TechnicalRecord;
import com.hortinis.sync.protocol.TechnicalRecordOperation;
import com.hortinis.sync.protocol.TechnicalTombstone;
import com.hortinis.sync.protocol.TombstoneOperationResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic acceptance and ordered pulls within an immutable server-resolved scope. */
@Service
@SynchronizationPersistenceEnabled
public class TechnicalRecordSynchronizationService {

  private static final int CHANGE_PAGE_SIZE = 100;

  private final TechnicalRecordAcceptancePersistence persistence;
  private final SynchronizationScopePersistence scopes;

  /** Uses scoped persistence; every workflow caller must supply resolved authority. */
  public TechnicalRecordSynchronizationService(
      TechnicalRecordAcceptancePersistence persistence, SynchronizationScopePersistence scopes) {
    this.persistence = persistence;
    this.scopes = scopes;
  }

  /** Serializes acceptance, replay, and publication within the resolved scope transaction. */
  @Transactional
  public OperationResult submit(
      SynchronizationAccessContext context, TechnicalRecordOperation operation) {
    // Lock before receipts and records; keep publication order stable until commit.
    scopes.lockForAcceptance(context);
    Objects.requireNonNull(operation, "The operation is required.");
    Optional<TechnicalRecordOperation> existingReceipt =
        persistence.findReceipt(context, operation.operationId());
    if (existingReceipt.isPresent()) {
      return replayOrReject(context, operation, existingReceipt.get());
    }

    Optional<TechnicalRecord> current = persistence.findRecord(context, operation.recordId());
    Revision acceptedRevision =
        switch (operation) {
          case CreateTechnicalRecordOperation create -> validateCreate(context, create, current);
          case ReplaceTechnicalRecordOperation replace ->
              validateChange(replace, replace.expectedRevision(), current);
          case DeleteTechnicalRecordOperation delete ->
              validateChange(delete, delete.expectedRevision(), current);
        };

    if (!persistence.insertReceipt(context, operation)) {
      TechnicalRecordOperation receipt =
          persistence
              .findReceipt(context, operation.operationId())
              .orElseThrow(() -> new IllegalStateException("The operation receipt disappeared."));
      return replayOrReject(context, operation, receipt);
    }

    return switch (operation) {
      case CreateTechnicalRecordOperation create -> {
        TechnicalRecord accepted =
            new TechnicalRecord(create.recordId(), acceptedRevision, create.value());
        persistence.insertRecord(context, accepted);
        yield acceptRecord(context, create.operationId(), accepted);
      }
      case ReplaceTechnicalRecordOperation replace -> {
        TechnicalRecord accepted =
            new TechnicalRecord(replace.recordId(), acceptedRevision, replace.value());
        persistence.replaceRecord(context, accepted);
        yield acceptRecord(context, replace.operationId(), accepted);
      }
      case DeleteTechnicalRecordOperation delete ->
          acceptDeletion(context, delete, acceptedRevision);
    };
  }

  /** Keeps current account/session locks through the resolved scope's committed changes read. */
  @Transactional
  public ChangePage pull(SynchronizationAccessContext context, String cursor) {
    long currentSequence = scopes.requireScope(context);
    long afterSequence =
        SyncCursorCodec.decode(
            cursor, context.serverInstanceId(), context.synchronizationScopeId());
    if (afterSequence > currentSequence) {
      throw new InvalidRequestException();
    }
    List<TechnicalChange> fetched =
        persistence.findChangesAfter(context, afterSequence, CHANGE_PAGE_SIZE + 1);
    boolean hasMore = fetched.size() > CHANGE_PAGE_SIZE;
    List<TechnicalChange> changes =
        hasMore ? List.copyOf(fetched.subList(0, CHANGE_PAGE_SIZE)) : List.copyOf(fetched);
    long nextSequence =
        changes.isEmpty()
            ? afterSequence
            : Long.parseLong(changes.get(changes.size() - 1).sequence());
    return new ChangePage(
        changes,
        SyncCursorCodec.encode(
            nextSequence, context.serverInstanceId(), context.synchronizationScopeId()),
        hasMore);
  }

  private Revision validateCreate(
      SynchronizationAccessContext context,
      CreateTechnicalRecordOperation operation,
      Optional<TechnicalRecord> current) {
    if (persistence.isIdentifierRetired(context, operation.recordId())) {
      throw new RecordIdentifierRetiredException(operation.operationId(), operation.recordId());
    }
    if (current.isPresent()) {
      throw new RecordAlreadyExistsException(operation.operationId(), current.get());
    }
    return new Revision(1);
  }

  private static Revision validateChange(
      TechnicalRecordOperation operation,
      ExpectedRevision expectedRevision,
      Optional<TechnicalRecord> current) {
    TechnicalRecord record =
        current.orElseThrow(
            () -> new RecordNotFoundException(operation.operationId(), operation.recordId()));
    if (!expectedRevision.matches(record.revision())) {
      throw new RevisionConflictException(operation.operationId(), expectedRevision, record);
    }
    return record.revision().next();
  }

  private OperationResult acceptRecord(
      SynchronizationAccessContext context, OperationId operationId, TechnicalRecord record) {
    long sequence = persistence.insertChange(context, operationId, record);
    return new RecordOperationResult("accepted", operationId, record, Long.toString(sequence));
  }

  private OperationResult acceptDeletion(
      SynchronizationAccessContext context,
      DeleteTechnicalRecordOperation operation,
      Revision revision) {
    long sequence =
        persistence.insertTombstoneChange(
            context, operation.operationId(), operation.recordId(), revision);
    TechnicalTombstone tombstone =
        new TechnicalTombstone(operation.recordId(), revision, Long.toString(sequence));
    persistence.insertTombstone(context, tombstone);
    persistence.reserveIdentifier(context, operation.recordId(), Long.toString(sequence));
    persistence.deleteRecord(context, operation.recordId());
    return new TombstoneOperationResult(
        "accepted", operation.operationId(), tombstone, Long.toString(sequence));
  }

  private OperationResult replayOrReject(
      SynchronizationAccessContext context,
      TechnicalRecordOperation operation,
      TechnicalRecordOperation original) {
    if (!operation.equals(original)) {
      throw new OperationIdReusedException(operation.operationId());
    }
    return persistence
        .findResult(context, operation.operationId())
        .orElseThrow(() -> new IllegalStateException("The accepted operation result is missing."));
  }
}
