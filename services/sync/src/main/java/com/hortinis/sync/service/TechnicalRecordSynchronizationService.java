package com.hortinis.sync.service;

import com.hortinis.sync.persistence.TechnicalRecordAcceptancePersistence;
import com.hortinis.sync.persistence.TechnicalRecordAcceptancePersistence.AcceptedOperationReceipt;
import com.hortinis.sync.protocol.ChangePage;
import com.hortinis.sync.protocol.CreateTechnicalRecordOperation;
import com.hortinis.sync.protocol.OperationIdReusedException;
import com.hortinis.sync.protocol.OperationResult;
import com.hortinis.sync.protocol.OperationRules;
import com.hortinis.sync.protocol.RecordAlreadyExistsException;
import com.hortinis.sync.protocol.RecordNotFoundException;
import com.hortinis.sync.protocol.ReplaceTechnicalRecordOperation;
import com.hortinis.sync.protocol.RevisionConflictException;
import com.hortinis.sync.protocol.SyncCursorCodec;
import com.hortinis.sync.protocol.TechnicalChange;
import com.hortinis.sync.protocol.TechnicalRecord;
import com.hortinis.sync.protocol.TechnicalRecordOperation;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(
    prefix = "hortinis.sync.persistence",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TechnicalRecordSynchronizationService {

  private static final String CREATE_KIND = "create";
  private static final int CHANGE_PAGE_SIZE = 100;

  private final TechnicalRecordAcceptancePersistence persistence;

  public TechnicalRecordSynchronizationService(TechnicalRecordAcceptancePersistence persistence) {
    this.persistence = persistence;
  }

  @Transactional
  public OperationResult submit(TechnicalRecordOperation operation) {
    OperationRules.validate(operation);

    Optional<AcceptedOperationReceipt> existingReceipt =
        persistence.findReceiptForUpdate(operation.operationId());
    if (existingReceipt.isPresent()) {
      return replayOrReject(operation, existingReceipt.get());
    }

    persistence.lockRecord(operation.recordId());
    existingReceipt = persistence.findReceiptForUpdate(operation.operationId());
    if (existingReceipt.isPresent()) {
      return replayOrReject(operation, existingReceipt.get());
    }

    TechnicalRecord current = persistence.findRecordForUpdate(operation.recordId()).orElse(null);
    TechnicalRecord accepted;
    if (operation instanceof CreateTechnicalRecordOperation create) {
      if (current != null) {
        throw new RecordAlreadyExistsException(operation.operationId(), current);
      }
      accepted = new TechnicalRecord(create.recordId(), "1", create.value());
    } else if (operation instanceof ReplaceTechnicalRecordOperation replace) {
      if (current == null) {
        throw new RecordNotFoundException(operation.operationId(), operation.recordId());
      }
      if (!new BigInteger(replace.expectedRevision()).equals(new BigInteger(current.revision()))) {
        throw new RevisionConflictException(
            operation.operationId(), replace.expectedRevision(), current);
      }
      accepted =
          new TechnicalRecord(
              replace.recordId(), nextRevision(current.revision()), replace.value());
    } else {
      throw new IllegalStateException("Unsupported technical operation.");
    }

    if (!persistence.insertReceipt(operation)) {
      AcceptedOperationReceipt receipt =
          persistence
              .findReceiptForUpdate(operation.operationId())
              .orElseThrow(() -> new IllegalStateException("The operation receipt disappeared."));
      return replayOrReject(operation, receipt);
    }

    if (operation instanceof CreateTechnicalRecordOperation create) {
      persistence.insertRecord(create);
    } else if (operation instanceof ReplaceTechnicalRecordOperation replace) {
      persistence.replaceRecord(replace, current.revision());
    }

    long sequence = persistence.insertChange(operation.operationId(), accepted);
    return new OperationResult(
        "accepted", operation.operationId(), accepted, Long.toString(sequence));
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

  private OperationResult replayOrReject(
      TechnicalRecordOperation operation, AcceptedOperationReceipt receipt) {
    TechnicalRecordOperation original = toOperation(operation.operationId(), receipt);
    if (!OperationRules.equal(operation, original)) {
      throw new OperationIdReusedException(operation.operationId());
    }
    return persistence
        .findResult(operation.operationId())
        .orElseThrow(() -> new IllegalStateException("The accepted operation result is missing."));
  }

  private static String nextRevision(String revision) {
    return Long.toString(Math.addExact(Long.parseLong(revision), 1));
  }

  private static TechnicalRecordOperation toOperation(
      String operationId, AcceptedOperationReceipt receipt) {
    if (CREATE_KIND.equals(receipt.kind())) {
      return new CreateTechnicalRecordOperation(operationId, receipt.recordId(), receipt.value());
    }
    return new ReplaceTechnicalRecordOperation(
        operationId, receipt.recordId(), receipt.value(), receipt.expectedRevision());
  }
}
