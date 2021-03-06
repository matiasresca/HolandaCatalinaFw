package org.hcjf.service.security;

import java.lang.annotation.*;

/**
 * This annotation tag the methods of a layer with a permission that a session
 * need to invoke the method.
 * @author javaito
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Permissions.class)
public @interface Permission {
    String value();
}
