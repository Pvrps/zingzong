package io.capture.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that no compiled agent class links against a platform-loader module, which the bootstrap loader cannot see. */
class BootstrapVisibilityTest {

    /** Packages of modules defined to the platform loader in JDK 9+ that the agent must not link to. */
    private static final String[] NON_BOOT_PACKAGES = {
            "java/sql/", "javax/sql/", "java/net/http/", "javax/script/", "java/compiler/",
            "javax/transaction/xa/", "javax/xml/crypto/", "javax/smartcardio/", "javax/security/auth/kerberos/",
    };

    @Test
    void agentClassesDoNotLinkAgainstPlatformLoaderModules() throws IOException {
        Path classes = Paths.get("target", "classes", "io", "capture", "agent");
        assertTrue(Files.isDirectory(classes), "run after compile: " + classes.toAbsolutePath());
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                for (String ref : classReferences(p)) {
                    for (String pkg : NON_BOOT_PACKAGES) {
                        if (ref.startsWith(pkg)) {
                            offenders.add(classes.relativize(p) + " -> " + ref);
                        }
                    }
                }
            });
        }
        assertTrue(offenders.isEmpty(), "bootstrap-loaded code linking to platform modules (recognise by name instead):\n" + String.join("\n", offenders));
    }

    /** CONSTANT_Class entries of a class file, as internal names. */
    private static List<String> classReferences(Path classFile) {
        List<String> names = new ArrayList<>();
        try (InputStream in = Files.newInputStream(classFile)) {
            java.io.DataInputStream data = new java.io.DataInputStream(new java.io.BufferedInputStream(in));
            data.readInt();
            data.readUnsignedShort();
            data.readUnsignedShort();
            int count = data.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classIndex = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = data.readUnsignedByte();
                switch (tag) {
                    case 1: utf8[i] = new String(readBytes(data, data.readUnsignedShort()), StandardCharsets.UTF_8); break;
                    case 7: classIndex[i] = data.readUnsignedShort(); break;
                    case 8: case 16: case 19: case 20: data.readUnsignedShort(); break;
                    case 3: case 4: data.readInt(); break;
                    case 5: case 6: data.readLong(); i++; break;
                    case 9: case 10: case 11: case 12: case 17: case 18: data.readInt(); break;
                    case 15: data.readUnsignedByte(); data.readUnsignedShort(); break;
                    default: throw new IOException("unknown constant pool tag " + tag + " in " + classFile);
                }
            }
            for (int i = 1; i < count; i++) {
                if (classIndex[i] != 0 && utf8[classIndex[i]] != null) {
                    names.add(utf8[classIndex[i]]);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return names;
    }

    private static byte[] readBytes(java.io.DataInputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
