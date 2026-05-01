package org.coldis.library.service.statistics;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.coldis.library.helper.DateTimeHelper;
import org.springframework.beans.factory.annotation.Value;

/**
 * Custom statistics event repository operations. Implements the batched upsert against Postgres
 * with parallel-array binding via {@code unnest}.
 *
 * <p>Two SQL flavors are supported, selected by
 * {@code org.coldis.library.service.statistics.event.upsert-strategy}:
 * <ul>
 *   <li>{@code merge} (default) — uses {@code MERGE ... RETURNING merge_action()}; requires
 *       Postgres 17 or newer.</li>
 *   <li>{@code on-conflict} — uses {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING
 *       (xmax = 0) AS was_inserted}; works on Postgres 9.5+.</li>
 * </ul>
 */
public class StatisticsEventRepositoryImpl implements StatisticsEventRepositoryCustom {

  /** Upsert strategy property name. */
  static final String UPSERT_STRATEGY_PROPERTY =
      "org.coldis.library.service.statistics.event.upsert-strategy";
  
  /** Strategy value: PostgreSQL 17+ {@code MERGE ... RETURNING merge_action()}. */
  static final String STRATEGY_MERGE = "merge";

  /** Strategy value: portable {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}. */
  static final String STRATEGY_ON_CONFLICT = "on-conflict";

  /**
   * MERGE-based single-statement CTE upsert (Postgres 17+): input → existing snapshot → MERGE
   * (insert/update with latest-emission-wins predicate) → join everything back. Returns one row
   * per input key in input order with {@code applied}, {@code was_inserted} and the pre-update
   * state needed for summary deltas. Caller must hold the per-key advisory lock for the keys
   * being upserted.
   */
  private static final String UPSERT_BATCH_SQL_MERGE =
      "WITH input AS ("
          + " SELECT * FROM unnest("
          + "  CAST(:contexts AS text[]),"
          + "  CAST(:ownerKeys AS text[]),"
          + "  CAST(:dimensionNames AS text[]),"
          + "  CAST(:dateTimes AS timestamptz[]),"
          + "  CAST(:dimensionValues AS text[]),"
          + "  CAST(:weights AS numeric[]),"
          + "  CAST(:expiredAts AS timestamptz[]),"
          + "  CAST(:emittedAts AS timestamptz[]),"
          + "  CAST(:createdAts AS timestamptz[]),"
          + "  CAST(:updatedAts AS timestamptz[])"
          + " ) WITH ORDINALITY AS t(context, owner_key, dimension_name, date_time,"
          + "        dimension_value, weight, expired_at, emitted_at, created_at,"
          + "        updated_at, ord)"
          + "), existing AS ("
          + " SELECT s.context, s.owner_key, s.dimension_name,"
          + "        s.dimension_value AS old_dim, s.weight AS old_weight,"
          + "        s.date_time AS old_dt"
          + " FROM statistics_event s"
          + " WHERE (s.context, s.owner_key, s.dimension_name) IN ("
          + "   SELECT context, owner_key, dimension_name FROM input"
          + " )"
          + "), merged AS ("
          + " MERGE INTO statistics_event AS t"
          + " USING input AS i"
          + " ON t.context = i.context AND t.owner_key = i.owner_key"
          + "    AND t.dimension_name = i.dimension_name"
          + " WHEN MATCHED AND (t.emitted_at IS NULL OR i.emitted_at >= t.emitted_at) THEN"
          + "   UPDATE SET date_time = i.date_time,"
          + "              dimension_value = i.dimension_value,"
          + "              weight = i.weight,"
          + "              expired_at = i.expired_at,"
          + "              emitted_at = i.emitted_at,"
          + "              updated_at = i.updated_at"
          + " WHEN NOT MATCHED THEN"
          + "   INSERT (context, owner_key, dimension_name, date_time, dimension_value, weight,"
          + "           expired_at, emitted_at, created_at, updated_at)"
          + "   VALUES (i.context, i.owner_key, i.dimension_name, i.date_time, i.dimension_value,"
          + "           i.weight, i.expired_at, i.emitted_at, i.created_at, i.updated_at)"
          + " RETURNING merge_action() AS action,"
          + "           t.context AS context, t.owner_key AS owner_key,"
          + "           t.dimension_name AS dimension_name"
          + ") SELECT i.context, i.owner_key, i.dimension_name,"
          + "         m.action IS NOT NULL AS applied,"
          + "         COALESCE(m.action = 'INSERT', false) AS was_inserted,"
          + "         e.old_dim, e.old_weight, e.old_dt"
          + "  FROM input i"
          + "  LEFT JOIN merged m ON i.context = m.context"
          + "    AND i.owner_key = m.owner_key AND i.dimension_name = m.dimension_name"
          + "  LEFT JOIN existing e ON i.context = e.context"
          + "    AND i.owner_key = e.owner_key AND i.dimension_name = e.dimension_name"
          + "  ORDER BY i.ord";

  /**
   * ON CONFLICT-based single-statement CTE upsert (Postgres 9.5+): identical structure to the
   * MERGE flavor, except the middle CTE uses {@code INSERT ... ON CONFLICT DO UPDATE} with a
   * {@code WHERE} clause for the latest-emission-wins predicate, and {@code (xmax = 0)} on the
   * RETURNING row to distinguish freshly inserted rows from updates.
   */
  private static final String UPSERT_BATCH_SQL_ON_CONFLICT =
      "WITH input AS ("
          + " SELECT * FROM unnest("
          + "  CAST(:contexts AS text[]),"
          + "  CAST(:ownerKeys AS text[]),"
          + "  CAST(:dimensionNames AS text[]),"
          + "  CAST(:dateTimes AS timestamptz[]),"
          + "  CAST(:dimensionValues AS text[]),"
          + "  CAST(:weights AS numeric[]),"
          + "  CAST(:expiredAts AS timestamptz[]),"
          + "  CAST(:emittedAts AS timestamptz[]),"
          + "  CAST(:createdAts AS timestamptz[]),"
          + "  CAST(:updatedAts AS timestamptz[])"
          + " ) WITH ORDINALITY AS t(context, owner_key, dimension_name, date_time,"
          + "        dimension_value, weight, expired_at, emitted_at, created_at,"
          + "        updated_at, ord)"
          + "), existing AS ("
          + " SELECT s.context, s.owner_key, s.dimension_name,"
          + "        s.dimension_value AS old_dim, s.weight AS old_weight,"
          + "        s.date_time AS old_dt"
          + " FROM statistics_event s"
          + " WHERE (s.context, s.owner_key, s.dimension_name) IN ("
          + "   SELECT context, owner_key, dimension_name FROM input"
          + " )"
          + "), upserted AS ("
          + " INSERT INTO statistics_event ("
          + "   context, owner_key, dimension_name, date_time, dimension_value, weight,"
          + "   expired_at, emitted_at, created_at, updated_at"
          + " ) SELECT context, owner_key, dimension_name, date_time, dimension_value, weight,"
          + "          expired_at, emitted_at, created_at, updated_at FROM input"
          + " ON CONFLICT (context, owner_key, dimension_name) DO UPDATE SET"
          + "   date_time = EXCLUDED.date_time,"
          + "   dimension_value = EXCLUDED.dimension_value,"
          + "   weight = EXCLUDED.weight,"
          + "   expired_at = EXCLUDED.expired_at,"
          + "   emitted_at = EXCLUDED.emitted_at,"
          + "   updated_at = EXCLUDED.updated_at"
          + " WHERE statistics_event.emitted_at IS NULL"
          + "    OR EXCLUDED.emitted_at >= statistics_event.emitted_at"
          + " RETURNING context, owner_key, dimension_name, (xmax = 0) AS was_inserted"
          + ") SELECT i.context, i.owner_key, i.dimension_name,"
          + "         u.was_inserted IS NOT NULL AS applied,"
          + "         COALESCE(u.was_inserted, false) AS was_inserted,"
          + "         e.old_dim, e.old_weight, e.old_dt"
          + "  FROM input i"
          + "  LEFT JOIN upserted u ON i.context = u.context"
          + "    AND i.owner_key = u.owner_key AND i.dimension_name = u.dimension_name"
          + "  LEFT JOIN existing e ON i.context = e.context"
          + "    AND i.owner_key = e.owner_key AND i.dimension_name = e.dimension_name"
          + "  ORDER BY i.ord";

  /** Configured upsert strategy ({@code merge} or {@code on-conflict}). Defaults to {@code merge}. */
  @Value("${" + StatisticsEventRepositoryImpl.UPSERT_STRATEGY_PROPERTY + ":"
      + StatisticsEventRepositoryImpl.STRATEGY_MERGE + "}")
  private String upsertStrategy;

  /** Entity manager. */
  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<StatisticsEventUpsertResult> upsertBatch(final List<StatisticsEvent> events) {
    if (events == null || events.isEmpty()) {
      return Collections.emptyList();
    }
    final int size = events.size();
    final String[] contexts = new String[size];
    final String[] owners = new String[size];
    final String[] dims = new String[size];
    final LocalDateTime[] dateTimes = new LocalDateTime[size];
    final String[] values = new String[size];
    final BigDecimal[] weights = new BigDecimal[size];
    final LocalDateTime[] expiredAts = new LocalDateTime[size];
    final LocalDateTime[] emittedAts = new LocalDateTime[size];
    final LocalDateTime[] createdAts = new LocalDateTime[size];
    final LocalDateTime[] updatedAts = new LocalDateTime[size];
    final LocalDateTime now = DateTimeHelper.getCurrentLocalDateTime();
    for (int index = 0; index < size; index++) {
      final StatisticsEvent event = events.get(index);
      contexts[index] = event.getContext();
      owners[index] = event.getOwnerKey();
      dims[index] = event.getDimensionName();
      dateTimes[index] = event.getDateTime();
      values[index] = event.getDimensionValue();
      weights[index] = event.getWeight();
      expiredAts[index] = event.getExpiredAt();
      emittedAts[index] = event.getEmittedAt();
      createdAts[index] = (event.getCreatedAt() == null) ? now : event.getCreatedAt();
      updatedAts[index] = now;
    }
    final String sql =
        StatisticsEventRepositoryImpl.STRATEGY_ON_CONFLICT.equalsIgnoreCase(this.upsertStrategy)
            ? StatisticsEventRepositoryImpl.UPSERT_BATCH_SQL_ON_CONFLICT
            : StatisticsEventRepositoryImpl.UPSERT_BATCH_SQL_MERGE;
    final Query query = this.entityManager.createNativeQuery(sql);
    query.setParameter("contexts", contexts);
    query.setParameter("ownerKeys", owners);
    query.setParameter("dimensionNames", dims);
    query.setParameter("dateTimes", dateTimes);
    query.setParameter("dimensionValues", values);
    query.setParameter("weights", weights);
    query.setParameter("expiredAts", expiredAts);
    query.setParameter("emittedAts", emittedAts);
    query.setParameter("createdAts", createdAts);
    query.setParameter("updatedAts", updatedAts);
    @SuppressWarnings("unchecked")
    final List<Object[]> rows = query.getResultList();
    final List<StatisticsEventUpsertResult> results = new ArrayList<>(rows.size());
    for (final Object[] row : rows) {
      results.add(
          new StatisticsEventUpsertResult(
              (String) row[0],
              (String) row[1],
              (String) row[2],
              (Boolean) row[3],
              (Boolean) row[4],
              (String) row[5],
              (BigDecimal) row[6],
              StatisticsEventRepositoryImpl.toLocalDateTime(row[7])));
    }
    return results;
  }

  /**
   * Coerces the JDBC timestamp value returned by the native query to {@link LocalDateTime}.
   * Postgres JDBC + Hibernate 6 may return {@link Instant}, {@link OffsetDateTime},
   * {@link LocalDateTime}, or {@link java.sql.Timestamp} depending on column type and dialect
   * configuration. {@link Instant} (the typical {@code timestamptz} mapping) is converted via the
   * JVM default zone, matching how {@code AbstractTimestampableEntity} treats local times.
   */
  private static LocalDateTime toLocalDateTime(final Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof Instant instant) {
      return instant.atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
    if (value instanceof OffsetDateTime offsetDateTime) {
      return offsetDateTime.toLocalDateTime();
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    throw new IllegalStateException(
        "Unexpected timestamp type from upsertBatch: " + value.getClass().getName());
  }
}
