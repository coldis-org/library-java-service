package org.coldis.library.service.tiering;

import java.util.Optional;

import org.coldis.library.service.tiering.model.ColdRow;

/**
 * The answer of a tiered read: a hot entity, or a row that only exists in the cold tier (a table
 * that does not rehydrate).
 *
 * @param <Type>  Entity type.
 * @param entity  Hot entity, or null when the row is cold only.
 * @param coldRow Cold row, or null when the entity is hot.
 */
public record TieredHit<Type>(Type entity, ColdRow coldRow) {

	/**
	 * The hot entity, when any.
	 *
	 * @return The entity.
	 */
	public Optional<Type> hot() {
		return Optional.ofNullable(this.entity);
	}

	/**
	 * The cold row, when the answer is cold only.
	 *
	 * @return The cold row.
	 */
	public Optional<ColdRow> cold() {
		return Optional.ofNullable(this.coldRow);
	}

}
