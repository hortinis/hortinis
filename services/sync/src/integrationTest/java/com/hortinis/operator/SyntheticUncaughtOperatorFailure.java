package com.hortinis.operator;

/** Child-JVM diagnostic fixture; deliberately throws without application/database authority. */
public final class SyntheticUncaughtOperatorFailure {
  private SyntheticUncaughtOperatorFailure() {}

  /** Proves the production process boundary discards even an unexpected error's cause material. */
  public static void main(String[] args) {
    HortinisOperatorCommand.installUncaughtFailureBoundary();
    throw new AssertionError("synthetic-operator-diagnostic-canary");
  }
}
