package com.hortinis.sync.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hortinis.sync.access.ServerInstanceId;
import com.hortinis.sync.access.SynchronizationScopeId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SyncCursorCodecTest {
  private static final ServerInstanceId INSTANCE =
      new ServerInstanceId("00000000-0000-4000-8000-000000000001");
  private static final SynchronizationScopeId SCOPE =
      new SynchronizationScopeId("00000000-0000-4000-8000-000000000002");

  @Test
  void roundTripsPositionsWithinTheirInstallationAndScope() {
    assertThat(SyncCursorCodec.decode(null, INSTANCE, SCOPE)).isZero();
    for (long position : new long[] {0, 1, Long.MAX_VALUE}) {
      assertThat(
              SyncCursorCodec.decode(
                  SyncCursorCodec.encode(position, INSTANCE, SCOPE), INSTANCE, SCOPE))
          .isEqualTo(position);
    }
    assertThatThrownBy(() -> SyncCursorCodec.encode(-1, INSTANCE, SCOPE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsForeignInstallationAndScope() {
    String cursor = SyncCursorCodec.encode(1, INSTANCE, SCOPE);
    assertInvalid(cursor, new ServerInstanceId(SCOPE.value()), SCOPE);
    assertInvalid(cursor, INSTANCE, new SynchronizationScopeId(INSTANCE.value()));
  }

  @Test
  void rejectsLegacyMalformedNonCanonicalAndUnboundedCursors() {
    for (String cursor :
        new String[] {
          "",
          "v1.MQ",
          "scope-v1.",
          "scope-v1.invalid!",
          "scope-v1." + "a".repeat(161),
          SyncCursorCodec.encode(1, INSTANCE, SCOPE) + "="
        }) {
      assertInvalid(cursor, INSTANCE, SCOPE);
    }
    for (String position : new String[] {"-1", "01", "+1", "9223372036854775808", "", "1:2"}) {
      String boundary = INSTANCE.value() + ":" + SCOPE.value() + ":" + position;
      assertInvalid(
          "scope-v1."
              + Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(boundary.getBytes(StandardCharsets.UTF_8)),
          INSTANCE,
          SCOPE);
    }
  }

  private static void assertInvalid(
      String cursor, ServerInstanceId instance, SynchronizationScopeId scope) {
    assertThatThrownBy(() -> SyncCursorCodec.decode(cursor, instance, scope))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("The request is invalid.");
  }
}
