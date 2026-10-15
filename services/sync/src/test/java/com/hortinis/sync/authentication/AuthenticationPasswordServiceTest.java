package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hortinis.sync.authentication.persistence.AuthenticationAccountPersistence;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class AuthenticationPasswordServiceTest {
  private static final String LOGIN = "synthetic-owner";
  private static final String PASSWORD = "synthetic passphrase";
  private final AuthenticationAccountPersistence accounts =
      mock(AuthenticationAccountPersistence.class);
  private final PasswordVerifier encoder = mock(PasswordVerifier.class);

  @Test
  void unknownUsersStillRunTheSelectedEncoderThroughTheFrameworkDummyVerifier() {
    when(accounts.findLogin(LOGIN)).thenReturn(Optional.empty());
    when(encoder.encode("userNotFoundPassword")).thenReturn("dummy-verifier");
    var service = new AuthenticationPasswordService(accounts, encoder);
    assertThatThrownBy(() -> service.authenticate(LOGIN, PASSWORD))
        .isInstanceOf(AuthenticationFailedException.class);
    verify(encoder).matches(PASSWORD, "dummy-verifier");
  }

  @Test
  void returnsTheExactVerifiedSnapshotForPublicationRechecks() {
    var snapshot =
        new AuthenticationAccountPersistence.AccountSnapshot(
            UUID.randomUUID(),
            7,
            UUID.randomUUID(),
            UUID.randomUUID(),
            LOGIN,
            "stored-verifier",
            new byte[32]);
    when(accounts.findLogin(LOGIN)).thenReturn(Optional.of(snapshot));
    when(encoder.matches(PASSWORD, "stored-verifier")).thenReturn(true);
    var service = new AuthenticationPasswordService(accounts, encoder);
    assertThat(service.authenticate(LOGIN, PASSWORD)).isSameAs(snapshot);
  }

  @Test
  void preservesStorageUnavailableInsteadOfReportingCredentialFailure() {
    var unavailable = new DataAccessResourceFailureException("private database details");
    when(accounts.findLogin(LOGIN)).thenThrow(unavailable);
    var service = new AuthenticationPasswordService(accounts, encoder);
    assertThatThrownBy(() -> service.authenticate(LOGIN, PASSWORD)).isSameAs(unavailable);
  }
}
