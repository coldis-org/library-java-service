package org.coldis.library.service.tiering.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A leaf partition of a range-partitioned table, with its bounds as the catalog prints them.
 * Bounds are parsed as timestamps when they are literals; {@code MINVALUE}/{@code MAXVALUE} and a
 * default partition leave them null and mark the partition open-ended.
 */
public record LeafPartition(String name, String rangeFrom, String rangeTo, LocalDateTime from, LocalDateTime to, Boolean isOpenEnded) {

	/** Bound expression. */
	private static final Pattern BOUNDS = Pattern.compile("FOR VALUES FROM \\((.*)\\) TO \\((.*)\\)");

	/** Literal timestamp. */
	private static final DateTimeFormatter LITERAL = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd[ HH:mm:ss]")
			.optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd().parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
			.parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0).parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0).toFormatter();

	/**
	 * Parses a literal bound.
	 *
	 * @param  bound Bound text.
	 * @return       Moment, or null for an infinite bound.
	 */
	private static LocalDateTime parseBound(
			final String bound) {
		LocalDateTime moment = null;
		final String trimmed = bound.trim();
		if (!"MINVALUE".equals(trimmed) && !"MAXVALUE".equals(trimmed)) {
			final String literal = trimmed.replaceAll("^'|'(::[a-z ]+)?$", "").replaceAll("'::[a-z ]+$", "");
			moment = LocalDateTime.parse(literal, LeafPartition.LITERAL);
		}
		return moment;
	}

	/**
	 * Builds a partition from the catalog's bound expression.
	 *
	 * @param  name       Partition name.
	 * @param  expression {@code pg_get_expr(relpartbound)}.
	 * @return            Partition.
	 */
	public static LeafPartition of(
			final String name,
			final String expression) {
		LeafPartition partition = new LeafPartition(name, null, null, null, null, true);
		final Matcher matcher = LeafPartition.BOUNDS.matcher(expression == null ? "" : expression);
		if (matcher.find()) {
			final LocalDateTime from = LeafPartition.parseBound(matcher.group(1));
			final LocalDateTime to = LeafPartition.parseBound(matcher.group(2));
			partition = new LeafPartition(name, matcher.group(1).trim(), matcher.group(2).trim(), from, to, (from == null) || (to == null));
		}
		return partition;
	}

	/**
	 * Whether a moment falls in the partition's range.
	 *
	 * @param  moment Moment.
	 * @return        Contains.
	 */
	public Boolean contains(
			final LocalDateTime moment) {
		return ((this.from == null) || !moment.isBefore(this.from)) && ((this.to == null) || moment.isBefore(this.to));
	}

}
