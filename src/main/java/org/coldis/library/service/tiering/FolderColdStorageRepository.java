package org.coldis.library.service.tiering;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Local-folder cold tier ({@code org.coldis.library.service.tiering.storage=folder}): an object key
 * is a relative path under the root, written atomically (temporary file, then move), its metadata
 * in a sidecar ({@code <file>.meta}, one {@code name=value} per line), listed by prefix in
 * lexicographic order. For tests and local runs; the S3 repository is the production store.
 */
@Repository
@ConditionalOnProperty(
		name = ColdTieringProperties.PREFIX + ".storage",
		havingValue = "folder"
)
public class FolderColdStorageRepository implements ColdStorageRepository {

	/** Metadata sidecar suffix. */
	public static final String METADATA_SUFFIX = ".meta";

	/** Temporary file suffix. */
	private static final String TEMPORARY_SUFFIX = ".tmp";

	/** Root directory. */
	private final Path root;

	/**
	 * Constructor.
	 *
	 * @param root Root directory.
	 */
	public FolderColdStorageRepository(
			@Value("${" + ColdTieringProperties.PREFIX + ".folder.root}")
			final String root) {
		this.root = Path.of(root).toAbsolutePath().normalize();
	}

	/**
	 * The path of an object key.
	 *
	 * @param  key Object key.
	 * @return     The path under the root.
	 */
	private Path pathOf(
			final String key) {
		final Path path = this.root.resolve(key).normalize();
		if (!path.startsWith(this.root)) {
			throw new IntegrationException(new SimpleMessage("coldTiering.folder.key.invalid", new Object[] { key }));
		}
		return path;
	}

	/**
	 * The object key of a path.
	 *
	 * @param  path Path under the root.
	 * @return      The key.
	 */
	private String keyOf(
			final Path path) {
		return this.root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
	}

	/**
	 * Writes a file atomically: a temporary sibling, then a move over the target.
	 *
	 * @param  path    Target.
	 * @param  content Content.
	 * @throws IOException If the write fails.
	 */
	private static void writeAtomically(
			final Path path,
			final byte[] content) throws IOException {
		Files.createDirectories(path.getParent());
		final Path temporary = path.resolveSibling(path.getFileName() + FolderColdStorageRepository.TEMPORARY_SUFFIX);
		Files.write(temporary, content);
		Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * The root directory.
	 *
	 * @return The root.
	 */
	public Path getRoot() {
		return this.root;
	}

	/**
	 * Drops every object (tests).
	 */
	public void reset() {
		if (Files.isDirectory(this.root)) {
			try (Stream<Path> paths = Files.walk(this.root)) {
				for (final Path path : paths.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(this.root)).toList()) {
					Files.delete(path);
				}
			}
			catch (final IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}
	}

	/**
	 * Removes an object and its metadata (tests).
	 *
	 * @param key Object key.
	 */
	public void remove(
			final String key) {
		try {
			Files.deleteIfExists(this.pathOf(key));
			Files.deleteIfExists(this.pathOf(key + FolderColdStorageRepository.METADATA_SUFFIX));
		}
		catch (final IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	/**
	 * @see ColdStorageRepository#store(String, byte[], Map)
	 */
	@Override
	public ColdObject store(
			final String key,
			final byte[] content,
			final Map<String, String> metadata) {
		try {
			final StringBuilder sidecar = new StringBuilder();
			metadata.forEach((
					name,
					value) -> sidecar.append(name).append('=').append(value).append('\n'));
			FolderColdStorageRepository.writeAtomically(this.pathOf(key + FolderColdStorageRepository.METADATA_SUFFIX),
					sidecar.toString().getBytes(StandardCharsets.UTF_8));
			FolderColdStorageRepository.writeAtomically(this.pathOf(key), content);
		}
		catch (final IOException exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.folder.write.error", new Object[] { key }), exception);
		}
		return new ColdObject(key, (long) content.length);
	}

	/**
	 * @see ColdStorageRepository#load(String)
	 */
	@Override
	public Optional<byte[]> load(
			final String key) {
		final Path path = this.pathOf(key);
		try {
			return Files.isRegularFile(path) ? Optional.of(Files.readAllBytes(path)) : Optional.empty();
		}
		catch (final IOException exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.folder.read.error", new Object[] { key }), exception);
		}
	}

	/**
	 * @see ColdStorageRepository#list(String)
	 */
	@Override
	public List<String> list(
			final String prefix) {
		final List<String> keys = new ArrayList<>();
		if (Files.isDirectory(this.root)) {
			try (Stream<Path> paths = Files.walk(this.root)) {
				for (final Path path : paths.filter(Files::isRegularFile).toList()) {
					final String key = this.keyOf(path);
					if (key.startsWith(prefix) && !key.endsWith(FolderColdStorageRepository.METADATA_SUFFIX) && !key.endsWith(FolderColdStorageRepository.TEMPORARY_SUFFIX)) {
						keys.add(key);
					}
				}
			}
			catch (final IOException exception) {
				throw new IntegrationException(new SimpleMessage("coldTiering.folder.list.error", new Object[] { prefix }), exception);
			}
		}
		keys.sort(Comparator.naturalOrder());
		return keys;
	}

}
