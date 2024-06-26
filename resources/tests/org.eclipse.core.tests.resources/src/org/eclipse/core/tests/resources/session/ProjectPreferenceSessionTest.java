/*******************************************************************************
 *  Copyright (c) 2005, 2015 IBM Corporation and others.
 *
 *  This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License 2.0
 *  which accompanies this distribution, and is available at
 *  https://www.eclipse.org/legal/epl-2.0/
 *
 *  SPDX-License-Identifier: EPL-2.0
 *
 *  Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.tests.resources.session;

import static org.eclipse.core.resources.ResourcesPlugin.getWorkspace;
import static org.eclipse.core.tests.resources.ResourceTestPluginConstants.PI_RESOURCES_TESTS;
import static org.eclipse.core.tests.resources.ResourceTestUtil.createInWorkspace;
import static org.eclipse.core.tests.resources.ResourceTestUtil.createTestMonitor;
import static org.eclipse.core.tests.resources.ResourceTestUtil.waitForRefresh;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.tests.harness.session.SessionTestExtension;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ProjectPreferenceSessionTest {
	private static final String DIR_NAME = ".settings";
	private static final String FILE_EXTENSION = "prefs";

	@RegisterExtension
	static SessionTestExtension sessionTestExtension = SessionTestExtension.forPlugin(PI_RESOURCES_TESTS)
			.withCustomization(SessionTestExtension.createCustomWorkspace()).create();

	private void saveWorkspace() throws Exception {
		getWorkspace().save(true, createTestMonitor());
	}

	/*
	 * See bug 91244
	 * - set some project settings
	 * - save them
	 * - exit the session
	 * - startup
	 * - delete the .prefs file from disk
	 */
	@Test
	@Order(1)
	public void testDeleteFileBeforeLoad1() throws Exception {
		IProject project = getProject("testDeleteFileBeforeLoad");
		String qualifier = "test.delete.file.before.load";
		createInWorkspace(project);
		IScopeContext context = new ProjectScope(project);
		Preferences node = context.getNode(qualifier);
		node.put("key", "value");
		node.flush();
		waitForRefresh();
		IFile file = project.getFile(IPath.fromOSString(DIR_NAME).append(qualifier).addFileExtension(FILE_EXTENSION));
		assertTrue(file.exists());
		assertTrue(file.getLocation().toFile().exists());
		saveWorkspace();
	}

	@Test
	@Order(2)
	public void testDeleteFileBeforeLoad2() throws Exception {
		IProject project = getProject("testDeleteFileBeforeLoad");
		Platform.getPreferencesService().getRootNode().node(ProjectScope.SCOPE).node(project.getName());
		AtomicReference<BackingStoreException> exceptionInListener = new AtomicReference<>();
		ILogListener listener = (status, plugin) -> {
			if (!Platform.PI_RUNTIME.equals(plugin)) {
				return;
			}
			Throwable t = status.getException();
			if (t instanceof BackingStoreException backingStoreException) {
				exceptionInListener.set(backingStoreException);
			}
		};
		try {
			Platform.addLogListener(listener);
			project.delete(IResource.NONE, createTestMonitor());
		} finally {
			Platform.removeLogListener(listener);
		}
		if (exceptionInListener.get() != null) {
			throw exceptionInListener.get();
		}
		saveWorkspace();
	}

	/*
	 * Test saving a key/value pair in one session and then ensure that they exist
	 * in the next session.
	 */
	@Test
	@Order(3)
	public void testSaveLoad1() throws Exception {
		IProject project = getProject("testSaveLoad");
		createInWorkspace(project);
		IScopeContext context = new ProjectScope(project);
		Preferences node = context.getNode("test.save.load");
		node.put("key", "value");
		node.flush();
		saveWorkspace();
	}

	@Test
	@Order(4)
	public void testSaveLoad2() throws Exception {
		IProject project = getProject("testSaveLoad");
		IScopeContext context = new ProjectScope(project);
		Preferences node = context.getNode("test.save.load");
		assertEquals("value", node.get("key", null));
		saveWorkspace();
	}

	private static IProject getProject(String name) {
		return getWorkspace().getRoot().getProject(name);
	}
}
