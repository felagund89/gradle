/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.tooling.internal.provider;

import com.google.common.collect.MapMaker;
import net.jcip.annotations.ThreadSafe;
import org.gradle.api.GradleException;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.classloader.ClasspathUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@ThreadSafe
public class ClasspathInferer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClasspathInferer.class);
    private final Lock lock = new ReentrantLock();
    private final Map<Class<?>, Collection<URL>> classPathCache;

    public ClasspathInferer() {
        this.classPathCache = new MapMaker().weakKeys().makeMap();
    }

    public void getClassPathFor(Class<?> targetClass, Collection<URL> dest) {
        lock.lock();
        try {
            Collection<URL> classPath = classPathCache.get(targetClass);
            if (classPath == null) {
                Set<Class<?>> visited = new HashSet<Class<?>>();
                classPath = new LinkedHashSet<URL>();
                find(targetClass, visited, classPath);
                classPathCache.put(targetClass, classPath);
            }
            dest.addAll(classPath);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Locates the classpath required by the given target class. Traverses the dependency graph of classes used by the specified class and collects the result in the given collection.
     */
    private void find(Class<?> target, Collection<Class<?>> visited, Collection<URL> dest) {
        ClassLoader targetClassLoader = target.getClassLoader();
        if (targetClassLoader == null) {
            // A system class, skip it
            return;
        }
        if (!visited.add(target)) {
            // Already seen this class, skip it
            return;
        }

        String resourceName = target.getName().replace(".", "/") + ".class";
        URL resource = targetClassLoader.getResource(resourceName);
        try {
            if (resource == null) {
                LOGGER.warn("Could not determine classpath for {}", target);
                return;
            }

            File classPathRoot = null;
            try {
                classPathRoot = ClasspathUtil.getClasspathForResource(resource, resourceName);
            } catch (GradleException ex) {
                try {
                    classPathRoot = getClasspathForCodeSource(target.getProtectionDomain().getCodeSource().getLocation(), resourceName);
                } catch (GradleException ex2) {
                    LOGGER.debug("Ignored failure to determine classpath using fallback method", ex2);
                    throw ex;
                }
            }
            dest.add(classPathRoot.toURI().toURL());

            // To determine the dependencies of the class, load up the byte code and look for CONSTANT_Class entries in the constant pool

            ClassReader reader;
            InputStream inputStream = resource.openStream();
            try {
                reader = new ClassReader(inputStream);
            } finally {
                inputStream.close();
            }

            char[] charBuffer = new char[reader.getMaxStringLength()];
            for (int i = 1; i < reader.getItemCount(); i++) {
                int itemOffset = reader.getItem(i);
                if (itemOffset > 0 && reader.readByte(itemOffset - 1) == 7) {
                    // A CONSTANT_Class entry, read the class descriptor
                    String classDescriptor = reader.readUTF8(itemOffset, charBuffer);
                    Type type = Type.getObjectType(classDescriptor);
                    while (type.getSort() == Type.ARRAY) {
                        type = type.getElementType();
                    }
                    if (type.getSort() != Type.OBJECT) {
                        // A primitive type
                        continue;
                    }
                    String className = type.getClassName();
                    if (className.equals(target.getName())) {
                        // A reference to this class
                        continue;
                    }

                    Class<?> cl;
                    try {
                        cl = Class.forName(className, false, targetClassLoader);
                    } catch (ClassNotFoundException e) {
                        // This is fine, just ignore it
                        LOGGER.warn("Could not determine classpath for {}", target);
                        continue;
                    }
                    find(cl, visited, dest);
                }
            }
        } catch (Exception e) {
            throw new GradleException(String.format("Could not determine the class-path for %s.", target), e);
        }
    }

    private File getClasspathForCodeSource(URL resource, String resourceName) {
        URI location;
        try {
            location = resource.toURI();
            String path = location.getPath();
            if (location.getScheme().equals("file")) {
                if (path.endsWith(".jar")) {
                    // just assume that resourceName is contained in this JAR
                    return new File(path);
                }
                File candidate = new File(path);
                if (candidate.isDirectory() && new File(candidate, resourceName).exists()) {
                    return candidate;
                }
                candidate = new File(candidate, "bin");
                if (candidate.isDirectory() && new File(candidate, resourceName).exists()) {
                    return candidate;
                }
            }
        } catch (URISyntaxException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
        throw new GradleException(String.format("Cannot determine classpath for resource '%s' from location '%s'.", resourceName, location));
    }
}
