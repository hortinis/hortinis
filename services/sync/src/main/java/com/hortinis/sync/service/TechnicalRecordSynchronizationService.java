package com.hortinis.sync.service;

import com.hortinis.sync.configuration.SynchronizationPersistenceEnabled;
import com.hortinis.sync.persistence.TechnicalRecordAcceptancePersistence;
import com.hortinis.sync.protocol.ChangePage;
import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.DeleteTechnicalRecordOperation;
import com.hortinis.sync.protocol.ExpectedRevision;
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

@Service
@SynchronizationPersistenceEnabled
public class TechnicalRecordSynchronizationService {

  private static final int CHANGE_PAGE_SIZE = 100;

  private final TechnicalRecordAcceptancePersistence persistence;

  public TechnicalRecordSynchronizationService(TechnicalRecordAcceptancePersistence persistence) {
    this.persistence = persistence;
  }

  @Transactional
  public OperationResult submit(TechnicalRecordOperation operation) {
    Objects.requireNonNull(operation, "The operation is required.");
    Optional<TechnicalRecordOperation> existingReceipt =
        persistence.findReceiptForUpdate(operation.operationId());
    if (existingReceipt.isPresent()) {
      return replayOrReject(operation, existingReceipt.get());
    }

    persistence.lockRecord(operation.recordId());
    existingReceipt = persistence.findReceiptForUpdate(operation.operationId());
    if (existingReceipt.isPresent()) {
      return replayOrReject(operation, existingReceipt.get());
    }

    Optional<TechnicalRecord> current = persistence.findRecordForUpdate(operation.recordId());
    Revision acceptedRevision =
        switch (operation) {
          case CreateTechnicalRecordOperation create -> validateCreate(create, current);
          case ReplaceTechnicalRecordOperation replace ->
              validateChange(replace, replace.expectedRevision(), current);
          case DeleteTechnicalRecordOperation delete ->
              validateChange(delete, delete.expectedRevision(), current);
        };

    if (!persistence.insertReceipt(operation)) {
      TechnicalRecordOperation receipt =
          persistence
              .findReceiptForUpdate(operation.operationId())
              .orElseThrow(() -> new IllegalStateException("The operation receipt disappeared."));
      return replayOrReject(operation, receipt);
    }

    // Every publisher takes the record lock before this scope lock. Holding the scope row through
    // commit prevents a later sequence from becoming visible before an earlier sequence.
    persistence.lockChangePublication();

    return switch (operation) {
      case CreateTechnicalRecordOperation create -> {
        TechnicalRecord accepted =
            new TechnicalRecord(create.recordId(), acceptedRevision, create.value());
        persistence.insertRecord(accepted);
        yield acceptRecord(create.operationId(), accepted);
      }
      case ReplaceTechnicalRecordOperation replace -> {
        TechnicalRecord accepted =
            new TechnicalRecord(replace.recordId(), acceptedRevision, replace.value());
        persistence.replaceRecord(accepted);
        yield acceptRecord(replace.operationId(), accepted);
      }
      case DeleteTechnicalRecordOperation delete -> acceptDeletion(delete, acceptedRevision);
    };
  }

  @Transactional(readOnly = true)
  public ChangePage pull(String cursor) {
    long afterSequence = SyncCursorCodec.decode(cursor);
    List<TechnicalChange> fetched =
        persistence.findChangesAfter(afterSequence, CHANGE_PAGE_SIZE + 1);
    boolean hasMore = fetched.size() > CHANGE_PAGE_SIZE;
    List<TechnicalChange> changes =
        hasMore ? List.copyOf(fetched.subList(0, CHANGE_PAGE_SIZE)) : List.copyOf(fetched);
    long nextSequence =
        changes.isEmpty()
            ? afterSequence
            : Long.parseLong(changes.get(changes.size() - 1).sequence());
    return new ChangePage(changes, SyncCursorCodec.encode(nextSequence), hasMore);
  }

  private Revision validateCreate(
      CreateTechnicalRecordOperation operation, Optional<TechnicalRecord> current) {
    if (persistence.isIdentifierRetired(operation.recordId())) {
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

  private OperationResult acceptRecord(OperationId operationId, TechnicalRecord record) {
    long sequence = persistence.insertChange(operationId, record);
    return new RecordOperationResult("accepted", operationId, record, Long.toString(sequence));
  }

  private OperationResult acceptDeletion(
      DeleteTechnicalRecordOperation operation, Revision revision) {
    long sequence =
        persistence.insertTombstoneChange(operation.operationId(), operation.recordId(), revision);
    TechnicalTombstone tombstone =
        new TechnicalTombstone(operation.recordId(), revision, Long.toString(sequence));
    persistence.insertTombstone(tombstone);
    persistence.reserveIdentifier(operation.recordId(), Long.toString(sequence));
    persistence.deleteRecord(operation.recordId());
    return new TombstoneOperationResult(
        "accepted", operation.operationId(), tombstone, Long.toString(sequence));
  }

  private OperationResult replayOrReject(
      TechnicalRecordOperation operation, TechnicalRecordOperation original) {
    if (!operation.equals(original)) {
      throw new OperationIdReusedException(operation.operationId());
    }
    return persistence
        .findResult(operation.operationId())
        .orElseThrow(() -> new IllegalStateException("The accepted operation result is missing."));
  }
}
