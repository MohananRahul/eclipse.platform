/*******************************************************************************
 * Copyright (C) 2020, Thomas Wolf <thomas.wolf@paranor.ch> and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.core.tests.text;

import java.io.IOException;
import junit.framework.*;
import org.eclipse.core.text.StringMatcher;

/**
 * Poor man's JUnit 3.8 parameterized tests for wildcard matching in
 * {@link StringMatcher}.
 */
public class StringMatcherWildcardTest extends AbstractStringMatcherTestBase {

	public static Test suite() {
		TestSuite tests = new TestSuite();
		TestData[] items;
		try {
			items = getTestData("StringMatcherWildcardTest.txt");
			if (items == null || items.length == 0) {
				tests.addTest(TestSuite.warning("No tests found in StringMatcherWildcardTest.txt"));
				return tests;
			}
		} catch (IOException e) {
			tests.addTest(TestSuite.warning("Could not load test data: " + e));
			return tests;
		}
		for (TestData item : items) {
			if (!item.caseInsensitive) {
				tests.addTest(new TestCase("wildcard[" + item + ']') {

					@Override
					protected void runTest() throws Throwable {
						StringMatcher matcher = new StringMatcher(item.pattern, false, false);
						assertTrue("Unexpected result", item.expected == matcher.match(item.text));
					}
				});
			}
			tests.addTest(new TestCase("wildcardCaseInsensitive[" + item + ']') {

				@Override
				protected void runTest() throws Throwable {
					StringMatcher matcher = new StringMatcher(item.pattern, true, false);
					assertTrue("Unexpected result", item.expected == matcher.match(item.text));
				}
			});
		}
		return tests;
	}
}
