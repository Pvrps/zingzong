package io.capture.agent.http;

import io.capture.agent.AgentLog;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.MethodDelegation;

import java.lang.reflect.Constructor;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * The agent cannot extend {@code javax.servlet} classes at compile time (no javax dependency),
 * so the request/response wrappers and the tee streams are generated with Byte Buddy against
 * the servlet API the server actually loaded, once per servlet-API class loader. The
 * generated classes live in a child loader of that loader (they see javax.servlet through
 * the parent and the agent through the bootstrap loader) and are cached on the
 * {@code HttpServletRequestWrapper} Class object, so they go away with it.
 */
public final class TeeClasses {

    private static final String REQUEST_WRAPPER = "javax.servlet.http.HttpServletRequestWrapper";
    private static final String RESPONSE_WRAPPER = "javax.servlet.http.HttpServletResponseWrapper";
    private static final String REQUEST = "javax.servlet.http.HttpServletRequest";
    private static final String RESPONSE = "javax.servlet.http.HttpServletResponse";
    private static final String INPUT_STREAM = "javax.servlet.ServletInputStream";
    private static final String OUTPUT_STREAM = "javax.servlet.ServletOutputStream";

    private static final ClassValue<TeeClasses> CACHE = new ClassValue<TeeClasses>() {
        @Override
        protected TeeClasses computeValue(Class<?> requestWrapperClass) {
            return generate(requestWrapperClass.getClassLoader());
        }
    };

    private final Constructor<?> requestWrapper;
    private final Constructor<?> responseWrapper;
    private final Constructor<?> inputStream;
    private final Constructor<?> outputStream;

    private TeeClasses(Constructor<?> requestWrapper, Constructor<?> responseWrapper, Constructor<?> inputStream, Constructor<?> outputStream) {
        this.requestWrapper = requestWrapper;
        this.responseWrapper = responseWrapper;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
    }

    /** The generated classes for the servlet API visible from {@code servletObject}'s class. */
    public static TeeClasses forServletApiOf(Object servletObject) throws ClassNotFoundException {
        ClassLoader loader = servletObject.getClass().getClassLoader();
        Class<?> wrapper = Class.forName(REQUEST_WRAPPER, false, loader == null ? ClassLoader.getSystemClassLoader() : loader);
        return CACHE.get(wrapper);
    }

    public Object wrapRequest(Object request, HttpCapture capture) throws Exception {
        return requestWrapper.newInstance(request, capture);
    }

    public Object wrapResponse(Object response, HttpCapture capture) throws Exception {
        return responseWrapper.newInstance(response, capture);
    }

    Object newInputStream(Object delegate, BodyBuffer buffer) throws Exception {
        return inputStream.newInstance(delegate, buffer);
    }

    Object newOutputStream(Object delegate, BodyBuffer buffer) throws Exception {
        return outputStream.newInstance(delegate, buffer);
    }

    private static TeeClasses generate(ClassLoader servletLoader) {
        try {
            long start = System.nanoTime();
            Class<?> requestWrapper = Class.forName(REQUEST_WRAPPER, false, servletLoader);
            Class<?> responseWrapper = Class.forName(RESPONSE_WRAPPER, false, servletLoader);
            Class<?> request = Class.forName(REQUEST, false, servletLoader);
            Class<?> response = Class.forName(RESPONSE, false, servletLoader);
            Class<?> servletInputStream = Class.forName(INPUT_STREAM, false, servletLoader);
            Class<?> servletOutputStream = Class.forName(OUTPUT_STREAM, false, servletLoader);

            ByteBuddy byteBuddy = new ByteBuddy();
            Class<?> teeIn = stream(byteBuddy, servletInputStream, "io.capture.generated.TeeServletInputStream", servletLoader);
            Class<?> teeOut = stream(byteBuddy, servletOutputStream, "io.capture.generated.TeeServletOutputStream", servletLoader);

            Class<?> reqClass = byteBuddy.subclass(requestWrapper, net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy.Default.NO_CONSTRUCTORS)
                    .name("io.capture.generated.TeeHttpServletRequest")
                    .defineField("capture", Object.class, Visibility.PUBLIC)
                    .defineConstructor(Visibility.PUBLIC).withParameters(request, Object.class)
                    .intercept(MethodCall.invoke(requestWrapper.getConstructor(request)).withArgument(0)
                            .andThen(FieldAccessor.ofField("capture").setsArgumentAt(1)))
                    .method(named("getInputStream"))
                    .intercept(MethodDelegation.withDefaultConfiguration().filter(named("getInputStream")).to(TeeSupport.class))
                    .method(named("getReader"))
                    .intercept(MethodDelegation.withDefaultConfiguration().filter(named("getReader")).to(TeeSupport.class))
                    .make()
                    .load(servletLoader, ClassLoadingStrategy.Default.WRAPPER_PERSISTENT)
                    .getLoaded();

            Class<?> respClass = byteBuddy.subclass(responseWrapper, net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy.Default.NO_CONSTRUCTORS)
                    .name("io.capture.generated.TeeHttpServletResponse")
                    .defineField("capture", Object.class, Visibility.PUBLIC)
                    .defineConstructor(Visibility.PUBLIC).withParameters(response, Object.class)
                    .intercept(MethodCall.invoke(responseWrapper.getConstructor(response)).withArgument(0)
                            .andThen(FieldAccessor.ofField("capture").setsArgumentAt(1)))
                    .method(named("getOutputStream"))
                    .intercept(MethodDelegation.withDefaultConfiguration().filter(named("getOutputStream")).to(TeeSupport.class))
                    .method(named("getWriter"))
                    .intercept(MethodDelegation.withDefaultConfiguration().filter(named("getWriter")).to(TeeSupport.class))
                    .make()
                    .load(servletLoader, ClassLoadingStrategy.Default.WRAPPER_PERSISTENT)
                    .getLoaded();

            AgentLog.info("generated servlet tee classes for " + describe(servletLoader) + " in " + (System.nanoTime() - start) / 1000000L + "ms");
            return new TeeClasses(
                    reqClass.getConstructor(request, Object.class),
                    respClass.getConstructor(response, Object.class),
                    teeIn.getConstructor(Object.class, Object.class),
                    teeOut.getConstructor(Object.class, Object.class));
        } catch (Throwable t) {
            throw new IllegalStateException("cannot generate servlet tee classes for " + describe(servletLoader), t);
        }
    }

    /** A ServletInputStream/ServletOutputStream subclass whose every public method lands in TeeSupport.forward. */
    private static Class<?> stream(ByteBuddy byteBuddy, Class<?> servletStream, String name, ClassLoader loader) throws Exception {
        DynamicType.Builder<?> builder = byteBuddy.subclass(servletStream, net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy.Default.NO_CONSTRUCTORS)
                .name(name)
                .defineField("delegate", Object.class, Visibility.PUBLIC)
                .defineField("buffer", Object.class, Visibility.PUBLIC)
                .defineConstructor(Visibility.PUBLIC).withParameters(Object.class, Object.class)
                .intercept(MethodCall.invoke(servletStream.getDeclaredConstructor())
                        .andThen(FieldAccessor.ofField("delegate").setsArgumentAt(0))
                        .andThen(FieldAccessor.ofField("buffer").setsArgumentAt(1)))
                // Only the primitives are intercepted; readLine/skip/readAllBytes/print keep their
                // inherited implementations, which call read/write and therefore pass the tee.
                .method(named("read").or(named("write")).or(named("available")).or(named("close")).or(named("flush"))
                        .or(named("mark")).or(named("reset")).or(named("markSupported")).or(isAbstract()))
                .intercept(MethodDelegation.withDefaultConfiguration().filter(named("forward")).to(TeeSupport.class));
        return builder.make().load(loader, ClassLoadingStrategy.Default.WRAPPER_PERSISTENT).getLoaded();
    }

    private static String describe(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        String s = loader.toString();
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }
}
