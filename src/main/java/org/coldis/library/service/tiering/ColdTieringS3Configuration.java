package org.coldis.library.service.tiering;

import org.coldis.library.service.tiering.repository.ColdStorageRepository;
import org.coldis.library.service.tiering.repository.S3ColdStorageRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 cold tier wiring ({@code storage=s3}, the default): the consumer provides the
 * {@link S3Client} bean (its credentials, region, endpoint and timeouts); the library only needs
 * the bucket.
 */
@Configuration
@ConditionalOnClass(S3Client.class)
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class ColdTieringS3Configuration {

	/**
	 * S3 cold storage on the consumer's {@link S3Client} bean (the unique one, or the one marked
	 * {@code @Primary} when the consumer has several).
	 *
	 * @param  s3Client Consumer's S3 client.
	 * @param  bucket   Bucket.
	 * @return          The repository.
	 */
	@Bean
	@ConditionalOnProperty(
			name = ColdTieringProperties.PREFIX + ".storage",
			havingValue = "s3",
			matchIfMissing = true
	)
	public ColdStorageRepository s3ColdStorageRepository(
			final ObjectProvider<S3Client> s3Client,
			@Value("${" + ColdTieringProperties.PREFIX + ".s3.bucket}")
			final String bucket) {
		return new S3ColdStorageRepository(s3Client.getObject(), bucket);
	}

}
