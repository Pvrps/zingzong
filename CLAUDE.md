# Rules for editing weblogic-trace

For whoever edits the code. What the product is and how it works is in [README.md](README.md) and `docs/`; this file holds only the rules.

## Constraints

Numbered as in [docs/architecture/ARCHITECTURE.md, Design rules](docs/architecture/ARCHITECTURE.md#design-rules), where each is explained.

1. **No WebLogic classes at compile time.** The agent compiles against no WebLogic jar. Every WebLogic type is matched by name and reached by reflection, and that code lives in `agent/weblogic` and the hook definitions in `agent/instrument` only. Unit tests run against the stubs under `weblogic-trace-agent/src/test/java/weblogic/`, named like the real classes.
2. **Boot-layer linking only.** Agent code links only against `java.base`, `java.logging`, `java.naming`, `java.management` and `java.instrument`. Never reference `java.sql.*`, `javax.sql.*` or any other platform-loader module by class literal, `instanceof` or cast; recognise by name, call reflectively. `BootstrapVisibilityTest` checks every compiled class.
3. **Never throw into a business call.** Every method advice can reach is guarded; a failure records nothing or a marker and warns once (`AgentLog.warnOnce`). Reflective reads return null on any failure.
4. **Class-loader hygiene.** A per-type cache keys on the `Class` object (`ClassValue`) or on name plus loader, never on the class name alone, and holds no strong `Class` reference outside the class's own reachability. Resolve classes through the loader of the object at hand, never the agent's.
5. **Advice is inlined.** An `*Advice` class has two static methods, no fields, no lambdas, no method references, and exactly one call per method, into `TraceRuntime`. `TraceRuntime` is the only static mutable state in the agent; everything behind it is an object whose dependencies arrive through its constructor.
6. **Raw capture.** Record what crossed the boundary unchanged: every field, no masking, no allow-lists, no deny-lists. The only transformations are the markers of `Marker`, and every ceiling that fires leaves `@truncated` with a count. The viewer annotates and never removes.
7. **Java 8 bytecode.** `maven.compiler.source` and `target` 1.8, never `release`; every build plugin at a version that runs on a Java 8 JDK; `JAVA_HOME=$JDK8_HOME mvn -q install` and `mvn -q install` under JDK 21 both pass before a commit.

Also: the plugin changes nothing in a domain but the overrides file and `<domain>/weblogic-trace/`; the agent takes only `out=DIR`; the plugin's only parameters are `wltrace.domain`, `wltrace.port`, `wltrace.out`; a running server's agent jar is never overwritten in place (`EnableMojo` replaces it by rename); with no recording active nothing is instrumented.

## Style

- Names say what they hold: `containerInvocation` not `ci`, `businessInterface` not `iface`, `dataSourceName` not `ds`, `builder` not `sb`. No abbreviations except universally known ones (`id`, `url`, `jdbc`, `jms`, `ejb`, `http`).
- Every field that can be final is final; classes not designed for extension are final; constructors take every dependency.
- Java 8 idioms: diamond operator, lambdas and method references, `Optional` for absent return values, streams where they read more clearly than a loop; loops remain where early exit, index arithmetic or exception handling makes them clearer. The `*Advice` classes are the exception: no lambdas or method references, because ByteBuddy inlines them into the target method.
- Javadoc says what a class or method does and any constraint a caller must know, in present tense, as long as that takes and not more: a sentence for a simple method, a short paragraph for a class with rules a caller can get wrong. No history, no rationale essays, no restating the signature. Comments inside methods explain a non-obvious why. Packages are described in `docs/architecture/ARCHITECTURE.md` (Packages), not in `package-info.java` files.
- Tests are TestNG: `@Test`, `@BeforeMethod`/`@BeforeClass`, `Assert`, data providers where tests are tables. Test names state the behaviour (`recordsLazyCollectionAsUnloadedWithoutInstantiating`, not `testLazy`); every test class has one line saying what it proves. A base class is tested once, through a stub subclass. Every test runs offline with no server.
- No frameworks beyond ByteBuddy and Jackson in the agent; the viewer uses the JDK's `com.sun.net.httpserver`, no web framework, and a page of four plain files (`index.html`, `style.css`, `app.js`, `diagram.js`) with no build step.
- Nothing in the repository, its documentation or its build mentions a development environment, a sample application or a machine; verification is described generically (a domain, an application, the three goals).

## Build and test

```
mvn -q install                          # JDK 21 on the path
JAVA_HOME=$JDK8_HOME mvn -q install     # the same under a Java 8 JDK
```

Both run the unit tests. `mvn wltrace:<goal> -Dwltrace.domain=...` runs the installed plugin; `.mvn/settings.xml` supplies the `pluginGroup` inside this directory.

## Documentation

- When a hook or a WebLogic internal is touched, `docs/architecture/HOOKS.md` is updated in the same change: the target, why it was chosen, what is read, the verified builds, what happens when it changes. `docs/DEVELOPMENT.md` lists which document owns every other topic.
- A format change updates the `model` class, `trace.schema.json` and `docs/TRACE-FORMAT.md`; a constraint change updates the Design rules of `docs/architecture/ARCHITECTURE.md` and this file.
- Documents are present tense and current state only: a sentence that describes an earlier state of the code, or compares the current state with one, is a defect. Every class, method, package, file and goal name is in backticks and exists in the tree.
