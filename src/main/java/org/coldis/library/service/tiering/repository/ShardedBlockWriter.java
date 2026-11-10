package org.coldis.library.service.tiering.repository;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.service.tiering.model.BlockCodec;
import org.coldis.library.service.tiering.model.BlockEntry;
import org.coldis.library.service.tiering.model.ColdUpload;

/**
 * Writes one shard: rows (JSON lines) in key order into compressed blocks, blocks into upload
 * parts, and the block index. Blocks are cut when their estimated compressed size reaches the
 * block size (the ratio of the previous block, 4:1 at first). Keys must arrive strictly
 * ascending in code-point order, or the shard fails.
 */
public class ShardedBlockWriter {

	/** Initial compression ratio estimate. */
	private static final double INITIAL_RATIO = 4.0;

	/** Codec. */
	private final BlockCodec codec;

	/** Upload. */
	private final ColdUpload upload;

	/** Target compressed block size. */
	private final Integer blockSize;

	/** Part size. */
	private final Integer partSize;

	/** Block buffer (uncompressed lines). */
	private final ByteArrayOutputStream block = new ByteArrayOutputStream();

	/** Part buffer (frames). */
	private final ByteArrayOutputStream part = new ByteArrayOutputStream();

	/** Index entries. */
	private final List<BlockEntry> entries = new ArrayList<>();

	/** Compression ratio estimate. */
	private double ratio = ShardedBlockWriter.INITIAL_RATIO;

	/** First key of the current block. */
	private List<String> blockFirst;

	/** Last key written. */
	private List<String> last;

	/** Rows in the current block. */
	private Integer blockRows = 0;

	/** Rows written. */
	private Long rows = 0L;

	/** Bytes written (compressed). */
	private Long sizeBytes = 0L;

	/**
	 * Constructor.
	 *
	 * @param codec           Codec.
	 * @param upload          Upload.
	 * @param blockSize       Target compressed block size.
	 * @param partSize        Part size.
	 * @param minimumPartSize Storage minimum part size.
	 */
	public ShardedBlockWriter(
			final BlockCodec codec,
			final ColdUpload upload,
			final Integer blockSize,
			final Integer partSize,
			final Integer minimumPartSize) {
		this.codec = codec;
		this.upload = upload;
		this.blockSize = blockSize;
		this.partSize = Math.max(partSize, minimumPartSize);
	}

	/**
	 * CRC32C of bytes as hex.
	 *
	 * @param  content Bytes.
	 * @return         Hex.
	 */
	public static String crc32c(
			final byte[] content) {
		final CRC32C crc = new CRC32C();
		crc.update(content);
		return Long.toHexString(crc.getValue());
	}

	/**
	 * Compresses the current block, appends the frame to the part and records the index entry.
	 */
	private void flushBlock() {
		if (this.blockRows > 0) {
			final byte[] content = this.block.toByteArray();
			final byte[] frame = this.codec.compress(content);
			this.entries.add(new BlockEntry(this.blockFirst, this.last, this.sizeBytes, frame.length, this.blockRows, ShardedBlockWriter.crc32c(frame)));
			this.part.writeBytes(frame);
			this.sizeBytes += frame.length;
			this.ratio = Math.max(1.0, (double) content.length / Math.max(1, frame.length));
			this.block.reset();
			this.blockRows = 0;
			this.blockFirst = null;
			if (this.part.size() >= this.partSize) {
				this.upload.part(this.part.toByteArray());
				this.part.reset();
			}
		}
	}

	/**
	 * Adds a row.
	 *
	 * @param key  Canonical key segments.
	 * @param line Row JSON (no line break).
	 */
	public void add(
			final List<String> key,
			final String line) {
		if ((this.last != null) && (BlockEntry.compareKeys(this.last, key) >= 0) && (this.last.size() == key.size())) {
			throw new IntegrationException(new SimpleMessage("coldTiering.export.key.order", new Object[] { this.rows }));
		}
		if (this.blockFirst == null) {
			this.blockFirst = List.copyOf(key);
		}
		this.block.writeBytes(line.getBytes(StandardCharsets.UTF_8));
		this.block.write('\n');
		this.blockRows++;
		this.rows++;
		this.last = List.copyOf(key);
		if ((this.block.size() / this.ratio) >= this.blockSize) {
			this.flushBlock();
		}
	}

	/**
	 * Flushes the last block and part and completes the upload.
	 *
	 * @return The index entries.
	 */
	public List<BlockEntry> finish() {
		this.flushBlock();
		if (this.part.size() > 0) {
			this.upload.part(this.part.toByteArray());
			this.part.reset();
		}
		this.upload.complete();
		return List.copyOf(this.entries);
	}

	/**
	 * Rows written.
	 *
	 * @return Rows.
	 */
	public Long getRows() {
		return this.rows;
	}

	/**
	 * Compressed bytes written.
	 *
	 * @return Bytes.
	 */
	public Long getSizeBytes() {
		return this.sizeBytes;
	}

}
