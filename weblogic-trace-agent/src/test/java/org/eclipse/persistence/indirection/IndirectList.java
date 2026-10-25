package org.eclipse.persistence.indirection;

import java.util.Iterator;
import java.util.Vector;

/**
 * Stub of EclipseLink's lazy list: a Vector whose contents live behind a value holder and are
 * reached only through it, so that touching the contents of an uninstantiated holder is visible.
 */
public class IndirectList<E> extends Vector<E> implements IndirectContainer {

    private static final long serialVersionUID = 1L;

    private ValueHolderInterface valueHolder;

    @Override
    public ValueHolderInterface getValueHolder() {
        return valueHolder;
    }

    @Override
    public void setValueHolder(ValueHolderInterface valueHolder) {
        this.valueHolder = valueHolder;
    }

    @Override
    public boolean isInstantiated() {
        return valueHolder != null && valueHolder.isInstantiated();
    }

    @SuppressWarnings("unchecked")
    private Vector<E> delegate() {
        return (Vector<E>) valueHolder.getValue();
    }

    @Override
    public synchronized int size() {
        return delegate().size();
    }

    @Override
    public synchronized E get(int index) {
        return delegate().get(index);
    }

    @Override
    public synchronized Iterator<E> iterator() {
        return delegate().iterator();
    }
}
