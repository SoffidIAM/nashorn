/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.openjdk.nashorn.internal.runtime;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ReflectPermission;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.PrivilegedAction;
import java.security.Permissions;
import java.security.SecureClassLoader;
import java.util.Arrays;

/**
 * Superclass for Nashorn class loader classes.
 */
abstract class NashornLoader extends SecureClassLoader {
    protected static final String OBJECTS_PKG        = "org.openjdk.nashorn.internal.objects";
    protected static final String RUNTIME_PKG        = "org.openjdk.nashorn.internal.runtime";
    protected static final String RUNTIME_ARRAYS_PKG = "org.openjdk.nashorn.internal.runtime.arrays";
    protected static final String RUNTIME_LINKER_PKG = "org.openjdk.nashorn.internal.runtime.linker";
    protected static final String SCRIPTS_PKG        = "org.openjdk.nashorn.internal.scripts";

    static final Module NASHORN_MODULE = Context.class.getModule();

    private static final Permission[] SCRIPT_PERMISSIONS;

    private static final String MODULE_MANIPULATOR_NAME = SCRIPTS_PKG + ".ModuleGraphManipulator";
    private static final byte[] MODULE_MANIPULATOR_BYTES = readModuleManipulatorBytes();

    static {
        /*
         * Generated classes get access to runtime, runtime.linker, objects, scripts packages.
         * Note that the actual scripts can not access these because Java.type, Packages
         * prevent these restricted packages. And Java reflection and JSR292 access is prevented
         * for scripts. In other words, nashorn generated portions of script classes can access
         * classes in these implementation packages.
         */
        SCRIPT_PERMISSIONS = new Permission[] {
                new ReflectPermission("suppressAccessChecks"),
                new RuntimePermission("accessClassInPackage." + RUNTIME_PKG),
                new RuntimePermission("accessClassInPackage." + RUNTIME_LINKER_PKG),
                new RuntimePermission("accessClassInPackage." + OBJECTS_PKG),
                new RuntimePermission("accessClassInPackage." + SCRIPTS_PKG),
                new RuntimePermission("accessClassInPackage." + RUNTIME_ARRAYS_PKG)
        };
    }

    // addExport Method object on ModuleGraphManipulator
    // class loaded by this loader
    private Method addModuleExport;

    NashornLoader(final ClassLoader parent) {
        super(parent);
    }

    void loadModuleManipulator() {
        final Class<?> clazz = defineClass(MODULE_MANIPULATOR_NAME,
                MODULE_MANIPULATOR_BYTES, 0, MODULE_MANIPULATOR_BYTES.length);
        // force class initialization so that <clinit> runs!
        try {
            Class.forName(MODULE_MANIPULATOR_NAME, true, this);
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
        final PrivilegedAction<Void> pa = () -> {
            try {
                addModuleExport = clazz.getDeclaredMethod("addExport", Module.class);
                addModuleExport.setAccessible(true);
            } catch (final NoSuchMethodException | SecurityException ex) {
                throw new RuntimeException(ex);
            }
            return null;
        };
        AccessController.doPrivileged(pa);
    }

    final void addModuleExport(final Module to) {
        try {
            addModuleExport.invoke(null, to);
        } catch (final IllegalAccessException |
                IllegalArgumentException |
                InvocationTargetException ex) {
            throw new RuntimeException(ex);
        }
    }

    static boolean isInNamedModule() {
        // True if Nashorn is loaded as a JPMS module (typically, added to
        // --module-path); false if it is loaded through classpath into an
        // unnamed module. There are modular execution aspects that need to
        // be taken care of when Nashorn is used as a JPMS module.
        return NASHORN_MODULE.isNamed();
    }

    protected static void checkPackageAccess(final String name) {
        final int i = name.lastIndexOf('.');
        if (i != -1) {
            final SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                final String pkgName = name.substring(0, i);
                switch (pkgName) {
                    case RUNTIME_PKG:
                    case RUNTIME_ARRAYS_PKG:
                    case RUNTIME_LINKER_PKG:
                    case OBJECTS_PKG:
                    case SCRIPTS_PKG:
                        // allow it.
                        break;
                    default:
                        sm.checkPackageAccess(pkgName);
                }
            }
        }
    }

    @Override
    protected PermissionCollection getPermissions(final CodeSource codesource) {
        final Permissions permCollection = new Permissions();
        for (final Permission perm : SCRIPT_PERMISSIONS) {
            permCollection.add(perm);
        }
        return permCollection;
    }

    /**
     * Create a secure URL class loader for the given classpath
     * @param classPath classpath for the loader to search from
     * @param parent the parent class loader for the new class loader
     * @return the class loader
     */
    static ClassLoader createClassLoader(final String classPath, final ClassLoader parent) {
        final URL[] urls = pathToURLs(classPath);
        return URLClassLoader.newInstance(urls, parent);
    }

    /*
     * Utility method for converting a search path string to an array
     * of directory and JAR file URLs.
     *
     * @param path the search path string
     * @return the resulting array of directory and JAR file URLs
     */
    private static URL[] pathToURLs(final String path) {
        final String[] components = path.split(File.pathSeparator);
        return Arrays.stream(components)
            .map(File::new)
            .map(NashornLoader::fileToURL)
            .toArray(URL[]::new);
    }

    /*
     * Returns the directory or JAR file URL corresponding to the specified
     * local file name.
     *
     * @param file the File object
     * @return the resulting directory or JAR file URL, or null if unknown
     */
    private static URL fileToURL(final File file) {
        String name;
        try {
            name = file.getCanonicalPath();
        } catch (final IOException e) {
            name = file.getAbsolutePath();
        }
        name = name.replace(File.separatorChar, '/');
        if (!name.startsWith("/")) {
            name = "/" + name;
        }
        // If the file does not exist, then assume that it's a directory
        if (!file.isFile()) {
            name += "/";
        }
        try {
            return new URL("file", "", name);
        } catch (final MalformedURLException e) {
            throw new IllegalArgumentException("file");
        }
    }

    private static byte[] readModuleManipulatorBytes() {
        final PrivilegedAction<byte[]> pa = () -> {
            final String res = "/"+ MODULE_MANIPULATOR_NAME.replace('.', '/') + ".class";
            try (InputStream in = NashornLoader.class.getResourceAsStream(res)) {
                return in.readAllBytes();
            } catch (final IOException exp) {
                throw new UncheckedIOException(exp);
            }
        };
        return AccessController.doPrivileged(pa);
    }
}

