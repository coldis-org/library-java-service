package org.coldis.library.service.tiering;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 cold tier wiring ({@code storage=s3}, the default): the consumer provides the
 * {@link S3Client} bean (its credentials, region and endpoint), named by
 * {@code org.coldis.library.service.tiering.s3.client-bean}; the library only needs the bucket.
 */
@Configuration
@ConditionalOnClass(S3Client.class)
@ConditionalOnProperty(
		name = ColdTieringProperties.ENABLED_PROPERTY,
		matchIfMissing = false
)
public class ColdTieringS3Configuration {

	/**
	 * S3 cold storage.
	 *
	 * @param  beanFactory Bean factory.
	 * @param  clientBean  Name of the consumer's S3 client bean.
	 * @param  bucket      Bucket.
	 * @return             The repository.
	 */
	@Bean
	@ConditionalOnProperty(
			name = ColdTieringProperties.PREFIX + ".storage",
			havingValue = "s3",
			matchIfMissing = true
	)
	public ColdStorageRepository s3ColdStorageRepository(
			final BeanFactory beanFactory,
			@Value("${" + ColdTieringProperties.PREFIX + ".s3.client-bean:s3Client}")
			final String clientBean,
			@Value("${" + ColdTieringProperties.PREFIX + ".s3.bucket}")
			final String bucket) {
		return new S3ColdStorageRepository(beanFactory.getBean(clientBean, S3Client.class), bucket);
	}

}
