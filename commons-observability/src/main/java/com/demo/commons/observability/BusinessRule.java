package com.demo.commons.observability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents a single business decision the annotated method makes.
 *
 * <p>Pure metadata: the aspect does <strong>not</strong> execute or
 * evaluate these — they are read reflectively by the
 * {@code ServiceCardController} to render the Decision Rules table on
 * the service card. This keeps the Function body short while still
 * surfacing the logic to product-owners in a first-class way.
 *
 * <p>Repeatable so a method can declare several rules.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(BusinessRules.class)
public @interface BusinessRule {

    /** Plain-English (or pseudo-code) condition, e.g. {@code "score >= 75"}. */
    String condition();

    /** Plain-English outcome when the condition holds, e.g. {@code "grade = A"}. */
    String outcome();

    /** Optional KPI name the rule emits when triggered. Empty when none. */
    String kpi() default "";
}
