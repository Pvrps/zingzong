package ca.gc.cra.xz02.devops.wltrace.agent.http;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.MethodDelegation;

import java.lang.reflect.Constructor;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * The request and response wrappers and the tee streams for one servlet API, generated with Byte
 * Buddy against the API classes the server loaded. The generated classes extend
 * {@code HttpServletRequestWrapper}, {@code HttpServletResponseWrapper}, {@code ServletInputStream}
 * and {@code ServletOutputStream} and delegate every intercepted method to {@link TeeSupport}.
 * They live in a child of the servlet API's loader, so they go away with it.
 */
public final class TeeClasses {

    static final String REQUEST_WRAPPER = "javax.servlet.http.HttpServletRequestWrapper";
    private static final String RESPONSE_WRAPPER = "javax.servlet.http.HttpServletResponseWrapper";
    private static final String REQUEST = "javax.servlet.http.HttpServletRequest";
    private static final String RESPONSE = "javax.servlet.http.HttpServletResponse";
    private static final String INPUT_STREAM = "javax.servlet.ServletInputStream";
    private static final String OUTPUT_STREAM = "javax.servlet.ServletOutputStream";
    private static final String GENERATED_PACKAGE = "ca.gc.cra.xz02.devops.wltrace.generated.";

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

    /** Generates the four classes against the servlet API visible from the loader. */
    static TeeClasses generate(ClassLoader servletLoader) throws Exception {
        Class<?> requestWrapper = Class.forName(REQUEST_WRAPPER, false, servletLoader);
        Class<?> responseWrapper = Class.forName(RESPONSE_WRAPPER, false, servletLoader);
        Class<?> request = Class.forName(REQUEST, false, servletLoader);
        Class<?> response = Class.forName(RESPONSE, false, servletLoader);
        Class<?> servletInputStream = Class.forName(INPUT_STREAM, false, servletLoader);
        Class<?> servletOutputStream = Class.forName(OUTPUT_STREAM, false, servletLoader);

        ByteBuddy byteBuddy = new ByteBuddy();
        Class<?> teeInput = stream(byteBuddy, servletInputStream, GENERATED_PACKAGE + "TeeServletInputStream", servletLoader);
        Class<?> teeOutput = stream(byteBuddy, servletOutputStream, GENERATED_PACKAGE + "TeeServletOutputStream", servletLoader);

        Class<?> requestClass = byteBuddy.subclass(requestWrapper, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                .name(GENERATED_PACKAGE + "TeeHttpServletRequest")
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

        Class<?> responseClass = byteBuddy.subclass(responseWrapper, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                .name(GENERATED_PACKAGE + "TeeHttpServletResponse")
                .defineField("capture", Object.class, Visibility.PUBLIC)
                .defineConstructor(Visibility.PUBLIC).withParameters(response, Object.class)
                .intercept(MethodCall.invoke(responseWrapper.getConstructor(response)).withArgument(0)
                        .andThen(FieldAccessor.ofField("capture").setsArgumentAt(1)))
                .method(named("getOutputStream"))
                .intercept(MethodDelegation.withDefaultConfiguration().filter(named("getOutputStream")).to(TeeSupport.class))
                .method(named("getWriter"))
                .intercept(MethodDelegation.withDefaultConfiguration().filter(named("getWriter")).to(TeeSupport.class))
                .method(named("sendError"))
                .intercept(MethodDelegation.withDefaultConfiguration().filter(named("sendError")).to(TeeSupport.class))
                .make()
                .load(servletLoader, ClassLoadingStrategy.Default.WRAPPER_PERSISTENT)
                .getLoaded();

        return new TeeClasses(
                requestClass.getConstructor(request, Object.class),
                responseClass.getConstructor(response, Object.class),
                teeInput.getConstructor(Object.class, Object.class),
                teeOutput.getConstructor(Object.class, Object.class));
    }

    /**
     * A ServletInputStream or ServletOutputStream subclass whose primitives land in
     * {@link TeeSupport#forward}. Only the primitives are intercepted; readLine, skip, print and
     * the like keep their inherited implementations, which call read or write and therefore pass the tee.
     */
    private static Class<?> stream(ByteBuddy byteBuddy, Class<?> servletStream, String name, ClassLoader loader) throws Exception {
        DynamicType.Builder<?> builder = byteBuddy.subclass(servletStream, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                .name(name)
                .defineField("delegate", Object.class, Visibility.PUBLIC)
                .defineField("buffer", Object.class, Visibility.PUBLIC)
                .defineConstructor(Visibility.PUBLIC).withParameters(Object.class, Object.class)
                .intercept(MethodCall.invoke(servletStream.getDeclaredConstructor())
                        .andThen(FieldAccessor.ofField("delegate").setsArgumentAt(0))
                        .andThen(FieldAccessor.ofField("buffer").setsArgumentAt(1)))
                .method(named("read").or(named("write")).or(named("available")).or(named("close")).or(named("flush"))
                        .or(named("mark")).or(named("reset")).or(named("markSupported")).or(isAbstract()))
                .intercept(MethodDelegation.withDefaultConfiguration().filter(named("forward")).to(TeeSupport.class));
        return builder.make().load(loader, ClassLoadingStrategy.Default.WRAPPER_PERSISTENT).getLoaded();
    }
}
