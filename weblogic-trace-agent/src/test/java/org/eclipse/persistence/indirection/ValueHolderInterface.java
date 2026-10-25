package org.eclipse.persistence.indirection;

/** Stub of EclipseLink's lazy value holder: the agent reads {@link #isInstantiated()} and, only then, {@link #getValue()}. */
public interface ValueHolderInterface {

    Object getValue();

    void setValue(Object value);

    boolean isInstantiated();

    Object clone();
}
