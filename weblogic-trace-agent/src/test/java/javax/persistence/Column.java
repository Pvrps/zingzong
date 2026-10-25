package javax.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stub of the JPA annotation, with the shape the fixtures use. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Column {
    String name() default "";

    boolean nullable() default true;

    int length() default 255;

    int precision() default 0;

    int scale() default 0;
}
