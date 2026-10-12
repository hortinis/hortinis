package com.hortinis.sync.authentication;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

/**
 * Protected host-only ticket files; secrets never appear in names, return values or diagnostics.
 */
public final class OperatorTicketFiles {
  private static final String SUFFIX = ".ticket";
  private final Path directory;

  /** Requires POSIX mode enforcement and secure relative traversal; no unsafe platform fallback. */
  public OperatorTicketFiles(Path directory) {
    this.directory = directory.toAbsolutePath().normalize();
    if (this.directory.getNameCount() == 0) {
      throw unavailable();
    }
    try {
      try (var secure = openDirectory()) {
        requirePrivateDirectory(secure);
      }
    } catch (IOException | UnsupportedOperationException exception) {
      throw unavailable();
    }
  }

  /** Creates a new 0600 file exclusively, refusing replacement, symlinks and foreign ownership. */
  public Path create(UUID reference, String secret) {
    if (reference == null || !AuthenticationSecrets.isRandomValue(secret)) {
      throw new IllegalArgumentException("Invalid controlled authentication ticket.");
    }
    Path relative = Path.of(reference + SUFFIX);
    try (var secure = openDirectory()) {
      requirePrivateDirectory(secure);
      boolean created = false;
      try {
        try (var channel =
            secure.newByteChannel(
                relative,
                Set.of(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")))) {
          created = true;
          var attributes =
              secure
                  .getFileAttributeView(
                      relative, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                  .readAttributes();
          var parent = secure.getFileAttributeView(PosixFileAttributeView.class).readAttributes();
          if (!attributes.isRegularFile()
              || !attributes.owner().equals(parent.owner())
              || !attributes.permissions().equals(PosixFilePermissions.fromString("rw-------"))) {
            throw unavailable();
          }
          ByteBuffer bytes = ByteBuffer.wrap((secret + "\n").getBytes(StandardCharsets.US_ASCII));
          while (bytes.hasRemaining()) {
            channel.write(bytes);
          }
          if (channel instanceof FileChannel) {
            ((FileChannel) channel).force(true);
          }
        }
        return directory.resolve(relative);
      } catch (IOException | IllegalStateException | UnsupportedOperationException exception) {
        if (created) {
          secure.deleteFile(relative);
        }
        throw unavailable();
      }
    } catch (IOException | UnsupportedOperationException exception) {
      throw unavailable();
    }
  }

  /**
   * Removes files under the same exclusive installation lock as issuance, without reading secrets.
   */
  public void cleanup(Set<UUID> activeReferences) {
    try (var secure = openDirectory()) {
      requirePrivateDirectory(secure);
      for (Path entry : secure) {
        Path relative = entry.getFileName();
        String name = relative.toString();
        UUID reference = reference(name);
        var attributes =
            secure
                .getFileAttributeView(
                    relative, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes();
        var parent = secure.getFileAttributeView(PosixFileAttributeView.class).readAttributes();
        if (!attributes.isRegularFile()
            || !attributes.owner().equals(parent.owner())
            || !attributes.permissions().equals(PosixFilePermissions.fromString("rw-------"))) {
          throw unavailable();
        }
        if (!activeReferences.contains(reference)) {
          secure.deleteFile(relative);
        }
      }
    } catch (IOException | UnsupportedOperationException exception) {
      throw unavailable();
    }
  }

  private SecureDirectoryStream<Path> openDirectory() throws IOException {
    try (DirectoryStream<Path> root = Files.newDirectoryStream(directory.getRoot())) {
      if (!(root instanceof SecureDirectoryStream<Path>)) {
        throw unavailable();
      }
      return descend((SecureDirectoryStream<Path>) root, directory.iterator());
    }
  }

  private static SecureDirectoryStream<Path> descend(
      SecureDirectoryStream<Path> parent, java.util.Iterator<Path> components) throws IOException {
    SecureDirectoryStream<Path> next =
        parent.newDirectoryStream(components.next(), LinkOption.NOFOLLOW_LINKS);
    if (!components.hasNext()) {
      // The leaf's ownership transfers to the caller's try-with-resources block.
      return next;
    }
    try (next) {
      return descend(next, components);
    }
  }

  private static void requirePrivateDirectory(SecureDirectoryStream<Path> secure)
      throws IOException {
    var attributes = secure.getFileAttributeView(PosixFileAttributeView.class).readAttributes();
    if (!attributes.isDirectory()
        || !attributes.permissions().equals(PosixFilePermissions.fromString("rwx------"))) {
      throw unavailable();
    }
  }

  private static UUID reference(String name) {
    if (!name.endsWith(SUFFIX)) {
      throw unavailable();
    }
    String value = name.substring(0, name.length() - SUFFIX.length());
    try {
      UUID reference = UUID.fromString(value);
      if (!reference.toString().equals(value)) {
        throw unavailable();
      }
      return reference;
    } catch (IllegalArgumentException exception) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Protected authentication ticket storage is unavailable.");
  }
}
