package org.eclipse.persistence.indirection;

/** Stub of EclipseLink's instantiated value holder. */
public class ValueHolder implements ValueHolderInterface {

    private Object value;

    public ValueHolder(Object value) {
        this.value = value;
    }

    @Override
    public Object getValue() {
        return value;
    }

    @Override
    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public boolean isInstantiated() {
        return true;
    }

    @Override
    public Object clone() {
        return new ValueHolder(value);
    }
}
