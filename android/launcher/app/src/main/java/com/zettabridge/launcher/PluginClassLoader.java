package com.zettabridge.launcher;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.io.IOException;

/** Child-first plugin dex loader with explicit launcher bridge and native-library routing. */
final class PluginClassLoader extends DexClassLoader {
    private static final String BRIDGE_PREFIX = "com.zettabridge.core.";

    private final PluginRecord record;
    private final File proxyTemplate;
    private final ClassLoader bridgeLoader;

    PluginClassLoader(PluginRecord record, File codeCache, File proxyTemplate, ClassLoader bootLoader,
            ClassLoader bridgeLoader) {
        super(record.apk().getPath(), codeCache.getPath(), record.isTranslated() ? null : record.libDir().getPath(),
                bootLoader);
        this.record = record;
        this.proxyTemplate = proxyTemplate;
        this.bridgeLoader = bridgeLoader;
    }

    static boolean delegateToBridge(String name) {
        return name != null && name.startsWith(BRIDGE_PREFIX);
    }

    private static boolean platformClass(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")
                || name.startsWith("dalvik.") || name.startsWith("sun.");
    }

    /**
     * Classes of platform shared libraries the launcher declares with uses-library. Android puts
     * org.apache.http.legacy on the class path of every app targeting SDK 27 or lower, so plugins
     * of that era use it without declaring it; it lives on the launcher's loader, not the boot one.
     */
    private static boolean sharedLibraryClass(String name) {
        return name.startsWith("org.apache.http.") || name.startsWith("org.apache.commons.logging.")
                || name.startsWith("android.net.http.");
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (this) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (delegateToBridge(name)) {
                    loaded = bridgeLoader.loadClass(name);
                } else if (platformClass(name)) {
                    loaded = super.loadClass(name, false);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException missing) {
                        try {
                            loaded = super.loadClass(name, false);
                        } catch (ClassNotFoundException notBoot) {
                            if (!sharedLibraryClass(name)) throw notBoot;
                            loaded = PluginClassLoader.class.getClassLoader().loadClass(name);
                        }
                    }
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    @Override
    public String findLibrary(String name) {
        try {
            File library = PluginFiles.resolveLibrary(record.libDir(), record.proxyDir(), proxyTemplate,
                    record.isTranslated(), name);
            return library != null ? library.getPath() : null;
        } catch (IOException e) {
            return null;  // ART reports the normal UnsatisfiedLinkError; Diagnostics adds the bridge detail.
        }
    }
}
