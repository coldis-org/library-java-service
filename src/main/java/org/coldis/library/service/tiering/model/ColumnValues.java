package org.coldis.library.service.tiering.model;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

	/** Timestamp without time zone. */
	public static final String TIMESTAMP = "timestamp without time zone";

	/** Timestamp with time zone. */
	public static final String TIMESTAMPTZ = "timestamp with time zone";

	/** Date. */
	public static final String DATE = "date";

	/** Types a versioned table's last key column may have (an as-of moment converts to them). */
	public static final Set<String> TEMPORAL_TYPES = Set.of(ColumnValues.TIMESTAMP, ColumnValues.TIMESTAMPTZ, ColumnValues.DATE);

	/** Text types. */
	public static final Set<String> TEXT_TYPES = Set.of("text", "character varying", "character");

	/** Integer types. */
	public static final Set<String> INTEGER_TYPES = Set.of("bigint", "integer", "smallint");

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
			case ColumnValues.DATE -> LocalDate.parse(value);
			case ColumnValues.TIMESTAMP -> LocalDateTime.parse(value);
			case ColumnValues.TIMESTAMPTZ -> OffsetDateTime.parse(value);
			case "text", "character varying", "character" -> value;
			default -> throw new IntegrationException(new SimpleMessage("coldTiering.key.type.unsupported", new Object[] { dataType }));
		};
	}

	/**
	 * The string form of a value read from the database or given by a consumer.
	 *
	 * @param  value JDBC object.
	 * @return       The string.
	 */
	public static String format(
			final Object value) {
		if (value == null) {
			throw new IntegrationException(new SimpleMessage("coldTiering.value.null"));
		}
		return switch (value) {
			case java.sql.Timestamp timestamp -> timestamp.toLocalDateTime().toString();
			case java.sql.Date date -> date.toLocalDate().toString();
			default -> value.toString();
		};
	}

	/**
	 * A consumer's value (an entity field, an id property) as the object of the column's SQL type:
	 * a {@code timestamptz} column mapped as {@code LocalDateTime}, {@code Instant} or
	 * {@code ZonedDateTime} becomes an {@code OffsetDateTime}; a {@code timestamp} column mapped
	 * as {@code OffsetDateTime} becomes a {@code LocalDateTime}.
	 *
	 * @param  dataType SQL type.
	 * @param  value    Consumer's value.
	 * @return          The column value.
	 */
	public static Object toColumnValue(
			final String dataType,
			final Object value) {
		Object columnValue = value;
		if (ColumnValues.TIMESTAMPTZ.equals(dataType)) {
			columnValue = switch (value) {
				case LocalDateTime moment -> moment.atZone(ZoneId.systemDefault()).toOffsetDateTime();
				case Instant moment -> moment.atOffset(ZoneOffset.UTC);
				case ZonedDateTime moment -> moment.toOffsetDateTime();
				case java.sql.Timestamp moment -> moment.toLocalDateTime().atZone(ZoneId.systemDefault()).toOffsetDateTime();
				default -> value;
			};
		}
		else if (ColumnValues.TIMESTAMP.equals(dataType)) {
			columnValue = switch (value) {
				case OffsetDateTime moment -> moment.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
				case ZonedDateTime moment -> moment.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
				case Instant moment -> LocalDateTime.ofInstant(moment, ZoneId.systemDefault());
				default -> value;
			};
		}
		return columnValue;
	}

	/**
	 * A column value as the Java type an entity maps the column with (the inverse of
	 * {@link #toColumnValue(String, Object)}), for JPQL parameters.
	 *
	 * @param  value  Column value.
	 * @param  target Entity field type.
	 * @return        The value of the target type.
	 */
	public static Object toJavaValue(
			final Object value,
			final Class<?> target) {
		Object javaValue = value;
		if (value instanceof final OffsetDateTime moment) {
			if (LocalDateTime.class.equals(target)) {
				javaValue = moment.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
			}
			else if (Instant.class.equals(target)) {
				javaValue = moment.toInstant();
			}
			else if (ZonedDateTime.class.equals(target)) {
				javaValue = moment.atZoneSameInstant(ZoneId.systemDefault());
			}
		}
		else if ((value instanceof final LocalDateTime moment) && OffsetDateTime.class.equals(target)) {
			javaValue = moment.atZone(ZoneId.systemDefault()).toOffsetDateTime();
		}
		else if ((value instanceof final Number number) && (Long.class.equals(target) || Long.TYPE.equals(target))) {
			javaValue = number.longValue();
		}
		else if ((value instanceof final Number number) && (Integer.class.equals(target) || Integer.TYPE.equals(target))) {
			javaValue = number.intValue();
		}
		else if ((value instanceof final Number number) && (Short.class.equals(target) || Short.TYPE.equals(target))) {
			javaValue = number.shortValue();
		}
		return javaValue;
	}

	/**
	 * Reads a column with the object type of its SQL type (so a {@code timestamptz} keeps its
	 * offset).
	 *
	 * @param  resultSet Result set.
	 * @param  column    Column.
	 * @param  dataType  SQL type.
	 * @return           The value.
	 * @throws SQLException If the read fails.
	 */
	public static Object read(
			final ResultSet resultSet,
			final String column,
			final String dataType) throws SQLException {
		return switch (dataType) {
			case ColumnValues.TIMESTAMPTZ -> resultSet.getObject(column, OffsetDateTime.class);
			case ColumnValues.TIMESTAMP -> resultSet.getObject(column, LocalDateTime.class);
			case ColumnValues.DATE -> resultSet.getObject(column, LocalDate.class);
			default -> resultSet.getObject(column);
		};
	}

	/**
	 * Whether two values of a column are the same (instants compared as instants).
	 *
	 * @param  dataType SQL type.
	 * @param  first    First value (string form).
	 * @param  second   Second value (string form).
	 * @return          Whether they are the same value.
	 */
	public static Boolean isSameValue(
			final String dataType,
			final String first,
			final String second) {
		final Object firstValue = ColumnValues.parse(dataType, first);
		final Object secondValue = ColumnValues.parse(dataType, second);
		return (firstValue instanceof final OffsetDateTime firstMoment) && (secondValue instanceof final OffsetDateTime secondMoment)
				? firstMoment.isEqual(secondMoment)
				: Objects.equals(firstValue, secondValue);
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
			case ColumnValues.DATE -> LocalDate.of(1, 1, 1);
			case ColumnValues.TIMESTAMP -> LocalDateTime.of(1, 1, 1, 0, 0);
			case ColumnValues.TIMESTAMPTZ -> OffsetDateTime.of(1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			case "text", "character varying", "character" -> "";
			default -> throw new IntegrationException(new SimpleMessage("coldTiering.key.type.unsupported", new Object[] { dataType }));
		};
	}

	/**
	 * An as-of moment as the string form of a temporal column.
	 *
	 * @param  dataType SQL type (one of {@link #TEMPORAL_TYPES}).
	 * @param  moment   Moment.
	 * @return          The string form.
	 */
	public static String ofMoment(
			final String dataType,
			final LocalDateTime moment) {
		return switch (dataType) {
			case ColumnValues.TIMESTAMP -> moment.toString();
			case ColumnValues.TIMESTAMPTZ -> moment.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
			case ColumnValues.DATE -> moment.toLocalDate().toString();
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
			case ColumnValues.TIMESTAMP -> LocalDateTime.parse(segment, ColumnValues.TIMESTAMP_FORMAT).toString();
			case ColumnValues.TIMESTAMPTZ -> OffsetDateTime
					.parse(segment.substring(0, segment.length() - 1), ColumnValues.TIMESTAMP_FORMAT.withZone(ZoneOffset.UTC)).toString();
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
	 * Canonical segment of a row key's last key column (the version/time of a versioned table).
	 *
	 * @param  table Table.
	 * @param  key   Key.
	 * @return       Segment.
	 */
	public static String lastSegmentOf(
			final TieredTable table,
			final TieredRowKey key) {
		return ColumnValues.toSegment(table.getColumnTypes().get(table.getLastKeyColumn()), key.values().get(table.getLastKeyColumn()));
	}

	/**
	 * Canonical segments of a table's key values, in key column order.
	 *
	 * @param  table       Table.
	 * @param  columnTypes Column types.
	 * @param  values      Formatted values by column.
	 * @return             Segments.
	 */
	public static List<String> toSegments(
			final TieredTable table,
			final Map<String, String> columnTypes,
			final Map<String, String> values) {
		final List<String> segments = new ArrayList<>();
		Boolean isContiguous = true;
		for (final String column : table.getKeyColumns()) {
			final String value = values.get(column);
			if (isContiguous && (value != null)) {
				segments.add(ColumnValues.toSegment(columnTypes.get(column), value));
			}
			else {
				isContiguous = false;
			}
		}
		return segments;
	}

}
