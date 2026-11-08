package org.coldis.library.service.tiering;

import org.coldis.library.service.tiering.repository.ColdStorageRepository;
import org.coldis.library.service.tiering.repository.S3ColdStorageRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 cold tier wiring ({@code storage=s3}, the default): the consumer provides the
 * {@link S3Client} bean (its credentials, region, endpoint and timeouts); the library only needs
 * the bucket. A consumer with several S3 clients names the one for the cold tier
 * {@value #DEDICATED_CLIENT_BEAN}; otherwise its unique (or {@code @Primary}) client is used.
 */
@Configuration
@ConditionalOnClass(S3Client.class)
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class ColdTieringS3Configuration {

	/** Name of the consumer's S3 client bean dedicated to the cold tier, when it has several. */
	public static final String DEDICATED_CLIENT_BEAN = "coldTieringS3Client";

	/**
	 * S3 cold storage on the consumer's {@link S3Client} bean: the one named
	 * {@value #DEDICATED_CLIENT_BEAN} when present, else the unique one (or the one marked
	 * {@code @Primary}).
	 *
	 * @param  dedicatedS3Client Consumer's S3 client dedicated to the cold tier, if any.
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
			@Qualifier(ColdTieringS3Configuration.DEDICATED_CLIENT_BEAN)
			final ObjectProvider<S3Client> dedicatedS3Client,
			final ObjectProvider<S3Client> s3Client,
			@Value("${" + ColdTieringProperties.PREFIX + ".s3.bucket}")
			final String bucket) {
		final S3Client client = dedicatedS3Client.getIfAvailable(s3Client::getObject);
		return new S3ColdStorageRepository(client, bucket);
	}

}
