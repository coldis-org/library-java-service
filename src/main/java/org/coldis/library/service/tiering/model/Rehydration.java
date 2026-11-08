package org.coldis.library.service.tiering.model;

/**
 * What a cold read does with the payload it fetched.
 */
public enum Rehydration {

	/** Inserts the row back into the hot table (original carried columns, a fresh TTL clock) and serves it from there. */
	INSERT,

	/** Serves the payload as a {@link ColdRow}; the hot table is never written by a read. */
	NONE;

}
