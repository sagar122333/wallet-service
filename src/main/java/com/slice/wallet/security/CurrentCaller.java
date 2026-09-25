package com.slice.wallet.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated {@link Caller} into a controller method parameter.
 *
 * <p>Resolved by {@link CallerArgumentResolver}. The alternative - a static
 * {@code SecurityContextHolder.getContext()} lookup inside each method - hides a dependency that
 * every one of those methods genuinely has, and makes them untestable without a populated
 * thread-local.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CurrentCaller {
}
