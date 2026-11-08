package org.coldis.library.service.tiering;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.coldis.library.exception.IntegrationException;
import org.coldis.library.model.SimpleMessage;

/**
 * Converts key values between their string form ({@link TieredRowKey}), the JDBC objects of the
 * column's SQL type (as {@code information_schema.columns.data_type} names it) and their
 * canonical object-key segment (URL-safe, fixed width, so the lexicographic order of segments is
 * the order of the values).
 */
public final class ColumnValues {

	/** Fixed-width timestamp format. */
	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

	/** Fixed-width integer format (non-negative values keep their order). */
	private static final String INTEGER_FORMAT = "%019d";

	/**
	 * No instances.
	 */
	private ColumnValues() {
		super();
	}

	/**
	 * The JDBC object of a key value.
	 *
	 * @param  dataType SQL type.
	 * @param  value    String value.
	 * @return          The object.
	 */
	public static Object parse(
			final String dataType,
			final String value) {
		return switch (dataType) {
			case "bigint" -> Long.valueOf(value);
			case "integer", "smallint" -> Integer.valueOf(value);
			case "uuid" -> UUID.fromString(value);
			case "date" -> LocalDate.parse(value);
			case "timestamp without time zone" -> LocalDateTime.parse(value);
			case "timestamp with time zone" -> OffsetDateTime.parse(value);
			case "text", "character varying", "character" -> value;
			default -> throw new IntegrationException(new SimpleMessage("coldTiering.key.type.unsupported", new Object[] { dataType }));
		};
	}

	/**
	 * The string form of a key value read from the database or given by a consumer.
	 *
	 * @param  value JDBC object.
	 * @return       The string.
	 */
	public static String format(
			final Object value) {
		return switch (value) {
			case java.sql.Timestamp timestamp -> timestamp.toLocalDateTime().toString();
			case java.sql.Date date -> date.toLocalDate().toString();
			default -> value.toString();
		};
	}

	/**
	 * A value below every value of a type (the backfill cursor start).
	 *
	 * @param  dataType SQL type.
	 * @return          The lowest value.
	 */
	public static Object lowest(
			final String dataType) {
		return switch (dataType) {
			case "bigint" -> Long.MIN_VALUE;
			case "integer", "smallint" -> Integer.MIN_VALUE;
			case "uuid" -> new UUID(0L, 0L);
			case "date" -> LocalDate.of(1, 1, 1);
			case "timestamp without time zone" -> LocalDateTime.of(1, 1, 1, 0, 0);
			case "timestamp with time zone" -> OffsetDateTime.of(1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			case "text", "character varying", "character" -> "";
			default -> throw new IntegrationException(new SimpleMessage("coldTiering.key.type.unsupported", new Object[] { dataType }));
		};
	}

	/**
	 * The canonical object-key segment of a key value.
	 *
	 * @param  dataType SQL type.
	 * @param  value    String value.
	 * @return          The segment.
	 */
	public static String toSegment(
			final String dataType,
			final String value) {
		final Object object = ColumnValues.parse(dataType, value);
		return switch (object) {
			case Long number -> String.format(ColumnValues.INTEGER_FORMAT, number);
			case Integer number -> String.format(ColumnValues.INTEGER_FORMAT, number);
			case LocalDateTime timestamp -> ColumnValues.TIMESTAMP_FORMAT.format(timestamp);
			case OffsetDateTime timestamp -> ColumnValues.TIMESTAMP_FORMAT.format(timestamp.withOffsetSameInstant(ZoneOffset.UTC)) + "Z";
			case String text -> URLEncoder.encode(text, StandardCharsets.UTF_8);
			default -> object.toString();
		};
	}

	/**
	 * The string form of a key value from its canonical segment.
	 *
	 * @param  dataType SQL type.
	 * @param  segment  Segment.
	 * @return          The string value.
	 */
	public static String fromSegment(
			final String dataType,
			final String segment) {
		return switch (dataType) {
			case "bigint" -> Long.valueOf(segment).toString();
			case "integer", "smallint" -> Integer.valueOf(segment).toString();
			case "timestamp without time zone" -> LocalDateTime.parse(segment, ColumnValues.TIMESTAMP_FORMAT).toString();
			case "timestamp with time zone" -> OffsetDateTime.parse(segment.substring(0, segment.length() - 1), ColumnValues.TIMESTAMP_FORMAT.withZone(ZoneOffset.UTC))
					.toString();
			case "text", "character varying", "character" -> URLDecoder.decode(segment, StandardCharsets.UTF_8);
			default -> segment;
		};
	}

	/**
	 * The JDBC objects of a row key.
	 *
	 * @param  table       Table.
	 * @param  columnTypes Column types.
	 * @param  key         Row key.
	 * @return             The objects by column.
	 */
	public static Map<String, Object> parseKey(
			final TieredTable table,
			final Map<String, String> columnTypes,
			final TieredRowKey key) {
		final Map<String, Object> values = new LinkedHashMap<>();
		for (final String column : table.getKeyColumns()) {
			final String value = key.values().get(column);
			if (value == null) {
				throw new IntegrationException(new SimpleMessage("coldTiering.key.incomplete", new Object[] { table.getId(), column }));
			}
			values.put(column, ColumnValues.parse(columnTypes.get(column), value));
		}
		return values;
	}

	/**
	 * The canonical segments of the leading key columns present in a key (prefix or full key).
	 *
	 * @param  table       Table.
	 * @param  columnTypes Column types.
	 * @param  values      Key values (string form), by column.
	 * @return             The segments, in key column order, up to the first missing column.
	 */
	public static List<String> toSegments(
			final TieredTable table,
			final Map<String, String> columnTypes,
			final Map<String, String> values) {
		final List<String> segments = new ArrayList<>();
		Boolean complete = true;
		for (final String column : table.getKeyColumns()) {
			final String value = values.get(column);
			if (complete && (value != null)) {
				segments.add(ColumnValues.toSegment(columnTypes.get(column), value));
			}
			else {
				complete = false;
			}
		}
		return segments;
	}

}
