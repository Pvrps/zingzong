package org.eclipse.persistence.indirection;

/** Stub of EclipseLink's lazy collection interface: the agent reads {@link #isInstantiated()} by name. */
public interface IndirectContainer {

    ValueHolderInterface getValueHolder();

    void setValueHolder(ValueHolderInterface valueHolder);

    boolean isInstantiated();
}
