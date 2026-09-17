package com.hortinis.sync.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SyncCursorCodecTest {

  @Test
  void roundTripsPositionsWithoutExposingTheirRepresentation() {
    assertThat(SyncCursorCodec.decode(null)).isZero();
    assertThat(SyncCursorCodec.encode(0)).isEqualTo("v1.MA");
    assertThat(SyncCursorCodec.decode(SyncCursorCodec.encode(Long.MAX_VALUE)))
        .isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void rejectsMalformedAndNonCanonicalCursors() {
    assertThatThrownBy(() -> SyncCursorCodec.decode(""))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> SyncCursorCodec.decode("v1.not-a-cursor"))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> SyncCursorCodec.decode("v1.MDE="))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> SyncCursorCodec.decode("v1.LTE="))
        .isInstanceOf(InvalidRequestException.class);
  }
}
