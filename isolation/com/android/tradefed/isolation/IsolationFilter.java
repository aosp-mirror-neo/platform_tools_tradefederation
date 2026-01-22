/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tradefed.isolation;

import org.junit.runner.Description;
import org.junit.runner.manipulation.Filter;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

// LINT.IfChange
/**
 * Implements JUnit's Filter interface to allow TradeFed style filters to be evaluated during
 * execution of JUnit tests. Very similar to TradeFed's internal FilterHelper, but freed of the
 * dependencies that one has.
 */
public final class IsolationFilter extends Filter {
    /** The include filters of the test name to run */
    private Set<String> mIncludeFilters = new LinkedHashSet<>();

    /** The exclude filters of the test name to run */
    private Set<String> mExcludeFilters = new LinkedHashSet<>();

    /** The include annotations of the test to run */
    private Set<String> mIncludeAnnotations = new LinkedHashSet<>();

    /** The exclude annotations of the test to run */
    private Set<String> mExcludeAnnotations = new LinkedHashSet<>();

    public IsolationFilter(
            Collection<String> includeFilters,
            Collection<String> excludeFilters,
            Collection<String> includeAnnotations,
            Collection<String> excludeAnnotations) {
        mIncludeFilters = new LinkedHashSet<>(includeFilters);
        mExcludeFilters = new LinkedHashSet<>(excludeFilters);
        mIncludeAnnotations = new LinkedHashSet<>(includeAnnotations);
        mExcludeAnnotations = new LinkedHashSet<>(excludeAnnotations);
    }

    public IsolationFilter(FilterSpec filter) {
        this(
                filter.getIncludeFiltersList(),
                filter.getExcludeFiltersList(),
                filter.getIncludeAnnotationsList(),
                filter.getExcludeAnnotationsList());
    }

    public IsolationFilter() {}

    @Override
    public String describe() {
        return "Tradefed filtering based on name and annotations.";
    }

    /**
     * Check if an element that has annotation passes the filter
     *
     * <p>Synced from the method in TestFilterHelper class, with extraJars removed.
     *
     * @param desc a {@link Description} that describes the test.
     * @return true if the test method should run, false otherwise
     */
    @Override
    public boolean shouldRun(Description desc) {
        // We need to build the packageName for a description object
        Class<?> classObj = desc.getTestClass();

        // Parameterized tests might not have a test class
        if (classObj == null && desc.isSuite()) {
            classObj = desc.getChildren().get(0).getTestClass();
        }
        if (classObj == null) {
            System.out.println(
                    String.format(
                            "INFO: Description %s has no test class. Filtering result may not be"
                                    + " reliable.",
                            desc));
        }

        // If class is explicitly annotated to be excluded, exclude it.
        if (classObj != null && isExcluded(Arrays.asList(classObj.getAnnotations()))) {
            return false;
        }
        String packageName = classObj == null ? "" : classObj.getPackage().getName();
        String className = desc.getClassName();
        String methodName = String.format("%s#%s", className, desc.getMethodName());
        if (!shouldRunFilter(packageName, className, methodName)) {
            return false;
        }
        // Carry the parent class annotations to ensure includeAnnotations are respected
        List<Annotation> annotations = new ArrayList<>(desc.getAnnotations());
        if (classObj != null) {
            annotations.addAll(Arrays.asList(classObj.getAnnotations()));
        }
        if (!shouldTestRun(annotations)) {
            return false;
        }
        return mIncludeFilters.isEmpty()
                || mIncludeFilters.contains(methodName)
                || mIncludeFilters.contains(className)
                || mIncludeFilters.contains(packageName)
                || includeFilterMatches(methodName);
    }

    //
    // Methods below are synced from class TestFilterHelper. Any change should be synced in both
    // classes.
    //

    /**
     * Check if an element that has annotation passes the filter
     *
     * @param annotatedElement the element to filter
     * @return true if the test should run, false otherwise
     */
    private boolean shouldTestRun(AnnotatedElement annotatedElement) {
        return shouldTestRun(Arrays.asList(annotatedElement.getAnnotations()));
    }

    /**
     * Check if the {@link Description} that contains annotations passes the filter
     *
     * @param desc the element to filter
     * @return true if the test should run, false otherwise
     */
    private boolean shouldTestRun(Description desc) {
        return shouldTestRun(desc.getAnnotations());
    }

    /** Internal helper to determine if a particular test should run based on its annotations. */
    private boolean shouldTestRun(Collection<Annotation> annotationsList) {
        if (isExcluded(annotationsList)) {
            return false;
        }
        return isIncluded(annotationsList);
    }

    private boolean isExcluded(Collection<Annotation> annotationsList) {
        if (!mExcludeAnnotations.isEmpty()) {
            for (Annotation a : annotationsList) {
                if (mExcludeAnnotations.contains(a.annotationType().getName())) {
                    // If any of the method annotation match an ExcludeAnnotation, don't run it
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isIncluded(Collection<Annotation> annotationsList) {
        if (!mIncludeAnnotations.isEmpty()) {
            Set<String> neededAnnotation = new HashSet<String>();
            neededAnnotation.addAll(mIncludeAnnotations);
            for (Annotation a : annotationsList) {
                if (neededAnnotation.contains(a.annotationType().getName())) {
                    neededAnnotation.remove(a.annotationType().getName());
                }
            }
            if (neededAnnotation.size() != 0) {
                // The test needs to have all the include annotation to pass.
                return false;
            }
        }
        return true;
    }

    /**
     * Check if an element that has annotation passes the filter
     *
     * @param packageName name of the method's package
     * @param classObj method's class
     * @param method test method
     * @return true if the test method should run, false otherwise
     */
    private boolean shouldRun(String packageName, Class<?> classObj, Method method) {
        String className = classObj.getName();
        String methodName = String.format("%s#%s", className, method.getName());

        if (!shouldRunFilter(packageName, className, methodName)) {
            return false;
        }
        // If class is explicitly annotated to be excluded.
        if (isExcluded(Arrays.asList(classObj.getAnnotations()))) {
            return false;
        }
        // if class include but method exclude, we exclude
        if (isIncluded(Arrays.asList(classObj.getAnnotations()))
                && isExcluded(Arrays.asList(method.getAnnotations()))) {
            return false;
        }
        // If a class is explicitly included and check above says method could run, we skip method
        // check, it will be included.
        if (mIncludeAnnotations.isEmpty()
                || !isIncluded(Arrays.asList(classObj.getAnnotations()))) {
            if (!shouldTestRun(method)) {
                return false;
            }
        }
        return mIncludeFilters.isEmpty()
                || mIncludeFilters.contains(methodName)
                || mIncludeFilters.contains(className)
                || mIncludeFilters.contains(packageName)
                || includeFilterMatches(methodName);
    }

    private boolean includeFilterMatches(String methodName) {
        for (var filter : mIncludeFilters) {
            // if (methodName.contains(filter)) {
            // The Whole method name must match so user must pass .* on ends.
            // This is good so we don't accidentally two method when user
            // passes 'testFoo':
            //   #testFoo
            //   #testFooAndBar
            try {
                if (methodName.matches(filter)) {
                    return true;
                }
            } catch (PatternSyntaxException pse) {
                // Ignore names that form a bad regex,
                // like ones using a versioned parameter or module MyClass#myTest[foo-1.23]
            }
        }
        return false;
    }

    /**
     * Internal helper to check if a particular test should run based on its package, class, method
     * names.
     */
    private boolean shouldRunFilter(String packageName, String className, String methodName) {
        if (mExcludeFilters.contains(packageName)) {
            // Skip package because it was excluded
            return false;
        }
        if (mExcludeFilters.contains(className)) {
            // Skip class because it was excluded
            return false;
        }
        if (mExcludeFilters.contains(methodName)) {
            // Skip method because it was excluded
            return false;
        }
        for (String filter : mExcludeFilters) {
            // The whole method name must match so user must pass .* on ends.
            try {
                if (methodName.matches(filter)) {
                    return false;
                }
            } catch (PatternSyntaxException pse) {
                // Ignore names that form a bad regex,
                // like ones using a versioned parameter or module MyClass#myTest[foo-1.23]
            }
        }
        return true;
    }
}
// LINT.ThenChange(//tools/tradefederation/core/com/android/tradefed/testtype/TestFilterHelper.java)
