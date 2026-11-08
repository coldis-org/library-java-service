package org.coldis.library.test.service.tiering;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.coldis.library.service.tiering.model.ColumnValues;
import org.coldis.library.service.tiering.model.PathObjectKeyStrategy;
import org.coldis.library.service.tiering.model.TieredTable;
import org.coldis.library.service.tiering.repository.FolderColdStorageRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Column values, the path layout and the folder storage's file names, without Spring.
 */
public class ColumnValuesUnitTest {

	/** Path layout. */
	private static final PathObjectKeyStrategy STRATEGY = new PathObjectKeyStrategy();

	/**
	 * Asserts that values round-trip through their segment and, when the type keeps the order
	 * (every type a version column may have), that the segments sort as the values do.
	 *
	 * @param dataType   SQL type.
	 * @param ordered    Values in ascending order (string form).
	 * @param keepsOrder Whether the segments must keep the order.
	 */
	private static void assertSegments(
			final String dataType,
			final List<String> ordered,
			final Boolean keepsOrder) {
		String previous = null;
		for (final String value : ordered) {
			final String segment = ColumnValues.toSegment(dataType, value);
			Assertions.assertFalse(segment.contains("/"), segment);
			Assertions.assertTrue(ColumnValues.same(dataType, value, ColumnValues.fromSegment(dataType, segment)), segment);
			if (keepsOrder && (previous != null)) {
				Assertions.assertTrue(previous.compareTo(segment) < 0, previous + " < " + segment);
			}
			previous = segment;
		}
	}

	/**
	 * Every supported type round-trips and sorts.
	 */
	@Test
	public void testSegmentsRoundTripAndOrder() {
		// Text is URL-encoded: safe in a path, round-trips, but its order is not kept ("a b" encodes
		// to "a+b", after "a%2Fb"), which is why a version column must be temporal.
		ColumnValuesUnitTest.assertSegments("character varying", List.of("a b", "a/b", "abc", "ação", "b..", "é"), false);
		ColumnValuesUnitTest.assertSegments("text", List.of("", ".", "..", "x"), false);
		ColumnValuesUnitTest.assertSegments("bigint", List.of("0", "7", "42", "1000000", "9223372036854775807"), true);
		ColumnValuesUnitTest.assertSegments("integer", List.of("1", "10", "2147483647"), true);
		ColumnValuesUnitTest.assertSegments(ColumnValues.TIMESTAMP, List.of("2025-12-31T23:59:59.999999", "2026-01-10T12:00", "2026-01-10T12:00:00.000001"), true);
		ColumnValuesUnitTest.assertSegments(ColumnValues.TIMESTAMPTZ, List.of("2026-01-10T12:00-03:00", "2026-01-10T15:00:00.5Z", "2026-01-10T13:00-03:00"), true);
		ColumnValuesUnitTest.assertSegments(ColumnValues.DATE, List.of("2025-12-31", "2026-01-01"), true);
		Assertions.assertEquals("2026-01-10T15:00:00.000000Z", ColumnValues.toSegment(ColumnValues.TIMESTAMPTZ, "2026-01-10T12:00-03:00"));
		Assertions.assertEquals("0000000000000000042", ColumnValues.toSegment("bigint", "42"));
	}

	/**
	 * Entity values convert to and from column values.
	 */
	@Test
	public void testConversions() {
		final LocalDateTime local = LocalDateTime.of(2026, 1, 10, 12, 0);
		final Object column = ColumnValues.toColumnValue(ColumnValues.TIMESTAMPTZ, local);
		Assertions.assertInstanceOf(OffsetDateTime.class, column);
		Assertions.assertEquals(local, ColumnValues.toJavaValue(column, LocalDateTime.class));
		Assertions.assertEquals(local.toLocalDate(), LocalDate.parse(ColumnValues.ofMoment(ColumnValues.DATE, local)));
		Assertions.assertTrue(ColumnValues.same(ColumnValues.TIMESTAMPTZ, "2026-01-10T12:00-03:00", "2026-01-10T15:00Z"));
		Assertions.assertFalse(ColumnValues.same("bigint", "1", "2"));
		Assertions.assertEquals(local, ((OffsetDateTime) ColumnValues.parse(ColumnValues.TIMESTAMPTZ, ColumnValues.ofMoment(ColumnValues.TIMESTAMPTZ, local)))
				.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
	}

	/**
	 * The path layout and its inverse.
	 */
	@Test
	public void testPathLayout() {
		final TieredTable table = new TieredTable("events", "events", List.of("source", "occurred_at"));
		final String key = ColumnValuesUnitTest.STRATEGY.keyOf("cold/", table, List.of("web%2Fapp", "2026-01-10T12:00:00.000000Z"), ".json.gz");
		Assertions.assertEquals("cold/events/web%2Fapp/2026-01-10T12:00:00.000000Z.json.gz", key);
		Assertions.assertEquals("cold/events/web%2Fapp/", ColumnValuesUnitTest.STRATEGY.prefixOf("cold/", table, List.of("web%2Fapp")));
		Assertions.assertEquals("2026-01-10T12:00:00.000000Z", ColumnValuesUnitTest.STRATEGY.lastSegmentOf(key, ".json.gz"));
		Assertions.assertThrows(Exception.class, () -> new TieredTable("bad", "events; drop", List.of("source")));
		Assertions.assertThrows(Exception.class, () -> new TieredTable("bad", "events", List.of("Source")));
	}

	/**
	 * The folder storage keeps dot-only segments and lists in order.
	 *
	 * @throws Exception If the test fails.
	 */
	@Test
	public void testFolderStorageDotSegments() throws Exception {
		final FolderColdStorageRepository storage = new FolderColdStorageRepository(Files.createTempDirectory("cold-tiering-unit").toString());
		try {
			storage.store("t/../x.gz", new byte[] { 1 }, Map.of("k", "v"));
			storage.store("t/./y.gz", new byte[] { 2 }, Map.of());
			storage.store("t/a/z.gz", new byte[] { 3 }, Map.of());
			Assertions.assertEquals(List.of("t/../x.gz", "t/./y.gz", "t/a/z.gz"), storage.list("t/"));
			Assertions.assertArrayEquals(new byte[] { 1 }, storage.load("t/../x.gz").orElseThrow());
			Assertions.assertTrue(storage.load("x.gz").isEmpty());
			Assertions.assertTrue(Files.isRegularFile(storage.getRoot().resolve("t").resolve("%2E%2E").resolve("x.gz")));
			storage.delete("t/../x.gz");
			Assertions.assertTrue(storage.load("t/../x.gz").isEmpty());
		}
		finally {
			storage.reset();
			Files.deleteIfExists(storage.getRoot());
		}
	}

}
