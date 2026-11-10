package org.coldis.library.service.tiering.repository;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.service.tiering.model.ColdTieringPropertyNames;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.tiering.model.ColdObject;
import org.coldis.library.service.tiering.model.ColdUpload;
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
		name = ColdTieringPropertyNames.STORAGE,
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
			@Value("${" + ColdTieringPropertyNames.PREFIX + ".folder.root}")
			final String root) {
		this.root = Path.of(root).toAbsolutePath().normalize();
	}

	/**
	 * A key segment as a file name: a segment that is only dots ({@code .}, {@code ..}) would
	 * mean the directory itself or its parent, so its dots are percent-encoded.
	 *
	 * @param  segment Key segment.
	 * @return         The file name.
	 */
	private static String fileNameOf(
			final String segment) {
		return segment.matches("\\.+") ? segment.replace(".", "%2E") : segment;
	}

	/**
	 * A file name as a key segment.
	 *
	 * @param  fileName File name.
	 * @return          The segment.
	 */
	private static String segmentOf(
			final String fileName) {
		return fileName.matches("(%2E)+") ? fileName.replace("%2E", ".") : fileName;
	}

	/**
	 * The path of an object key.
	 *
	 * @param  key Object key.
	 * @return     The path under the root.
	 */
	private Path pathOf(
			final String key) {
		Path path = this.root;
		for (final String segment : key.split("/")) {
			if (!segment.isEmpty()) {
				path = path.resolve(FolderColdStorageRepository.fileNameOf(segment));
			}
		}
		if (!path.normalize().startsWith(this.root) || path.equals(this.root)) {
			throw new IntegrationException(new SimpleMessage("coldTiering.folder.key.invalid"));
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
		final List<String> segments = new ArrayList<>();
		for (final Path name : this.root.relativize(path)) {
			segments.add(FolderColdStorageRepository.segmentOf(name.toString()));
		}
		return String.join("/", segments);
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
	 * @see ColdStorageRepository#delete(String)
	 */
	@Override
	public void delete(
			final String key) {
		try {
			Files.deleteIfExists(this.pathOf(key));
			Files.deleteIfExists(this.pathOf(key + FolderColdStorageRepository.METADATA_SUFFIX));
		}
		catch (final IOException exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.folder.delete.error"), exception);
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
			throw new IntegrationException(new SimpleMessage("coldTiering.folder.write.error"), exception);
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
			throw new IntegrationException(new SimpleMessage("coldTiering.folder.read.error"), exception);
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
				throw new IntegrationException(new SimpleMessage("coldTiering.folder.list.error"), exception);
			}
		}
		keys.sort(Comparator.naturalOrder());
		return keys;
	}


	/**
	 * @see ColdStorageRepository#loadRange(String, Long, Integer)
	 */
	@Override
	public Optional<byte[]> loadRange(
			final String key,
			final Long offset,
			final Integer length) {
		final Path path = this.pathOf(key);
		Optional<byte[]> content = Optional.empty();
		if (Files.isRegularFile(path)) {
			try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
				final byte[] range = new byte[(int) Math.min(length, Math.max(0, file.length() - offset))];
				file.seek(offset);
				file.readFully(range);
				content = Optional.of(range);
			}
			catch (final IOException exception) {
				throw new IntegrationException(new SimpleMessage("coldTiering.folder.read.error"), exception);
			}
		}
		return content;
	}

	/**
	 * @see ColdStorageRepository#head(String)
	 */
	@Override
	public Optional<ColdObject> head(
			final String key) {
		final Path path = this.pathOf(key);
		Optional<ColdObject> object = Optional.empty();
		if (Files.isRegularFile(path)) {
			try {
				object = Optional.of(new ColdObject(key, Files.size(path)));
			}
			catch (final IOException exception) {
				throw new IntegrationException(new SimpleMessage("coldTiering.folder.read.error"), exception);
			}
		}
		return object;
	}

	/**
	 * @see ColdStorageRepository#minimumPartSize()
	 */
	@Override
	public Integer minimumPartSize() {
		return 1;
	}

	/**
	 * @see ColdStorageRepository#beginUpload(String, Map)
	 */
	@Override
	public ColdUpload beginUpload(
			final String key,
			final Map<String, String> metadata) {
		final Path path = this.pathOf(key);
		final Path temporary = path.resolveSibling(path.getFileName() + FolderColdStorageRepository.TEMPORARY_SUFFIX);
		try {
			Files.createDirectories(path.getParent());
			Files.deleteIfExists(temporary);
			Files.createFile(temporary);
		}
		catch (final IOException exception) {
			throw new IntegrationException(new SimpleMessage("coldTiering.folder.write.error"), exception);
		}
		return new ColdUpload() {

			/** Bytes. */
			private Long size = 0L;

			@Override
			public void part(
					final byte[] content) {
				try {
					Files.write(temporary, content, StandardOpenOption.APPEND);
					this.size += content.length;
				}
				catch (final IOException exception) {
					throw new IntegrationException(new SimpleMessage("coldTiering.folder.write.error"), exception);
				}
			}

			@Override
			public ColdObject complete() {
				try {
					final StringBuilder sidecar = new StringBuilder();
					metadata.forEach((
							name,
							value) -> sidecar.append(name).append('=').append(value).append('\n'));
					FolderColdStorageRepository.writeAtomically(FolderColdStorageRepository.this.pathOf(key + FolderColdStorageRepository.METADATA_SUFFIX),
							sidecar.toString().getBytes(StandardCharsets.UTF_8));
					Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				}
				catch (final IOException exception) {
					throw new IntegrationException(new SimpleMessage("coldTiering.folder.write.error"), exception);
				}
				return new ColdObject(key, this.size);
			}

			@Override
			public void abort() {
				try {
					Files.deleteIfExists(temporary);
				}
				catch (final IOException exception) {
					throw new IntegrationException(new SimpleMessage("coldTiering.folder.delete.error"), exception);
				}
			}
		};
	}

}
