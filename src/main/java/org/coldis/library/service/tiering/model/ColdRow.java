package org.coldis.library.service.tiering.model;

/**
 * A row read from the cold tier.
 *
 * @param key     Row key.
 * @param rowJson The whole row as JSON ({@code row_to_json} of the hot row when archived).
 */
public record ColdRow(TieredRowKey key, String rowJson) {

}
