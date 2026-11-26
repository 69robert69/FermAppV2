package com.fermapp.api.security;

import com.fermapp.api.common.error.Errors;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Lets controllers declare an {@link AuthContext} parameter for the authenticated caller. */
@Component
public class AuthContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container, NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        AuthContext auth = AccessInterceptor.current();
        if (auth == null) {
            throw Errors.unauthorized();
        }
        return auth;
    }
}
