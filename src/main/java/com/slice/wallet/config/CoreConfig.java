package com.slice.wallet.config;

import com.slice.wallet.common.IdGenerator;
import com.slice.wallet.security.CallerArgumentResolver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The composition root for everything that is not auto-configured.
 *
 * <p>Small on purpose. If this file ever grows past a screen it usually means business decisions
 * have started leaking into configuration.
 */
@Configuration
public class CoreConfig implements WebMvcConfigurer {

    private final CallerArgumentResolver callerArgumentResolver;

    public CoreConfig(CallerArgumentResolver callerArgumentResolver) {
        this.callerArgumentResolver = callerArgumentResolver;
    }

    /**
     * The one source of "now" in the application.
     *
     * <p>Injecting it means a test can pin time with {@code Clock.fixed(...)} or advance it
     * deliberately, instead of sleeping. UTC, not the system zone: a server that moves between
     * regions must not change what a timestamp means.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public IdGenerator idGenerator() {
        return () -> UUID.randomUUID().toString();
    }

    /** Makes {@code @CurrentCaller Caller caller} resolvable on controller methods. */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(callerArgumentResolver);
    }
}
