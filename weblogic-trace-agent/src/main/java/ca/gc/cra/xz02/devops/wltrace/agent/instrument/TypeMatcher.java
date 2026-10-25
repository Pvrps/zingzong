package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import net.bytebuddy.description.type.TypeDescription;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Decides whether a class about to be loaded or retransformed is a target, from its description and its defining loader. */
public interface TypeMatcher {

    boolean matches(TypeDescription type, ClassLoader loader);

    /** Matches the classes with exactly these names, in any loader. */
    static TypeMatcher named(String... classNames) {
        Set<String> names = new HashSet<>(Arrays.asList(classNames));
        return (type, loader) -> names.contains(type.getName());
    }
}
