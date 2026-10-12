package com.hortinis.sync.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorTicketFilesTest {
  private static final String STORAGE_UNAVAILABLE =
      "Protected authentication ticket storage is unavailable.";
  @TempDir Path temporary;

  @Test
  void writesExclusivePrivateFilesAndCleansOnlyInactiveGeneratedReferences() throws Exception {
    Path directory = managedDirectory();
    OperatorTicketFiles files = new OperatorTicketFiles(directory);
    UUID active = UUID.randomUUID();
    UUID expired = UUID.randomUUID();
    String synthetic = AuthenticationSecrets.randomValue();
    Path activeFile = files.create(active, synthetic);
    final Path expiredFile = files.create(expired, AuthenticationSecrets.randomValue());
    assertThat(Files.getPosixFilePermissions(directory))
        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    assertThat(Files.getPosixFilePermissions(activeFile))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(Files.readString(activeFile)).isEqualTo(synthetic + "\n");
    assertThat(activeFile.getFileName().toString()).doesNotContain(synthetic);
    assertThatThrownBy(() -> files.create(active, AuthenticationSecrets.randomValue()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(STORAGE_UNAVAILABLE);
    assertThat(Files.readString(activeFile)).isEqualTo(synthetic + "\n");
    files.cleanup(Set.of(active));
    assertThat(Files.exists(activeFile)).isTrue();
    assertThat(Files.exists(expiredFile)).isFalse();
    files.cleanup(Set.of());
    assertThat(Files.exists(activeFile)).isFalse();
    files.cleanup(Set.of());
  }

  @Test
  void rejectsMissingOrOpenDirectoryAndAllSymlinkTraversalWithoutWritingThroughIt()
      throws Exception {
    Path missing = temporary.resolve("missing");
    assertThatThrownBy(() -> new OperatorTicketFiles(missing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(STORAGE_UNAVAILABLE);
    assertThat(Files.exists(missing)).isFalse();
    Path directory = managedDirectory();
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
    assertThatThrownBy(() -> new OperatorTicketFiles(directory))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(STORAGE_UNAVAILABLE);
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    Path link = temporary.resolve("link");
    Files.createSymbolicLink(link, directory);
    assertThatThrownBy(() -> new OperatorTicketFiles(link))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(STORAGE_UNAVAILABLE);
    assertThatThrownBy(() -> new OperatorTicketFiles(link.resolve("missing-child")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(STORAGE_UNAVAILABLE);
    assertThat(Files.exists(directory.resolve("missing-child"))).isFalse();
  }

  @Test
  void refusesFileSymlinksAndUnknownDirectoryEntriesWithoutChangingTheirTargets() throws Exception {
    Path directory = managedDirectory();
    OperatorTicketFiles files = new OperatorTicketFiles(directory);
    Path target = temporary.resolve("outside");
    Files.writeString(target, "synthetic outside content");
    UUID reference = UUID.randomUUID();
    Path link = directory.resolve(reference + ".ticket");
    Files.createSymbolicLink(link, target);
    assertThatThrownBy(() -> files.create(reference, AuthenticationSecrets.randomValue()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(STORAGE_UNAVAILABLE);
    assertThatThrownBy(() -> files.cleanup(Set.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(STORAGE_UNAVAILABLE);
    assertThat(Files.readString(target)).isEqualTo("synthetic outside content");
    Files.delete(link);
    Path unrelated = directory.resolve("unrelated");
    Files.writeString(unrelated, "synthetic unrelated content");
    assertThatThrownBy(() -> files.cleanup(Set.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(STORAGE_UNAVAILABLE);
    assertThat(Files.readString(unrelated)).isEqualTo("synthetic unrelated content");
  }

  @Test
  void exactlyOneConcurrentCreatorWinsWithoutOverwritingTheWinner() throws Exception {
    Path directory = managedDirectory();
    OperatorTicketFiles files = new OperatorTicketFiles(directory);
    UUID reference = UUID.randomUUID();
    String first = AuthenticationSecrets.randomValue();
    String second = AuthenticationSecrets.randomValue();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var left = executor.submit(() -> attempt(files, reference, first));
      var right = executor.submit(() -> attempt(files, reference, second));
      assertThat(
              (left.get(10, TimeUnit.SECONDS) ? 1 : 0) + (right.get(10, TimeUnit.SECONDS) ? 1 : 0))
          .isEqualTo(1);
    }
    assertThat(Files.readString(directory.resolve(reference + ".ticket")))
        .isIn(first + "\n", second + "\n");
  }

  private Path managedDirectory() throws Exception {
    return Files.createDirectory(
        temporary.resolve("managed"),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  }

  private static boolean attempt(OperatorTicketFiles files, UUID reference, String synthetic) {
    try {
      files.create(reference, synthetic);
      return true;
    } catch (IllegalStateException exception) {
      assertThat(exception).hasMessage(STORAGE_UNAVAILABLE);
      return false;
    }
  }
}
