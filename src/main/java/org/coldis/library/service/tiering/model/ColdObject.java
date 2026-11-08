package org.coldis.library.service.tiering.model;

/**
 * An object written to the cold tier.
 *
 * @param key       Object key (recorded in the index; the only way to address the object).
 * @param sizeBytes Stored size.
 */
public record ColdObject(String key, Long sizeBytes) {

}
