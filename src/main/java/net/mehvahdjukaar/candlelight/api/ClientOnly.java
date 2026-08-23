package net.mehvahdjukaar.candlelight.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type, method, field or constructor as client-side only.
 * <p>
 * When the {@code clientOnly} transform is enabled, the annotated element (and, for types, the
 * whole class) is stripped from jars intended for environments where client-only code must not be
 * present. This lets code reference client-exclusive members without leaking them into
 * non-client distributions.
 *
 * @see net.mehvahdjukaar.candlelight.core.processors.ClientOnlyProcessor
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface ClientOnly {
}
