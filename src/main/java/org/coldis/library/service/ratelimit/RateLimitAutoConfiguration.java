package org.coldis.library.service.ratelimit;

import org.aspectj.lang.Aspects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Order(100)
@Configuration
public class RateLimitAutoConfiguration {
    /**
     * Create rate limit interceptor.
     *
     * @return the rate limit interceptor
     */
    @Bean(name = "rateLimitInterceptor")
    public RateLimitInterceptor createRateLimitInterceptor() {
        return Aspects.aspectOf(RateLimitInterceptor.class);
    }
}
