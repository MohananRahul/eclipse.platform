/*******************************************************************************
 * Copyright (c) 2000, 2015 IBM Corporation and others.
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
 *******************************************************************************/
package org.eclipse.core.tests.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.core.tests.resources.ResourceTestPluginConstants.NATURE_127562;
import static org.eclipse.core.tests.resources.ResourceTestPluginConstants.NATURE_EARTH;
import static org.eclipse.core.tests.resources.ResourceTestPluginConstants.NATURE_MISSING;
import static org.eclipse.core.tests.resources.ResourceTestPluginConstants.NATURE_SIMPLE;
import static org.eclipse.core.tests.resources.ResourceTestPluginConstants.NATURE_SNOW;
import static org.eclipse.core.tests.resources.ResourceTestPluginConstants.NATURE_WATER;
import static org.eclipse.core.tests.resources.ResourceTestPluginConstants.getInvalidNatureSets;
import static org.eclipse.core.tests.resources.ResourceTestPluginConstants.getValidNatureSets;
import static org.eclipse.core.tests.resources.ResourceTestUtil.createInWorkspace;
import static org.eclipse.core.tests.resources.ResourceTestUtil.createTestMonitor;
import static org.eclipse.core.tests.resources.ResourceTestUtil.createUniqueString;
import static org.junit.Assert.assertThrows;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.internal.resources.CheckMissingNaturesListener;
import org.eclipse.core.internal.resources.File;
import org.eclipse.core.internal.resources.PreferenceInitializer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.core.tests.internal.resources.SimpleNature;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

/**
 * Tests all aspects of project natures.  These tests only
 * exercise API classes and methods.  Note that the nature-related
 * APIs on IWorkspace are tested by IWorkspaceTest.
 */
public class NatureTest {

	@Rule
	public WorkspaceTestRule workspaceRule = new WorkspaceTestRule();

	IProject project;

	/**
	 * Sets the given set of natures for the project.  If success
	 * does not match the "shouldFail" argument, an assertion error
	 * with the given message is thrown.
	 */
	private void setNatures(IProject project, String[] natures, boolean shouldFail) throws Throwable {
		setNatures(project, natures, shouldFail, false);
	}

	/**
	 * Sets the given set of natures for the project.  If success
	 * does not match the "shouldFail" argument, an assertion error
	 * with the given message is thrown.
	 */
	private void setNatures(IProject project, String[] natures, boolean shouldFail, boolean silent)
			throws Throwable {
		IProjectDescription desc = project.getDescription();
		desc.setNatureIds(natures);
		int flags;
		if (silent) {
			flags = IResource.KEEP_HISTORY | IResource.AVOID_NATURE_CONFIG;
		} else {
			flags = IResource.KEEP_HISTORY;
		}
		ThrowingRunnable descriptionSetter = () -> project.setDescription(desc, flags, createTestMonitor());
		if (shouldFail) {
			assertThrows(CoreException.class, descriptionSetter);
		} else {
			descriptionSetter.run();
		}
	}

	@After
	public void tearDown() throws Exception {
		project.delete(true, null);
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).putInt(ResourcesPlugin.PREF_MISSING_NATURE_MARKER_SEVERITY, PreferenceInitializer.PREF_MISSING_NATURE_MARKER_SEVERITY_DEFAULT);
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).flush();
	}

	@Before
	public void setUp() throws Exception {
		this.project = ResourcesPlugin.getWorkspace().getRoot().getProject(createUniqueString());
	}

	private void assertHasEnabledNature(String nature) throws CoreException {
		assertThat(project.hasNature(nature))
				.withFailMessage("project '%s' is expected to have nature: %s", project, nature).isTrue();
		assertThat(project.isNatureEnabled(nature))
				.withFailMessage("project '%s' is expected to have nature enabled: %s", project, nature).isTrue();
	}

	private void assertDoesNotHaveNature(String nature) throws CoreException {
		assertThat(project.hasNature(nature))
				.withFailMessage("project '%s' is not expected to have nature: %s", project, nature).isFalse();
		assertThat(project.isNatureEnabled(nature))
				.withFailMessage("project '%s' is not expected to have nature enabled: %s", project, nature).isFalse();
	}

	/**
	 * Tests invalid additions to the set of natures for a project.
	 */
	@Test
	public void testInvalidAdditions() throws Throwable {
		createInWorkspace(project);
		setNatures(project, new String[] { NATURE_SIMPLE }, false);

		//Adding a nature that is not available.
		setNatures(project, new String[] { NATURE_SIMPLE, NATURE_MISSING }, true);
		assertHasEnabledNature(NATURE_SIMPLE);
		assertDoesNotHaveNature(NATURE_MISSING);

		//Adding a nature that has a missing prerequisite.
		setNatures(project, new String[] { NATURE_SIMPLE, NATURE_SNOW }, true);
		assertHasEnabledNature(NATURE_SIMPLE);
		assertDoesNotHaveNature(NATURE_SNOW);

		//Adding a nature that creates a duplicated set member.
		setNatures(project, new String[] { NATURE_EARTH }, false);
		setNatures(project, new String[] { NATURE_EARTH, NATURE_WATER }, true);
		assertHasEnabledNature(NATURE_EARTH);
		assertDoesNotHaveNature(NATURE_WATER);
	}

	/**
	 * Tests invalid removals from the set of natures for a project.
	 */
	@Test
	public void testInvalidRemovals() throws Throwable {
		createInWorkspace(project);

		//Removing a nature that still has dependents.
		setNatures(project, new String[] { NATURE_WATER, NATURE_SNOW }, false);
		setNatures(project, new String[] { NATURE_SNOW }, true);
		assertHasEnabledNature(NATURE_WATER);
		assertHasEnabledNature(NATURE_SNOW);
	}

	@Test
	public void testNatureLifecyle() throws Throwable {
		createInWorkspace(project);

		//add simple nature
		setNatures(project, new String[] { NATURE_SIMPLE }, false);
		SimpleNature instance = SimpleNature.getInstance();
		assertThat(instance.wasConfigured).withFailMessage("Simple nature has not been configured").isTrue();
		assertThat(instance.wasDeconfigured).withFailMessage("Simple nature has been deconfigured").isFalse();
		instance.reset();

		//remove simple nature
		setNatures(project, new String[0], false);
		instance = SimpleNature.getInstance();
		assertThat(instance.wasConfigured).withFailMessage("Simple nature has been configured").isFalse();
		assertThat(instance.wasDeconfigured).withFailMessage("Simple nature has not been deconfigured").isTrue();

		//add with AVOID_NATURE_CONFIG
		instance.reset();
		setNatures(project, new String[] { NATURE_SIMPLE }, false, true);
		instance = SimpleNature.getInstance();
		assertThat(instance.wasConfigured).withFailMessage("Simple nature has been configured").isFalse();
		assertThat(instance.wasDeconfigured).withFailMessage("Simple nature has been deconfigured").isFalse();
		assertHasEnabledNature(NATURE_SIMPLE);

		//remove with AVOID_NATURE_CONFIG
		instance.reset();
		setNatures(project, new String[0], false, true);
		instance = SimpleNature.getInstance();
		assertThat(instance.wasConfigured).withFailMessage("Simple nature has been configured").isFalse();
		assertThat(instance.wasDeconfigured).withFailMessage("Simple nature has been deconfigured").isFalse();
		assertDoesNotHaveNature(NATURE_SIMPLE);
	}

	/**
	 * Test simple addition and removal of natures.
	 */
	@Test
	public void testSimpleNature() throws Throwable {
		createInWorkspace(project);

		String[][] valid = getValidNatureSets();
		for (String[] element : valid) {
			setNatures(project, element, false);
		}
		//configure a valid nature before starting invalid tests
		String[] currentSet = new String[] {NATURE_SIMPLE};
		setNatures(project, currentSet, false);

		//now do invalid tests and ensure simple nature is still configured
		String[][] invalid = getInvalidNatureSets();
		for (String[] element : invalid) {
			setNatures(project, element, true);
			assertHasEnabledNature(NATURE_SIMPLE);
			assertDoesNotHaveNature(NATURE_WATER);
			assertThat(currentSet).isEqualTo(project.getDescription().getNatureIds());
		}
	}

	/**
	 * Test addition of nature that requires the workspace root.
	 * See bugs 127562 and  128709.
	 */
	@Test
	public void testBug127562Nature() throws Throwable {
		createInWorkspace(project);
		IWorkspace ws = project.getWorkspace();

		String[][] valid = getValidNatureSets();
		for (String[] element : valid) {
			setNatures(project, element, false);
		}

		// add with AVOID_NATURE_CONFIG
		String[] currentSet = new String[] {NATURE_127562};
		setNatures(project, currentSet, false, true);

		// configure the nature using a conflicting scheduling rule
		IJobManager manager = Job.getJobManager();
		try {
			manager.beginRule(ws.getRuleFactory().modifyRule(project), null);
			assertThrows(IllegalArgumentException.class, () -> project.getNature(NATURE_127562).configure());
		} finally {
			manager.endRule(ws.getRuleFactory().modifyRule(project));
		}

		// configure the nature using a non-conflicting scheduling rule
		try {
			manager.beginRule(ws.getRoot(), null);
			project.getNature(NATURE_127562).configure();
		} finally {
			manager.endRule(ws.getRoot());
		}
	}

	@Test
	public void testBug297871() throws Throwable {
		createInWorkspace(project);

		IFileStore descStore = ((File) project.getFile(IProjectDescription.DESCRIPTION_FILE_NAME)).getStore();
		java.io.File desc = descStore.toLocalFile(EFS.NONE, createTestMonitor());

		java.io.File descTmp = new java.io.File(desc.getPath() + ".tmp");
		Files.copy(desc.toPath(), descTmp.toPath());

		setNatures(project, new String[] { NATURE_EARTH }, false);

		assertHasEnabledNature(NATURE_EARTH);
		assertThat(project.getNature(NATURE_EARTH)).isNotNull();

		Files.copy(descTmp.toPath(), desc.toPath(), StandardCopyOption.REPLACE_EXISTING);
		// Make sure enough time has past to bump file's
		// timestamp during the copy
		org.eclipse.core.tests.resources.ResourceTestUtil
				.touchInFilesystem(project.getFile(IProjectDescription.DESCRIPTION_FILE_NAME));

		// should read file from filesystem, creating new IProjectDescription:
		project.refreshLocal(IResource.DEPTH_INFINITE, createTestMonitor());
		assertDoesNotHaveNature(NATURE_EARTH);
		assertThat(project.getNature(NATURE_EARTH)).isNull();
	}

	/**
	 * Changes project description and parallel checks {@link IProject#isNatureEnabled(String)},
	 * to check if natures value is cached properly.
	 *
	 * See Bug 338055.
	 */
	@Test
	public void testBug338055() throws Exception {
		final boolean finished[] = new boolean[1];
		createInWorkspace(project);

		AtomicReference<CoreException> failureInJob = new AtomicReference<>();
		Job simulateNatureAccessJob = new Job("CheckNatureJob") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					if (!finished[0]) {
						if (project.exists() && project.isOpen()) {
							project.isNatureEnabled(NATURE_SIMPLE);
						}
						schedule();
					}
				} catch (CoreException e) {
					failureInJob.set(e);
				}
				return Status.OK_STATUS;
			}
		};
		simulateNatureAccessJob.schedule();

		// Make sure enough time has past to bump file's
		// timestamp during the copy
		Thread.sleep(1000);

		IFileStore descStore = ((File) project.getFile(IProjectDescription.DESCRIPTION_FILE_NAME)).getStore();

		// create a description with many natures, this will make updating description longer
		StringBuilder description = new StringBuilder();
		description.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><projectDescription><name></name><comment></comment><projects></projects><buildSpec></buildSpec><natures>");
		description.append("<nature>" + NATURE_SIMPLE + "</nature>");
		for (int i = 0; i < 100; i++) {
			description.append("<nature>nature" + i + "</nature>");
		}
		description.append("</natures></projectDescription>\n");

		// write the description
		try (OutputStream output = descStore.openOutputStream(EFS.NONE, createTestMonitor());) {
			output.write(description.toString().getBytes());
		}

		project.refreshLocal(IResource.DEPTH_INFINITE, createTestMonitor());

		finished[0] = true;
		simulateNatureAccessJob.join();
		assertHasEnabledNature(NATURE_SIMPLE);
	}

	@Test
	public void testMissingNatureAddsMarker() throws Exception {
		createInWorkspace(project);
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).putInt(ResourcesPlugin.PREF_MISSING_NATURE_MARKER_SEVERITY, IMarker.SEVERITY_WARNING);
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).flush();
		IProjectDescription desc = project.getDescription();
		desc.setNatureIds(new String[] {NATURE_MISSING});
		project.setDescription(desc, IResource.FORCE | IResource.AVOID_NATURE_CONFIG, createTestMonitor());
		project.refreshLocal(IResource.DEPTH_INFINITE, createTestMonitor());
		project.build(IncrementalProjectBuilder.FULL_BUILD, createTestMonitor());
		Job.getJobManager().wakeUp(CheckMissingNaturesListener.MARKER_TYPE);
		Job.getJobManager().join(CheckMissingNaturesListener.MARKER_TYPE, createTestMonitor());
		IMarker[] markers = project.findMarkers(CheckMissingNaturesListener.MARKER_TYPE, false, IResource.DEPTH_ONE);
		assertThat(markers).hasSize(1);
		IMarker marker = markers[0];
		assertThat(marker.getAttribute("natureId")).isEqualTo(NATURE_MISSING);
		assertThat(marker.getAttribute(IMarker.CHAR_START, -42)).isNotEqualTo(-42);
		assertThat(marker.getAttribute(IMarker.CHAR_END, -42)).isNotEqualTo(-42);
		String content = ((IFile) marker.getResource()).readString();
		String marked = content.substring(marker.getAttribute(IMarker.CHAR_START, -42), marker.getAttribute(IMarker.CHAR_END, -42));
		assertThat(marked).isEqualTo(NATURE_MISSING);
	}

	@Test
	public void testMissingNatureWithWhitespacesSetChars() throws Exception {
		createInWorkspace(project);
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).putInt(ResourcesPlugin.PREF_MISSING_NATURE_MARKER_SEVERITY, IMarker.SEVERITY_WARNING);
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).flush();
		IFile dotProjectFile = project.getFile(IProjectDescription.DESCRIPTION_FILE_NAME);
		dotProjectFile
				.setContents(
						("<projectDescription><name>" + project.getName() + "</name><natures><nature> " + NATURE_MISSING
								+ "  </nature></natures></projectDescription>").getBytes(),
						false, false, createTestMonitor());
		project.refreshLocal(IResource.DEPTH_INFINITE, createTestMonitor());
		project.build(IncrementalProjectBuilder.FULL_BUILD, createTestMonitor());
		Job.getJobManager().wakeUp(CheckMissingNaturesListener.MARKER_TYPE);
		Job.getJobManager().join(CheckMissingNaturesListener.MARKER_TYPE, createTestMonitor());
		IMarker[] markers = project.findMarkers(CheckMissingNaturesListener.MARKER_TYPE, false, IResource.DEPTH_ONE);
		assertThat(markers).hasSize(1);
		IMarker marker = markers[0];
		assertThat(marker.getAttribute("natureId")).isEqualTo(NATURE_MISSING);
		assertThat(marker.getAttribute(IMarker.CHAR_START, -42)).isNotEqualTo(-42);
		assertThat(marker.getAttribute(IMarker.CHAR_END, -42)).isNotEqualTo(-42);
		String content = ((IFile) marker.getResource()).readString();
		String marked = content.substring(marker.getAttribute(IMarker.CHAR_START, -42), marker.getAttribute(IMarker.CHAR_END, -42));
		assertThat(marked).isEqualTo(NATURE_MISSING);
	}

	@Test
	public void testKnownNatureDoesntAddMarker() throws Exception {
		createInWorkspace(project);
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).putInt(ResourcesPlugin.PREF_MISSING_NATURE_MARKER_SEVERITY, IMarker.SEVERITY_WARNING);
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).flush();
		IProjectDescription desc = project.getDescription();
		desc.setNatureIds(new String[] {NATURE_SIMPLE});
		project.setDescription(desc, createTestMonitor());
		project.refreshLocal(IResource.DEPTH_INFINITE, createTestMonitor());
		project.build(IncrementalProjectBuilder.FULL_BUILD, createTestMonitor());
		Job.getJobManager().wakeUp(CheckMissingNaturesListener.MARKER_TYPE);
		Job.getJobManager().join(CheckMissingNaturesListener.MARKER_TYPE, createTestMonitor());
		assertThat(project.findMarkers(CheckMissingNaturesListener.MARKER_TYPE, false, IResource.DEPTH_ONE)).isEmpty();
	}

	@Test
	public void testListenToPreferenceChange() throws Exception {
		testMissingNatureAddsMarker();
		// to INFO
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).putInt(ResourcesPlugin.PREF_MISSING_NATURE_MARKER_SEVERITY, IMarker.SEVERITY_INFO);
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).flush();
		Job.getJobManager().wakeUp(CheckMissingNaturesListener.MARKER_TYPE);
		Job.getJobManager().join(CheckMissingNaturesListener.MARKER_TYPE, createTestMonitor());
		IMarker[] markers = project.findMarkers(CheckMissingNaturesListener.MARKER_TYPE, false, IResource.DEPTH_ONE);
		assertThat(markers).hasSize(1).satisfiesExactly(
				marker -> assertThat(marker.getAttribute(IMarker.SEVERITY, -42)).isEqualTo(IMarker.SEVERITY_INFO));
		// to IGNORE
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).putInt(ResourcesPlugin.PREF_MISSING_NATURE_MARKER_SEVERITY, -1);
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).flush();
		Job.getJobManager().wakeUp(CheckMissingNaturesListener.MARKER_TYPE);
		Job.getJobManager().join(CheckMissingNaturesListener.MARKER_TYPE, createTestMonitor());
		markers = project.findMarkers(CheckMissingNaturesListener.MARKER_TYPE, false, IResource.DEPTH_ONE);
		assertThat(markers).isEmpty();
		// to ERROR
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).putInt(ResourcesPlugin.PREF_MISSING_NATURE_MARKER_SEVERITY, IMarker.SEVERITY_ERROR);
		InstanceScope.INSTANCE.getNode(ResourcesPlugin.PI_RESOURCES).flush();
		Job.getJobManager().wakeUp(CheckMissingNaturesListener.MARKER_TYPE);
		Job.getJobManager().join(CheckMissingNaturesListener.MARKER_TYPE, createTestMonitor());
		markers = project.findMarkers(CheckMissingNaturesListener.MARKER_TYPE, false, IResource.DEPTH_ONE);
		assertThat(markers).hasSize(1).satisfiesExactly(
				marker -> assertThat(marker.getAttribute(IMarker.SEVERITY, -42)).isEqualTo(IMarker.SEVERITY_ERROR));
	}

}
