package org.coldis.library.service.cache;

import org.coldis.library.helper.LocalPeriodicJobHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic job cleaner.
 */
@Component
public class PeriodicJobCleaner {

	/** Cleans the recent checks every hour. */
	@Scheduled(cron = "0 0 * * * *")
	public void cleanJobCache() {
		LocalPeriodicJobHelper.clearExpired();
	}

}
