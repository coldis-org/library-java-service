package org.coldis.library.service.jms;

import io.micrometer.common.KeyValue;
import io.micrometer.common.lang.Nullable;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationRegistry;

/**
 * Simple observation.
 */
public class SimpleObservation implements Observation {

	ObservationRegistry registry;

	private final Context context = new Context();

	public SimpleObservation(final ObservationRegistry registry) {
		this.registry = registry;
	}

	@Override
	public Observation contextualName(
			@Nullable
			final String contextualName) {
		return this;
	}

	@Override
	public Observation parentObservation(
			@Nullable
			final Observation parentObservation) {
		return this;
	}

	@Override
	public Observation lowCardinalityKeyValue(
			final KeyValue keyValue) {
		return this;
	}

	@Override
	public Observation lowCardinalityKeyValue(
			final String key,
			final String value) {
		return this;
	}

	@Override
	public Observation highCardinalityKeyValue(
			final KeyValue keyValue) {
		return this;
	}

	@Override
	public Observation highCardinalityKeyValue(
			final String key,
			final String value) {
		return this;
	}

	@Override
	public Observation observationConvention(
			final ObservationConvention<?> observationConvention) {
		return this;
	}

	@Override
	public Observation error(
			final Throwable error) {
		return this;
	}

	@Override
	public Observation event(
			final Event event) {
		return this;
	}

	@Override
	public Observation start() {
		return this;
	}

	@Override
	public Context getContext() {
		return this.context;
	}

	@Override
	public void stop() {
	}

	@Override
	public Scope openScope() {
		return new SimpleScope(this.registry, this);
	}

	public class SimpleScope implements Scope {

		final ObservationRegistry registry;

		/**
		 * Observation.
		 */
		private final Observation currentObservation;

		public SimpleScope(final ObservationRegistry registry, final Observation currentObservation) {
			this.registry = registry;
			this.currentObservation = currentObservation;
			registry.setCurrentObservationScope(this);
		}

		@Override
		public Observation getCurrentObservation() {
			return this.currentObservation;
		}

		@Override
		public void close() {
			this.registry.setCurrentObservationScope(null);

		}

		@Override
		public void reset() {
			this.registry.setCurrentObservationScope(null);
		}

		@Override
		public void makeCurrent() {
			this.registry.setCurrentObservationScope(this);
		}
	}
}
