package javax.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stub of the JPA annotation, with the shape the fixtures use. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface OneToMany {
    String mappedBy() default "";

    CascadeType[] cascade() default {};

    FetchType fetch() default FetchType.LAZY;

    boolean orphanRemoval() default false;
}
