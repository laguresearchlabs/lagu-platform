package com.lagu.platform.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a permission gate on a controller or service method.
 * The aspect evaluates {@link PermissionEvaluator} before allowing the method to proceed.
 *
 * <p>Use {@code resource = "*"} to match any resource type (e.g. admin-only operations).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** Resource type name (e.g. "RECORD", "ATTRIBUTE", "*") */
    String resource();

    /** Action (CREATE | READ | UPDATE | DELETE | TRANSITION | APPROVE) */
    String action();
}
