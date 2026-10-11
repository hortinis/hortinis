package com.hortinis.sync.access;

/** Test-thread authority selection; absent from production sources and driven by no HTTP input. */
public final class SyntheticAccessResolver implements SynchronizationAccessResolver {
  private final ThreadLocal<SynchronizationAccessContext> selected = new ThreadLocal<>();

  /** Selects a test-provisioned context for synchronous MockMvc dispatch on this thread. */
  public void select(SynchronizationAccessContext context) {
    selected.set(context);
  }

  /** Clears test authority after each test. */
  public void clear() {
    selected.remove();
  }

  @Override
  public SynchronizationAccessContext resolve() {
    var context = selected.get();
    if (context == null) {
      throw new SynchronizationAccessDeniedException();
    }
    return context;
  }
}
