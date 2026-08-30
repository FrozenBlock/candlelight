package net.mehvahdjukaar.candlelight.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type, method, field or constructor as server-side only. The mirror image of
 * {@link ClientOnly}.
 * <p>
 * When the {@code serverOnly} transform is enabled, the annotated element (and, for types, the
 * whole class) is stripped from jars intended for environments where server-only code must not be
 * present. This lets code reference server-exclusive members without leaking them into
 * non-server distributions.
 *
 * @see net.mehvahdjukaar.candlelight.core.processors.ServerOnlyProcessor
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface ServerOnly {
}