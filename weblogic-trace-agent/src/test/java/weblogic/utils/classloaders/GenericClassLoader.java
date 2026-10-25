package weblogic.utils.classloaders;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Stub of WebLogic's application class loader: defines the named classes itself from the
 * system class path, so the ownership rule sees an application loader.
 */
public final class GenericClassLoader extends ClassLoader {

    private final Set<String> owned;

    public GenericClassLoader(String... ownedClassNames) {
        super(GenericClassLoader.class.getClassLoader());
        this.owned = new HashSet<String>(Arrays.asList(ownedClassNames));
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && owned.contains(name)) {
                byte[] bytes = bytesOf(name);
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            return loaded != null ? loaded : super.loadClass(name, resolve);
        }
    }

    private byte[] bytesOf(String name) throws ClassNotFoundException {
        try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (in == null) {
                throw new ClassNotFoundException(name);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }
}
