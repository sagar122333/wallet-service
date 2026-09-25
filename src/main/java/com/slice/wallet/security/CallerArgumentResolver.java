package com.slice.wallet.security;

import com.slice.wallet.common.ErrorCode;
import com.slice.wallet.common.WalletException;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Hands the authenticated {@link Caller} to any controller method that asks for one.
 *
 * <p>{@link BearerAuthenticationFilter} already put it in the security context; this is the
 * bridge from Spring Security's world to a parameter a controller can declare. Every controller
 * method that needs to know who is calling says so in its signature, instead of reaching into a
 * static thread-local - which hides a real dependency and makes the method untestable without
 * populating that thread-local first.
 */
@Component
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentCaller.class)
                && Caller.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Caller caller)) {
            // Reaching a @CurrentCaller handler with no caller means the filter chain and the
            // controller disagree about which routes are public. Fail loudly rather than
            // fabricating an anonymous caller the service layer would then trust.
            throw new WalletException(ErrorCode.UNAUTHENTICATED,
                    "no authenticated caller on this request");
        }
        return caller;
    }
}
