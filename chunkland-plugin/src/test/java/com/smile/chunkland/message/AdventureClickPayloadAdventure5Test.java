package com.smile.chunkland.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline check against a real Adventure 5 jar.
 *
 * <p>Runs only when {@code ADVENTURE5_JAR} (environment variable or system
 * property, path-separated jar list) points at existing jars plus the compiled
 * main classes directory; otherwise the test is skipped. The isolated loader
 * uses the platform classloader as parent so no Adventure 4 class from the
 * surrounding suite classpath can leak in.
 */
class AdventureClickPayloadAdventure5Test {

    private static List<URL> adventure5Urls() throws Exception {
        String raw = System.getenv("ADVENTURE5_JAR");
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty("ADVENTURE5_JAR");
        }
        assumeTrue(raw != null && !raw.isBlank(), "ADVENTURE5_JAR not set; skipping real Adventure 5 check");
        List<URL> urls = new ArrayList<>();
        for (String part : raw.split(File.pathSeparator)) {
            if (part.isBlank()) {
                continue;
            }
            File file = new File(part);
            assumeTrue(file.isFile(), "ADVENTURE5_JAR entry missing: " + part);
            urls.add(file.toURI().toURL());
        }
        File mainClasses = new File("build/classes/java/main");
        assumeTrue(mainClasses.isDirectory(), "main classes not compiled: " + mainClasses.getPath());
        urls.add(mainClasses.toURI().toURL());
        return urls;
    }

    @Test
    void realAdventure5ClickShapesAreHandled() throws Exception {
        List<URL> urls = adventure5Urls();
        try (URLClassLoader loader =
                new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Class<?> clickType = Class.forName("net.kyori.adventure.text.event.ClickEvent", true, loader);
            Class<?> payloadType = Class.forName("com.smile.chunkland.message.AdventureClickPayload", true, loader);
            System.out.println("AdventureClickPayloadAdventure5Test: ClickEvent from "
                    + clickType.getProtectionDomain().getCodeSource().getLocation());

            Method read = payloadType.getMethod("read", Object.class);
            Method isRun = payloadType.getMethod("isRunCommand", Object.class);
            Method isSuggest = payloadType.getMethod("isSuggestCommand", Object.class);
            Method actionName = payloadType.getMethod("actionName", Object.class);

            Object suggest = clickType.getMethod("suggestCommand", String.class)
                    .invoke(null, "/land delete confirm 7");
            Object run = clickType.getMethod("runCommand", String.class)
                    .invoke(null, "/land confirm 1 2 home");
            Object open = clickType.getMethod("openUrl", String.class)
                    .invoke(null, "https://example.com/");

            System.out.println("AdventureClickPayloadAdventure5Test: suggest actionName=["
                    + actionName.invoke(null, suggest) + "] run actionName=["
                    + actionName.invoke(null, run) + "]");

            assertEquals("/land delete confirm 7", read.invoke(null, suggest));
            assertEquals("/land confirm 1 2 home", read.invoke(null, run));
            assertTrue((Boolean) isSuggest.invoke(null, suggest));
            assertFalse((Boolean) isRun.invoke(null, suggest));
            assertTrue((Boolean) isRun.invoke(null, run));
            assertFalse((Boolean) isSuggest.invoke(null, run));
            assertFalse((Boolean) isSuggest.invoke(null, open));
            assertFalse((Boolean) isRun.invoke(null, open));
        }
    }
}
