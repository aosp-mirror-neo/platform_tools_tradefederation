/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class IsolationFilterTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface MyAnnotation {}

    @MyAnnotation
    public static class AnnotatedClass {
        @Test
        public void testMethod() {}
    }

    public static class UnAnnotatedClass {
        @Test
        public void testMethod() {}
    }

    @Test
    public void testIncludeAnnotation_ClassLevel() {
        List<String> includeAnnotations = new ArrayList<>();
        includeAnnotations.add(MyAnnotation.class.getName());

        IsolationFilter filter =
                new IsolationFilter(
                        new ArrayList<>(), // includeFilters
                        new ArrayList<>(), // excludeFilters
                        includeAnnotations,
                        new ArrayList<>() // excludeAnnotations
                        );

        Description desc = Description.createTestDescription(AnnotatedClass.class, "testMethod");

        assertTrue("Should run because class has @MyAnnotation", filter.shouldRun(desc));
    }

    @Test
    public void testExcludeAnnotation_ClassLevel() {
        List<String> excludeAnnotations = new ArrayList<>();
        excludeAnnotations.add(MyAnnotation.class.getName());

        IsolationFilter filter =
                new IsolationFilter(
                        new ArrayList<>(),
                        new ArrayList<>(),
                        new ArrayList<>(),
                        excludeAnnotations);

        Description desc = Description.createTestDescription(AnnotatedClass.class, "testMethod");

        assertFalse(
                "Should NOT run because class has @MyAnnotation which is excluded",
                filter.shouldRun(desc));
    }

    @Test
    public void testIncludeFilter_MethodMatch() {
        List<String> includeFilters = new ArrayList<>();
        includeFilters.add(AnnotatedClass.class.getName() + "#testMethod");

        IsolationFilter filter =
                new IsolationFilter(
                        includeFilters, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        Description desc = Description.createTestDescription(AnnotatedClass.class, "testMethod");
        assertTrue("Should run because method name matches include filter", filter.shouldRun(desc));
    }

    @Test
    public void testIncludeFilter_ClassMatch() {
        List<String> includeFilters = new ArrayList<>();
        includeFilters.add(AnnotatedClass.class.getName());

        IsolationFilter filter =
                new IsolationFilter(
                        includeFilters, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        Description desc = Description.createTestDescription(AnnotatedClass.class, "testMethod");
        assertTrue("Should run because class name matches include filter", filter.shouldRun(desc));
    }

    @Test
    public void testIncludeFilter_PackageMatch() {
        List<String> includeFilters = new ArrayList<>();
        includeFilters.add("com.android.tradefed.isolation");

        IsolationFilter filter =
                new IsolationFilter(
                        includeFilters, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        Description desc = Description.createTestDescription(AnnotatedClass.class, "testMethod");
        assertTrue(
                "Should run because package name matches include filter", filter.shouldRun(desc));
    }

    @Test
    public void testExcludeFilter_MethodMatch() {
        List<String> excludeFilters = new ArrayList<>();
        excludeFilters.add(AnnotatedClass.class.getName() + "#testMethod");

        IsolationFilter filter =
                new IsolationFilter(
                        new ArrayList<>(), excludeFilters, new ArrayList<>(), new ArrayList<>());

        Description desc = Description.createTestDescription(AnnotatedClass.class, "testMethod");
        assertFalse(
                "Should NOT run because method name matches exclude filter",
                filter.shouldRun(desc));
    }

    @Test
    public void testExcludeFilter_ClassMatch() {
        List<String> excludeFilters = new ArrayList<>();
        excludeFilters.add(AnnotatedClass.class.getName());

        IsolationFilter filter =
                new IsolationFilter(
                        new ArrayList<>(), excludeFilters, new ArrayList<>(), new ArrayList<>());

        Description desc = Description.createTestDescription(AnnotatedClass.class, "testMethod");
        assertFalse(
                "Should NOT run because class name matches exclude filter", filter.shouldRun(desc));
    }

    @Test
    public void testExcludeFilter_PackageMatch() {
        List<String> excludeFilters = new ArrayList<>();
        excludeFilters.add("com.android.tradefed.isolation");

        IsolationFilter filter =
                new IsolationFilter(
                        new ArrayList<>(), excludeFilters, new ArrayList<>(), new ArrayList<>());

        Description desc = Description.createTestDescription(AnnotatedClass.class, "testMethod");
        assertFalse(
                "Should NOT run because package name matches exclude filter",
                filter.shouldRun(desc));
    }

    @MyAnnotation
    public void annotatedMethod() {}

    @Test
    public void testExcludeAnnotation_MethodLevel() throws NoSuchMethodException {
        List<String> excludeAnnotations = new ArrayList<>();
        excludeAnnotations.add(MyAnnotation.class.getName());

        IsolationFilter filter =
                new IsolationFilter(
                        new ArrayList<>(),
                        new ArrayList<>(),
                        new ArrayList<>(),
                        excludeAnnotations);

        Description desc =
                Description.createTestDescription(
                        this.getClass(),
                        "annotatedMethod",
                        this.getClass().getMethod("annotatedMethod").getAnnotations());

        assertFalse(
                "Should NOT run because method has @MyAnnotation which is excluded",
                filter.shouldRun(desc));
    }

    @Test
    public void testIncludeFilter_Regex() {
        List<String> includeFilters = new ArrayList<>();
        includeFilters.add(".*Test.AnnotatedClass#testM.*");

        IsolationFilter filter =
                new IsolationFilter(
                        includeFilters, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        Description desc = Description.createTestDescription(AnnotatedClass.class, "testMethod");
        assertTrue("Should run because regex matches", filter.shouldRun(desc));
    }

    @Test
    public void testIncludeFilter_ParameterizedSuiteClassMatch() {
        List<String> includeFilters = new ArrayList<>();
        includeFilters.add(AnnotatedClass.class.getName());

        IsolationFilter filter =
                new IsolationFilter(
                        includeFilters, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        // Simulate parameterized runner suite description, e.g. "[0]"
        // This description has no test class attached explicitly.
        Description parameterizedSuite = Description.createSuiteDescription("[0]");
        // The child test description HAS the test class.
        Description childTest =
                Description.createTestDescription(AnnotatedClass.class, "testMethod[0]");
        parameterizedSuite.addChild(childTest);

        // Even though parameterizedSuite.getClassName() returns "[0]", it should resolve the
        // real class name from its child and thus match the include filter.
        assertTrue(
                "Should run because real class name from child matches include filter",
                filter.shouldRun(parameterizedSuite));
    }
}
