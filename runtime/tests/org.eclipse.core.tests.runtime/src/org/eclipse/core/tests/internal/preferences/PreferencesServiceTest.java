/*******************************************************************************
 * Copyright (c) 2004, 2018 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Semion Chichelnitsky (semion@il.ibm.com) - bug 208564
 *******************************************************************************/
package org.eclipse.core.tests.internal.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.eclipse.core.internal.preferences.EclipsePreferences;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.preferences.ConfigurationScope;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IExportedPreferences;
import org.eclipse.core.runtime.preferences.IPreferenceFilter;
import org.eclipse.core.runtime.preferences.IPreferenceNodeVisitor;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.core.runtime.preferences.PreferenceFilterEntry;
import org.eclipse.core.runtime.preferences.UserScope;
import org.eclipse.core.tests.harness.FileSystemHelper;
import org.eclipse.core.tests.runtime.RuntimeTestsPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * @since 3.0
 */
@SuppressWarnings("restriction")
public class PreferencesServiceTest {

	static class ExportVerifier {

		private final IEclipsePreferences node;
		private final Set<String> expected;
		private String[] excludes;
		private IPreferenceFilter[] transfers;
		private boolean useTransfers;

		public ExportVerifier(IEclipsePreferences node, String[] excludes) {
			super();
			this.node = node;
			this.excludes = excludes;
			this.expected = new HashSet<>();
		}

		public ExportVerifier(IEclipsePreferences node, IPreferenceFilter[] transfers) {
			super();
			this.node = node;
			this.transfers = transfers;
			this.expected = new HashSet<>();
			this.useTransfers = true;
		}

		void addExpected(String path, String key) {
			this.expected.add(EclipsePreferences.encodePath(path, key));
		}

		void addVersion() {
			expected.add("file_export_version");
		}

		void setExcludes(String[] excludes) {
			this.excludes = excludes;
		}

		void addExportRoot(IEclipsePreferences root) {
			expected.add('!' + root.absolutePath());
		}

		void verify() throws Exception {
			IPreferencesService service = Platform.getPreferencesService();
			Properties properties = new Properties();
			try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				if (useTransfers) {
					service.exportPreferences(node, transfers, output);
				} else {
					service.exportPreferences(node, output, excludes);
				}
				try (ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray())) {
					properties.load(input);
				}
			}
			if (properties.isEmpty()) {
				assertTrue("2.0", expected.isEmpty());
				return;
			}
			assertEquals("3.0", expected.size(), properties.size());
			for (String key : expected) {
				assertNotNull("4.0." + key, properties.get(key));
			}
		}
	}

	@AfterEach
	public void tearDown() throws Exception {
		Platform.getPreferencesService().getRootNode().node(TestScope.SCOPE).removeNode();
	}


	@Test
	public void testImportExportBasic() throws Exception {
		IPreferencesService service = Platform.getPreferencesService();

		// create test node hierarchy
		String qualifier = getUniqueString() + '1';
		IEclipsePreferences test = new TestScope().getNode(qualifier);
		String key = getUniqueString() + 'k';
		String value = getUniqueString() + 'v';
		String key1 = "http://eclipse.org:24";
		String value1 = getUniqueString() + "v1";
		String actual = test.get(key, null);
		assertNull("1.0", actual);
		test.put(key, value);
		test.put(key1, value1);
		actual = test.get(key, null);
		assertEquals("1.1", value, actual);
		actual = test.get(key1, null);
		assertEquals("1.2", value1, actual);

		// export it
		byte[] bytes;
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			service.exportPreferences(test, output, (String[]) null);
			bytes = output.toByteArray();
		}

		// add new values
		String newKey = getUniqueString() + '3';
		String newValue = getUniqueString() + '4';
		actual = test.get(newKey, null);
		assertNull("3.0", actual);
		test.put(newKey, newValue);
		actual = test.get(newKey, null);
		assertEquals("3.1", newValue, actual);
		String newOldValue = getUniqueString() + '5';
		test.put(key, newOldValue);
		actual = test.get(key, null);
		assertEquals("3.2", newOldValue, actual);

		// import
		try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
			service.importPreferences(input);
		}

		// verify
		test = new TestScope().getNode(qualifier);
		actual = test.get(key, null);
		assertEquals("5.0", value, actual);
		actual = test.get(key1, null);
		assertEquals("5.1", value1, actual);
		actual = test.get(newKey, null);
		assertNull("5.2", actual);
		// ensure that the node isn't dirty (has been saved after the import)
		assertTrue("5.3", test instanceof EclipsePreferences);
		assertTrue("5.4", !((EclipsePreferences) test).isDirty());

		// clear all
		test.clear();
		actual = test.get(key, null);
		assertNull("6.1", actual);
		actual = test.get(key1, null);
		assertNull("6.2", actual);
		actual = test.get(newKey, null);
		assertNull("6.3", actual);

		// import
		try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
			service.importPreferences(input);
		}

		// verify
		test = new TestScope().getNode(qualifier);
		actual = test.get(key, null);
		assertEquals("8.0", value, actual);
		actual = test.get(key1, null);
		assertEquals("8.1", value1, actual);
		actual = test.get(newKey, null);
		assertNull("8.2", actual);
	}

	@Test
	public void testLookupOrder() {
		IPreferencesService service = Platform.getPreferencesService();
		String[] defaultOrder = new String[] {"project", //$NON-NLS-1$
				InstanceScope.SCOPE, //
				ConfigurationScope.SCOPE, //
				UserScope.SCOPE, //
				DefaultScope.SCOPE};
		String[] fullOrder = new String[] {"a", "b", "c"};
		String[] nullKeyOrder = new String[] {"e", "f", "g"};
		String qualifier = getUniqueString();
		String key = getUniqueString();

		// bogus set parms
		assertThrows(IllegalArgumentException.class, () -> service.setDefaultLookupOrder(null, key, fullOrder));
		assertThrows(IllegalArgumentException.class,
				() -> service.setDefaultLookupOrder(qualifier, key, new String[] { "a", null, "b" }));

		// nothing set
		assertThat(service.getDefaultLookupOrder(qualifier, key)).isNull();
		assertThat(service.getLookupOrder(qualifier, key)).containsExactly(defaultOrder);

		assertThat(service.getDefaultLookupOrder(qualifier, null)).isNull();
		assertThat(service.getLookupOrder(qualifier, null)).containsExactly(defaultOrder);

		// set for qualifier/key
		service.setDefaultLookupOrder(qualifier, key, fullOrder);
		assertThat(service.getDefaultLookupOrder(qualifier, key)).containsExactly(fullOrder);
		assertThat(service.getLookupOrder(qualifier, key)).containsExactly(fullOrder);

		// nothing set for qualifier/null
		assertThat(service.getDefaultLookupOrder(qualifier, null)).isNull();
		assertThat(service.getLookupOrder(qualifier, null)).containsExactly(defaultOrder);

		// set for qualifier/null
		service.setDefaultLookupOrder(qualifier, null, nullKeyOrder);
		assertThat(service.getDefaultLookupOrder(qualifier, null)).containsExactly(nullKeyOrder);
		assertThat(service.getLookupOrder(qualifier, null)).containsExactly(nullKeyOrder);
		assertThat(service.getDefaultLookupOrder(qualifier, key)).containsExactly(fullOrder);
		assertThat(service.getLookupOrder(qualifier, key)).containsExactly(fullOrder);

		// clear qualifier/key (find qualifier/null)
		service.setDefaultLookupOrder(qualifier, key, null);
		assertThat(service.getDefaultLookupOrder(qualifier, key)).isNull();
		assertThat(service.getLookupOrder(qualifier, key)).containsExactly(nullKeyOrder);

		// clear qualifier/null (find returns default-default)
		service.setDefaultLookupOrder(qualifier, null, null);
		assertThat(service.getDefaultLookupOrder(qualifier, key)).isNull();
		assertThat(service.getLookupOrder(qualifier, key)).containsExactly(defaultOrder);
		assertThat(service.getDefaultLookupOrder(qualifier, null)).isNull();
		assertThat(service.getLookupOrder(qualifier, null)).containsExactly(defaultOrder);
	}

	@Test
	public void testGetWithNodes() {
		IPreferencesService service = Platform.getPreferencesService();
		String qualifier = getUniqueString();
		String key = getUniqueString();
		String expected = getUniqueString();

		// nothing set - navigation
		Preferences node = service.getRootNode().node(TestScope.SCOPE).node(qualifier);
		String actual = node.get(key, null);
		assertNull("10", actual);

		// nothing set - service searching
		actual = service.get(key, null, new Preferences[] {node});
		assertNull("2.0", actual);

		// set value
		node.put(key, expected);

		// value is set - navigation
		actual = node.get(key, null);
		assertNotNull("3.0", actual);
		assertEquals("3.1", expected, actual);

		// value is set - service searching
		actual = service.get(key, null, new Preferences[] {node});
		assertNotNull("4.0", actual);
		assertEquals("4.1", expected, actual);

		// return default value if node list is null
		actual = service.get(key, null, null);
		assertNull("5.0", actual);

		// skip over null nodes
		actual = service.get(key, null, new Preferences[] {null, node});
		assertNotNull("6.0", actual);
		assertEquals("6.1", expected, actual);

		// set the value in the default scope as well
		Preferences defaultNode = service.getRootNode().node(DefaultScope.SCOPE).node(qualifier);
		String defaultValue = getUniqueString();
		defaultNode.put(key, defaultValue);
		actual = defaultNode.get(key, null);
		assertNotNull("7.0", actual);
		assertEquals("7.1", defaultValue, actual);

		// pass in both nodes
		actual = service.get(key, null, new Preferences[] {node, defaultNode});
		assertNotNull("8.0", actual);
		assertEquals("8.1", expected, actual);
		// skip nulls
		actual = service.get(key, null, new Preferences[] {null, node, null, defaultNode, null});
		assertNotNull("8.2", actual);
		assertEquals("8.3", expected, actual);
		// reverse the order
		actual = service.get(key, null, new Preferences[] {defaultNode, node});
		assertNotNull("8.4", actual);
		assertEquals("8.5", defaultValue, actual);
		// skip nulls
		actual = service.get(key, null, new Preferences[] {null, null, defaultNode, null, node, null});
		assertNotNull("8.6", actual);
		assertEquals("8.7", defaultValue, actual);
	}

	@Test
	public void testSearchingStringBasics() {
		IPreferencesService service = Platform.getPreferencesService();
		String qualifier = getUniqueString();
		String key = getUniqueString();
		Preferences node = service.getRootNode().node(TestScope.SCOPE).node(qualifier);
		Preferences defaultNode = service.getRootNode().node(DefaultScope.SCOPE).node(qualifier);
		String value = getUniqueString();
		String defaultValue = getUniqueString() + '1';
		String actual = null;

		List<IScopeContext[]> list = new ArrayList<>();
		list.add(null);
		list.add(new IScopeContext[] {});
		list.add(new IScopeContext[] {null});
		list.add(new IScopeContext[] {new TestScope()});
		list.add(new IScopeContext[] {new TestScope(), DefaultScope.INSTANCE});
		list.add(new IScopeContext[] {DefaultScope.INSTANCE, new TestScope()});
		list.add(new IScopeContext[] {DefaultScope.INSTANCE});
		IScopeContext[][] contexts = list.toArray(new IScopeContext[list.size()][]);

		// nothing is set
		for (int i = 0; i < contexts.length; i++) {
			actual = service.getString(qualifier, key, null, contexts[i]);
			assertNull("1.0." + i, actual);
		}

		// set a default value
		defaultNode.put(key, defaultValue);
		actual = defaultNode.get(key, null);
		assertNotNull("2.0", actual);
		assertEquals("2.1", defaultValue, actual);

		// should find it because "default" is in the default-default lookup order
		for (int i = 0; i < contexts.length; i++) {
			actual = service.getString(qualifier, key, null, contexts[i]);
			assertNotNull("3.0." + i, actual);
			assertEquals("3.1." + i, defaultValue, actual);
		}

		// set a real value
		node.put(key, value);
		actual = node.get(key, null);
		assertNotNull("4.0", actual);
		assertEquals("4.1", value, actual);

		// should find the default value since the "test" scope isn't in the lookup order
		for (int i = 0; i < contexts.length; i++) {
			actual = service.getString(qualifier, key, null, contexts[i]);
			assertNotNull("5.0." + i, actual);
			assertEquals("5.1." + i, defaultValue, actual);
		}

		// set the lookup order for qualifier/null
		String[] setOrder = new String[] {TestScope.SCOPE, DefaultScope.SCOPE};
		service.setDefaultLookupOrder(qualifier, null, setOrder);
		String[] order = service.getLookupOrder(qualifier, null);
		assertThat(order).containsExactly(setOrder);

		// get the value, should be the real one
		for (int i = 0; i < contexts.length; i++) {
			actual = service.getString(qualifier, key, null, contexts[i]);
			assertNotNull("7.0." + i, actual);
			assertEquals("7.1." + i, value, actual);
		}

		// set the order to be the reverse for the qualifier/key
		setOrder = new String[] {DefaultScope.SCOPE, TestScope.SCOPE};
		service.setDefaultLookupOrder(qualifier, key, setOrder);
		order = service.getLookupOrder(qualifier, key);
		assertThat(order).containsExactly(setOrder);

		// get the value, should be the default one
		for (int i = 0; i < contexts.length; i++) {
			actual = service.getString(qualifier, key, null, contexts[i]);
			assertNotNull("9.0." + i, actual);
			assertEquals("9.1." + i, defaultValue, actual);
		}
	}

	@Test
	public void testGet() {
		IPreferencesService service = Platform.getPreferencesService();
		String qualifier = getUniqueString();
		Preferences node = service.getRootNode().node(TestScope.SCOPE).node(qualifier);
		service.setDefaultLookupOrder(qualifier, null, new String[] {TestScope.SCOPE});

		// fun with paths

		String searchPath = "a";
		node.put("a", searchPath);
		assertEquals("3.0", searchPath, service.getString(qualifier, searchPath, null, null));

		searchPath = "a/b";
		node.node("a").put("b", searchPath);
		assertEquals("4.0", searchPath, service.getString(qualifier, searchPath, null, null));

		searchPath = "a//b";
		node.node("a").put("b", searchPath);
		assertEquals("5.0", searchPath, service.getString(qualifier, searchPath, null, null));

		searchPath = "a/b//c";
		node.node("a").node("b").put("c", searchPath);
		assertEquals("6.0", searchPath, service.getString(qualifier, searchPath, null, null));

		searchPath = "a/b//c/d";
		node.node("a").node("b").put("c/d", searchPath);
		assertEquals("7.0", searchPath, service.getString(qualifier, searchPath, null, null));

		searchPath = "/a";
		node.put("a", searchPath);
		assertEquals("8.0", searchPath, service.getString(qualifier, searchPath, null, null));

		searchPath = "/a/b";
		node.node("a").put("b", searchPath);
		assertEquals("9.0", searchPath, service.getString(qualifier, searchPath, null, null));

		searchPath = "///a";
		node.put("/a", searchPath);
		assertEquals("10.0", searchPath, service.getString(qualifier, searchPath, null, null));
	}

	@Test
	public void testImportExceptions() {
		// importing an empty file is an error (invalid file format)
		IPreferencesService service = Platform.getPreferencesService();
		InputStream input = new BufferedInputStream(new ByteArrayInputStream(new byte[0]));
		assertThrows(CoreException.class, () -> service.importPreferences(input));
	}

	@Test
	public void testImportLegacy() throws Exception {
		IPreferencesService service = Platform.getPreferencesService();
		String[] qualifiers = new String[] {getUniqueString() + 1, getUniqueString() + 2};
		String[] oldKeys = new String[] {getUniqueString() + 3, getUniqueString() + 4};
		String[] newKeys = new String[] {getUniqueString() + 5, getUniqueString() + 6};
		Preferences node = service.getRootNode().node(Plugin.PLUGIN_PREFERENCE_SCOPE);
		String actual;

		// nodes shouldn't exist
		for (String qualifier : qualifiers) {
			assertTrue("1.0", !node.nodeExists(qualifier));
		}

		// store some values
		for (String qualifier : qualifiers) {
			Preferences current = node.node(qualifier);
			for (String oldKey : oldKeys) {
				current.put(oldKey, getUniqueString());
				actual = current.get(oldKey, null);
				assertNotNull("2.0." + current.absolutePath() + IPath.SEPARATOR + oldKey, actual);
			}
			for (String newKey : newKeys) {
				actual = current.get(newKey, null);
				assertNull("2.1." + current.absolutePath() + IPath.SEPARATOR + newKey, actual);
			}
		}

		// import a legacy file
		try (InputStream input = getLegacyExportFile(qualifiers, newKeys)) {
			service.importPreferences(input);
		}

		// old values shouldn't exist anymore
		for (String qualifier : qualifiers) {
			Preferences current = node.node(qualifier);
			for (String oldKey : oldKeys) {
				assertNull("4.0." + current.absolutePath() + IPath.SEPARATOR + oldKey, current.get(oldKey, null));
			}
			for (String newKey : newKeys) {
				actual = current.get(newKey, null);
				assertNotNull("4.1." + current.absolutePath() + IPath.SEPARATOR + newKey, actual);
			}
		}
	}

	private InputStream getLegacyExportFile(String[] qualifiers, String[] keys) throws IOException {
		Properties properties = new Properties();
		for (String qualifier : qualifiers) {
			// version id
			properties.put(qualifier, "2.1.3");
			for (String key : keys) {
				properties.put(qualifier + IPath.SEPARATOR + key, getUniqueString());
			}
		}
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			properties.store(output, null);
			return new ByteArrayInputStream(output.toByteArray());
		}
	}

	/*
	 * - create a child node with some key/value pairs
	 * - set the excludes to be the child name
	 * - export the parent
	 * - don't expect anything to be exported
	 */
	@Test
	public void testExportExcludes1() throws Exception {

		// add some random key/value pairs
		String qualifier = getUniqueString();
		String child = "child";
		IEclipsePreferences node = new TestScope().getNode(qualifier);
		Preferences childNode = node.node(child);
		childNode.put("a", "v1");
		childNode.put("b", "v2");
		childNode.put("c", "v3");

		// set the excludes list so it doesn't export anything
		String[] excludes = new String[] {child};

		ExportVerifier verifier = new ExportVerifier(node, excludes);
		verifier.verify();

		// make the child path absolute and try again
		verifier.setExcludes(new String[] {'/' + child});
		verifier.verify();
	}

	/*
	 * - basic export
	 * - set a key/value pair on a node
	 * - export that node
	 * - nothing in the excludes list
	 * - expect that k/v pair to be in the file
	 */
	@Test
	public void testExportExcludes2() throws Exception {
		String qualifier = getUniqueString();
		IEclipsePreferences node = new TestScope().getNode(qualifier);
		String key = getUniqueString();
		String value = getUniqueString();
		node.put(key, value);
		String[] excludesList = new String[] {};

		ExportVerifier verifier = new ExportVerifier(node, excludesList);
		verifier.addExpected(node.absolutePath(), key);
		verifier.addVersion();
		verifier.addExportRoot(node);
		verifier.verify();
	}

	/*
	 * - add 2 key/value pairs to a node
	 * - add one of them to the excludes list
	 * - expect only the other key to exist
	 */
	@Test
	public void testExportExcludes3() throws Exception {
		String qualifier = getUniqueString();
		IEclipsePreferences node = new TestScope().getNode(qualifier);
		String k1 = "a";
		String k2 = "b";
		String v1 = "1";
		String v2 = "2";
		node.put(k1, v1);
		node.put(k2, v2);
		String[] excludesList = new String[] {k1};

		ExportVerifier verifier = new ExportVerifier(node, excludesList);
		verifier.addExpected(node.absolutePath(), k2);
		verifier.addVersion();
		verifier.addExportRoot(node);
		verifier.verify();

		// make the excludes list absolute paths and try again
		verifier.setExcludes(new String[] {'/' + k1});
		verifier.verify();
	}

	/*
	 * - add key/value pairs to a node
	 * - export containing non-matching string
	 * - expect all k/v pairs
	 */
	@Test
	public void testExportExcludes4() throws Exception {
		String qualifier = getUniqueString();
		IEclipsePreferences node = new TestScope().getNode(qualifier);
		String k1 = "a";
		String k2 = "b";
		String v1 = "1";
		String v2 = "2";
		node.put(k1, v1);
		node.put(k2, v2);
		String[] excludesList = new String[] {"bar"};

		ExportVerifier verifier = new ExportVerifier(node, excludesList);
		verifier.addVersion();
		verifier.addExportRoot(node);
		verifier.addExpected(node.absolutePath(), k1);
		verifier.addExpected(node.absolutePath(), k2);
		verifier.verify();
	}

	/**
	 * Tests a default preference value set by a preference initializer extension.
	 */
	@Test
	public void testDefaultFromInitializer() {
		String value = Platform.getPreferencesService().getString(RuntimeTestsPlugin.PI_RUNTIME_TESTS, TestInitializer.DEFAULT_PREF_KEY, null, null);
		assertEquals("1.0", TestInitializer.DEFAULT_PREF_VALUE, value);
	}

	/*
	 * - exporting default values shouldn't do anything
	 */
	@Test
	public void testExportDefaults() throws Exception {
		String qualifier = getUniqueString();
		IEclipsePreferences node = DefaultScope.INSTANCE.getNode(qualifier);
		for (int i = 0; i < 10; i++) {
			node.put(Integer.toString(i), getUniqueString());
		}

		ExportVerifier verifier = new ExportVerifier(node, (String[]) null);
		verifier.verify();
	}

	/*
	 * - create 2 child with 2 key/value pairs each
	 * - excludes is a single key from one of the children
	 * - export parent
	 * - expect all values to be exported but that one
	 */
	@Test
	public void testExportExcludes5() {
		String qualifier = getUniqueString();
		IEclipsePreferences node = new TestScope().getNode(qualifier);
		Preferences child1 = node.node("c1");
		Preferences child2 = node.node("c2");
		String k1 = "a";
		String k2 = "b";
		String v1 = "1";
		String v2 = "2";
		child1.put(k1, v1);
		child1.put(k2, v2);
		child2.put(k1, v1);
		child2.put(k2, v2);
		String[] excludes = new String[] {child1.name() + '/' + k2};
		ExportVerifier verifier = new ExportVerifier(node, excludes);
		verifier.addVersion();
		verifier.addExportRoot(node);
		verifier.addExpected(child1.absolutePath(), k1);
		verifier.addExpected(child2.absolutePath(), k1);
		verifier.addExpected(child2.absolutePath(), k2);
	}

	/**
	 * @deprecated this tests deprecated functions, so deprecation added to avoid
	 *             warnings
	 */
	@Deprecated
	@Test
	public void testValidateVersions() throws Exception {
		final char BUNDLE_VERSION_PREFIX = '@';

		// ensure that there is at least one value in the prefs
		Preferences scopeRoot = Platform.getPreferencesService().getRootNode().node(Plugin.PLUGIN_PREFERENCE_SCOPE);
		scopeRoot.node("org.eclipse.core.tests.runtime").put("key", "value");

		// no errors if the file doesn't exist
		IPath path = FileSystemHelper.getRandomLocation();
		IStatus result = org.eclipse.core.runtime.Preferences.validatePreferenceVersions(path);
		assertTrue("1.0", result.isOK());

		// an empty file wasn't written by #export so its an invalid file format
		// NOTE: this changed from "do nothing" to being an error in Eclipse 3.1
		Properties properties = new Properties();
		try (OutputStream output = new FileOutputStream(path.toFile())) {
			properties.store(output, null);
		}
		result = org.eclipse.core.runtime.Preferences.validatePreferenceVersions(path);
		assertTrue("2.0", !result.isOK());

		// no errors for a file which we write out right now
		org.eclipse.core.runtime.Preferences.exportPreferences(path);
		result = org.eclipse.core.runtime.Preferences.validatePreferenceVersions(path);
		assertTrue("3.1", result.isOK());

		// warning for old versions
		properties = new Properties();
		try (InputStream input = Files.newInputStream(path.toPath())) {
			properties.load(input);
		}
		// change all version numbers to "0" so the validation will fail
		for (Enumeration<Object> e = properties.keys(); e.hasMoreElements();) {
			String key = (String) e.nextElement();
			if (key.charAt(0) == BUNDLE_VERSION_PREFIX) {
				properties.put(key, "0");
			}
		}
		try (OutputStream output = Files.newOutputStream(path.toPath())) {
			properties.store(output, null);
		}
		result = org.eclipse.core.runtime.Preferences.validatePreferenceVersions(path);
		assertTrue("4.2", !result.isOK());

	}

	@Test
	public void testMatches() throws CoreException {
		IPreferencesService service = Platform.getPreferencesService();
		IPreferenceFilter[] matching = null;
		final String QUALIFIER = getUniqueString();

		// an empty transfer list matches nothing
		matching = service.matches(service.getRootNode(), new IPreferenceFilter[0]);
		assertThat(matching).isEmpty();

		// don't match this filter
		IPreferenceFilter filter = new IPreferenceFilter() {
			@Override
			public Map<String, PreferenceFilterEntry[]> getMapping(String scope) {
				Map<String, PreferenceFilterEntry[]> result = new HashMap<>();
				result.put(QUALIFIER, null);
				return result;
			}

			@Override
			public String[] getScopes() {
				return new String[] {InstanceScope.SCOPE};
			}
		};
		matching = service.matches(service.getRootNode(), new IPreferenceFilter[] { filter });
		assertThat(matching).isEmpty();

		// shouldn't match since there are no key/value pairs in the node
		matching = service.matches(service.getRootNode(), new IPreferenceFilter[] { filter });
		assertThat(matching).isEmpty();

		// add some values so it does match
		InstanceScope.INSTANCE.getNode(QUALIFIER).put("key", "value");
		matching = service.matches(service.getRootNode(), new IPreferenceFilter[] { filter });
		assertThat(matching).hasSize(1);

		// should match on the exact node too
		matching = service.matches(InstanceScope.INSTANCE.getNode(QUALIFIER), new IPreferenceFilter[] { filter });
		assertThat(matching).hasSize(1);

		// shouldn't match a different scope
		matching = service.matches(ConfigurationScope.INSTANCE.getNode(QUALIFIER), new IPreferenceFilter[] { filter });
		assertThat(matching).isEmpty();

		// try matching on the root node for a filter which matches all nodes in the scope
		filter = new IPreferenceFilter() {
			@Override
			public Map<String, PreferenceFilterEntry[]> getMapping(String scope) {
				return null;
			}

			@Override
			public String[] getScopes() {
				return new String[] {InstanceScope.SCOPE};
			}
		};
		matching = service.matches(InstanceScope.INSTANCE.getNode(QUALIFIER), new IPreferenceFilter[] { filter });
		assertThat(matching).hasSize(1);

		// shouldn't match
		matching = service.matches(ConfigurationScope.INSTANCE.getNode(QUALIFIER), new IPreferenceFilter[] { filter });
		assertThat(matching).isEmpty();
	}

	/*
	 * See bug 95608 - A node should match if it has any keys OR has a child node
	 * with keys.
	 */
	@Test
	public void testMatches2() throws CoreException {
		IPreferencesService service = Platform.getPreferencesService();
		final String QUALIFIER = getUniqueString();

		// setup - create a child node with a key/value pair
		InstanceScope.INSTANCE.getNode(QUALIFIER).node("child").put("key", "value");
		IPreferenceFilter[] filters = new IPreferenceFilter[] {new IPreferenceFilter() {
			@Override
			public Map<String, PreferenceFilterEntry[]> getMapping(String scope) {
				Map<String, PreferenceFilterEntry[]> result = new HashMap<>();
				result.put(QUALIFIER, null);
				return result;
			}

			@Override
			public String[] getScopes() {
				return new String[] {InstanceScope.SCOPE};
			}
		}};
		assertThat(service.matches(service.getRootNode(), filters)).hasSize(1);
	}

	@Test
	public void testExportWithTransfers1() throws Exception {

		final String VALID_QUALIFIER = getUniqueString();
		IPreferenceFilter transfer = new IPreferenceFilter() {
			@Override
			public Map<String, PreferenceFilterEntry[]> getMapping(String scope) {
				Map<String, PreferenceFilterEntry[]> result = new HashMap<>();
				result.put(VALID_QUALIFIER, null);
				return result;
			}

			@Override
			public String[] getScopes() {
				return new String[] {InstanceScope.SCOPE};
			}
		};

		IPreferencesService service = Platform.getPreferencesService();
		ExportVerifier verifier = new ExportVerifier(service.getRootNode(), new IPreferenceFilter[] {transfer});

		IEclipsePreferences node = InstanceScope.INSTANCE.getNode(VALID_QUALIFIER);
		String VALID_KEY_1 = "key1";
		String VALID_KEY_2 = "key2";
		node.put(VALID_KEY_1, "value1");
		node.put(VALID_KEY_2, "value2");

		verifier.addVersion();
		verifier.addExportRoot(service.getRootNode());
		verifier.addExpected(node.absolutePath(), VALID_KEY_1);
		verifier.addExpected(node.absolutePath(), VALID_KEY_2);

		node = InstanceScope.INSTANCE.getNode(getUniqueString());
		node.put("invalidkey1", "value1");
		node.put("invalidkey2", "value2");

		verifier.verify();
	}

	/*
	 * Test exporting with a transfer that returns null for the mapping. This means
	 * export everything in the scope.
	 */
	@Test
	public void testExportWithTransfers2() throws Exception {
		final String VALID_QUALIFIER = getUniqueString();
		IPreferenceFilter transfer = new IPreferenceFilter() {
			@Override
			public Map<String, PreferenceFilterEntry[]> getMapping(String scope) {
				return null;
			}

			@Override
			public String[] getScopes() {
				return new String[] {TestScope.SCOPE};
			}
		};

		IPreferencesService service = Platform.getPreferencesService();
		final ExportVerifier verifier = new ExportVerifier(service.getRootNode(), new IPreferenceFilter[] {transfer});

		IEclipsePreferences testNode = new TestScope().getNode(VALID_QUALIFIER);
		String VALID_KEY_1 = "key1";
		String VALID_KEY_2 = "key2";
		testNode.put(VALID_KEY_1, "value1");
		testNode.put(VALID_KEY_2, "value2");

		IPreferenceNodeVisitor visitor = node -> {
			String[] keys = node.keys();
			for (String key : keys) {
				verifier.addExpected(node.absolutePath(), key);
			}
			return true;
		};
		((IEclipsePreferences) service.getRootNode().node(TestScope.SCOPE)).accept(visitor);
		verifier.addVersion();
		verifier.addExportRoot(service.getRootNode());

		testNode = InstanceScope.INSTANCE.getNode(getUniqueString());
		testNode.put("invalidkey1", "value1");
		testNode.put("invalidkey2", "value2");

		verifier.verify();
	}

	/*
	 * See Bug 95608 - [Preferences] How do I specify a nested node in a preferenceTransfer
	 * When you specify a transfer with children, you should export it even if the
	 * parent doesn't have any key/value pairs.
	 */
	@Test
	public void testExportWithTransfers3() throws Exception {

		final String QUALIFIER = getUniqueString();
		IPreferenceFilter transfer = new IPreferenceFilter() {
			@Override
			public Map<String, PreferenceFilterEntry[]> getMapping(String scope) {
				Map<String, PreferenceFilterEntry[]> result = new HashMap<>();
				result.put(QUALIFIER, null);
				return result;
			}

			@Override
			public String[] getScopes() {
				return new String[] {InstanceScope.SCOPE};
			}
		};

		IPreferencesService service = Platform.getPreferencesService();
		ExportVerifier verifier = new ExportVerifier(service.getRootNode(), new IPreferenceFilter[] {transfer});

		Preferences node = InstanceScope.INSTANCE.getNode(QUALIFIER).node("child");
		String VALID_KEY_1 = "key1";
		String VALID_KEY_2 = "key2";
		node.put(VALID_KEY_1, "value1");
		node.put(VALID_KEY_2, "value2");

		verifier.addVersion();
		verifier.addExportRoot(service.getRootNode());
		verifier.addExpected(node.absolutePath(), VALID_KEY_1);
		verifier.addExpected(node.absolutePath(), VALID_KEY_2);

		node = InstanceScope.INSTANCE.getNode(getUniqueString());
		node.put("invalidkey1", "value1");
		node.put("invalidkey2", "value2");

		verifier.verify();
	}

	/*
	 * See Bug 208564 - [Preferences] preferenceTransfer: Allow wildcards on keys
	 * Since 3.6 preferenceTransfer allows to define common prefix for group of
	 * preferences
	 */
	@Test
	public void testExportWithTransfers4() throws Exception {
		final String VALID_QUALIFIER = getUniqueString();
		final String COMMON_PREFIX = "PREFIX.";
		IPreferenceFilter transfer = new IPreferenceFilter() {
			Map<String, PreferenceFilterEntry[]> result;

			@Override
			public Map<String, PreferenceFilterEntry[]> getMapping(String scope) {
				if (result == null) {
					result = new HashMap<>();
					result.put(VALID_QUALIFIER, new PreferenceFilterEntry[] {new PreferenceFilterEntry(COMMON_PREFIX, "prefix")});
				}
				return result;
			}

			@Override
			public String[] getScopes() {
				return new String[] {InstanceScope.SCOPE};
			}
		};
		IPreferenceFilter[] matching = null;
		IPreferencesService service = Platform.getPreferencesService();

		IEclipsePreferences node = InstanceScope.INSTANCE.getNode(VALID_QUALIFIER);
		String VALID_KEY_1 = COMMON_PREFIX + "key1";
		String VALID_KEY_2 = COMMON_PREFIX + "key2";
		String VALID_KEY_3 = "key3";
		String VALID_KEY_4 = "key4";
		node.put(VALID_KEY_1, "value1");
		node.put(VALID_KEY_2, "value2");
		node.put(VALID_KEY_3, "value3");
		node.put(VALID_KEY_4, "value4");

		matching = service.matches(service.getRootNode(), new IPreferenceFilter[] { transfer });
		assertThat(matching).hasSize(1);

		ExportVerifier verifier = new ExportVerifier(service.getRootNode(), new IPreferenceFilter[] {transfer});
		verifier.addVersion();
		verifier.addExportRoot(service.getRootNode());
		verifier.addExpected(node.absolutePath(), VALID_KEY_1);
		verifier.addExpected(node.absolutePath(), VALID_KEY_2);
		verifier.verify();
	}

	/**
	 * The test verifies that there is no PreferenceModifyListener which modifies unknown
	 * exported preferences node. Unnecessary nodes must not be created because empty
	 * exported node means that when it is applied to global preferences hierarchy, all
	 * preferences from the global node with the same path as the exported node
	 * will be removed.
	 * <p>
	 * If the test fails, you need to fix every PreferenceModifyListener that created
	 * unnecessary node. Failure message gives a hint which plug-ins you need to start
	 * with, although it is not 100% accurate since some plug-ins migrate old-style
	 * preferences from other plug-ins.
	 * <p>
	 * If you need to manually verify the bug, use the steps from
	 * https://bugs.eclipse.org/bugs/show_bug.cgi?id=418046#c0
	 * <p>
	 * For more details see bug 418046:
	 * https://bugs.eclipse.org/bugs/show_bug.cgi?id=418046
	 */
	@Test
	public void testApplyAndExportedPreferencesNotModified() throws BackingStoreException, CoreException {
		// create a dummy node and export it to a stream
		IEclipsePreferences toExport = InstanceScope.INSTANCE.getNode("bug418046");
		toExport.put("someKey", "someValue");
		IPreferencesService service = Platform.getPreferencesService();
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		assertTrue(service.exportPreferences(toExport, stream, null).isOK());

		// read preferences from a stream
		IExportedPreferences exported = service.readPreferences(new ByteArrayInputStream(stream.toByteArray()));

		// verify that reading from a stream does not modify exported preferences
		verifyExportedPreferencesNotModified(exported);

		// use the export root node to apply it to the global preferences hierarchy;
		// it does not change the behavior of the apply method, but shows more problems in
		// implementations of PreferenceModifyListener
		exported = (IExportedPreferences) exported.node(InstanceScope.SCOPE).node("bug418046");

		// apply exported preferences to the global preferences hierarchy
		assertTrue(service.applyPreferences(exported).isOK());

		// verify that applying does not modify exported preferences
		verifyExportedPreferencesNotModified(exported);
	}

	private void verifyExportedPreferencesNotModified(IExportedPreferences exported) throws BackingStoreException {
		Preferences node = exported;
		while (node.parent() != null) {
			node = node.parent();
		}
		String debugString = ((EclipsePreferences) node).toDeepDebugString();
		String[] children = node.childrenNames();
		assertThat(children).as(debugString).hasSize(1);

		assertEquals(InstanceScope.SCOPE, children[0]);
		node = node.node(children[0]);
		children = node.childrenNames();
		assertThat(children).as(debugString).hasSize(1);

		assertEquals("bug418046", children[0]);
		node = node.node(children[0]);
		children = node.childrenNames();
		assertThat(children).as(debugString).isEmpty();

		assertEquals(debugString, "someValue", node.get("someKey", null));
	}

	@Test
	@Disabled("not implemented yet")
	public void testApplyWithTransfers() {
	}

	private static String getUniqueString() {
		return UUID.randomUUID().toString();
	}

}
