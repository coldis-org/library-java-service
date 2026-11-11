package org.coldis.library.service.batch;

import java.util.AbstractList;
import java.util.List;

/**
 * The result a batch GET delegate returns to postpone the run: nothing to do yet, but the run is
 * not over. The batch service keeps the cursor and the count, does not finish the run, and runs
 * the next step after the usual delay. An empty list still finishes the run; a null GET result
 * (or a missing GET delegate) is an error.
 *
 * @param <Type> Item type.
 */
public final class BatchPostponed<Type> extends AbstractList<Type> {

	/** The single instance. */
	private static final BatchPostponed<Object> INSTANCE = new BatchPostponed<>();

	/**
	 * Private constructor: use {@link #of()}.
	 */
	private BatchPostponed() {
		super();
	}

	/**
	 * The postponed GET result.
	 *
	 * @param  <Type> Item type.
	 * @return        The marker (an empty, immutable list).
	 */
	@SuppressWarnings("unchecked")
	public static <Type> List<Type> of() {
		return (List<Type>) BatchPostponed.INSTANCE;
	}

	/**
	 * Whether a GET result postpones the run.
	 *
	 * @param  batch GET result.
	 * @return       Whether it is the postponed marker.
	 */
	public static Boolean isPostponed(
			final List<?> batch) {
		return batch instanceof BatchPostponed;
	}

	/**
	 * @see java.util.AbstractList#get(int)
	 */
	@Override
	public Type get(
			final int index) {
		throw new IndexOutOfBoundsException(index);
	}

	/**
	 * @see java.util.AbstractCollection#size()
	 */
	@Override
	public int size() {
		return 0;
	}

}
