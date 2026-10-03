package com.hortinis.sync.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class SynchronizationAccessContextTest {

  private static final String ID = "01890f3e-7c5a-7b12-8abc-0123456789ab";

  @Test
  void factoriesAreUnavailableToPublicWireConsumers() {
    assertThat(SynchronizationAccessContext.class.getConstructors()).isEmpty();
    for (var method : SynchronizationAccessContext.class.getDeclaredMethods()) {
      if (Modifier.isStatic(method.getModifiers())) {
        assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
        assertThat(Modifier.isProtected(method.getModifiers())).isFalse();
      }
    }
    assertThat(Modifier.isFinal(SynchronizationAccessContext.class.getModifiers())).isTrue();
  }

  @Test
  void rejectsIncompleteAndInconsistentContextsWithoutIdentityDiagnostics() {
    ServerInstanceId instance = new ServerInstanceId(ID);
    SynchronizationScopeId scope = new SynchronizationScopeId(ID);
    AccountId account = new AccountId(ID);
    assertThatThrownBy(() -> SynchronizationAccessContext.configured(null, scope))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                SynchronizationAccessContext.authenticated(
                    instance, scope, ServerAccessMode.SINGLE_USER_NO_AUTH, account))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The access authority is invalid.");
    assertThatThrownBy(
            () ->
                SynchronizationAccessContext.authenticated(
                    instance, scope, ServerAccessMode.SINGLE_USER_AUTH, null))
        .isInstanceOf(NullPointerException.class);
    assertThat(SynchronizationAccessContext.configured(instance, scope).authority())
        .isInstanceOf(SynchronizationAccessContext.ConfiguredAuthority.class);
    SynchronizationAccessContext context =
        SynchronizationAccessContext.authenticated(
            instance, scope, ServerAccessMode.MULTI_USER_AUTH, account);
    assertThat(context.synchronizationScopeId()).isEqualTo(scope);
    assertThat(context.serverInstanceId()).isEqualTo(instance);
    assertThat(context.toString()).doesNotContain(ID);
    assertThat(instance.toString()).doesNotContain(ID);
  }
}
