package org.coldis.library.service.tiering.repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.serialization.ObjectMapperHelper;
import org.coldis.library.service.tiering.model.BlockCodec;
import org.coldis.library.service.tiering.model.BlockEntry;
import org.coldis.library.service.tiering.model.ColdRow;
import org.coldis.library.service.tiering.model.ColumnValues;
import org.coldis.library.service.tiering.model.TieredRowKey;
import org.coldis.library.service.tiering.model.TieredTable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Decodes one block (frame) into rows: CRC32C check, decompression, one row per line, key
 * columns read from the row JSON.
 */
public final class ShardedBlockReader {

	/** Mapper. */
	private static final ObjectMapper OBJECT_MAPPER = ObjectMapperHelper.createMapper();

	/**
	 * No instances.
	 */
	private ShardedBlockReader() {
		super();
	}

	/**
	 * Canonical key segments of a row.
	 *
	 * @param  table Table.
	 * @param  row   Row JSON.
	 * @return       Formatted key values by column.
	 */
	public static Map<String, String> keyOf(
			final TieredTable table,
			final JsonNode row) {
		final Map<String, String> values = new LinkedHashMap<>();
		for (final String column : table.getKeyColumns()) {
			final JsonNode value = row.get(column);
			if ((value == null) || value.isNull()) {
				throw new IntegrationException(new SimpleMessage("coldTiering.cold.row.key.missing", new Object[] { table.getId(), column }));
			}
			values.put(column, ColumnValues.format(ColumnValues.parse(table.getColumnTypes().get(column), value.asText())));
		}
		return values;
	}

	/**
	 * Canonical segments of formatted key values.
	 *
	 * @param  table  Table.
	 * @param  values Formatted key values.
	 * @return        Segments, in key column order.
	 */
	public static List<String> segmentsOf(
			final TieredTable table,
			final Map<String, String> values) {
		final List<String> segments = new ArrayList<>();
		for (final String column : table.getKeyColumns()) {
			if (values.containsKey(column)) {
				segments.add(ColumnValues.toSegment(table.getColumnTypes().get(column), values.get(column)));
			}
		}
		return segments;
	}

	/**
	 * Decodes a frame into rows.
	 *
	 * @param  table Table.
	 * @param  codec Codec named by the index.
	 * @param  entry Block entry.
	 * @param  frame Frame bytes.
	 * @return       Rows.
	 */
	public static List<ColdRow> rows(
			final TieredTable table,
			final BlockCodec codec,
			final BlockEntry entry,
			final byte[] frame) {
		if ((frame.length != entry.length()) || !ShardedBlockWriter.crc32c(frame).equals(entry.crc32c())) {
			throw new IntegrationException(new SimpleMessage("coldTiering.cold.block.corrupt", new Object[] { table.getId() }));
		}
		final List<ColdRow> rows = new ArrayList<>(entry.rows());
		for (final String line : new String(codec.decompress(frame), StandardCharsets.UTF_8).split("\n")) {
			if (!line.isEmpty()) {
				try {
					rows.add(new ColdRow(new TieredRowKey(table.getId(), ShardedBlockReader.keyOf(table, ShardedBlockReader.OBJECT_MAPPER.readTree(line))), line));
				}
				catch (final IntegrationException exception) {
					throw exception;
				}
				catch (final Exception exception) {
					throw new IntegrationException(new SimpleMessage("coldTiering.cold.row.invalid", new Object[] { table.getId() }), exception);
				}
			}
		}
		return rows;
	}

}
