package com.demo.commons.observability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation for {@link BusinessRule} repetition — required
 * by the JLS {@code @Repeatable} machinery so multiple {@code
 * @BusinessRule} annotations can be stacked on one method.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BusinessRules {

    BusinessRule[] value();
}
