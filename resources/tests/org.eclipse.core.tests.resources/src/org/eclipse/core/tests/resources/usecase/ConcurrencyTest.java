/*******************************************************************************
 * Copyright (c) 2000, 2012 IBM Corporation and others.
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
package org.eclipse.core.tests.resources.usecase;

import static org.eclipse.core.resources.ResourcesPlugin.getWorkspace;
import static org.eclipse.core.tests.resources.ResourceTestUtil.removeFromWorkspace;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.tests.resources.util.WorkspaceResetExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WorkspaceResetExtension.class)
public class ConcurrencyTest {

	protected void assertIsNotRunning(ConcurrentOperation01 op) {
		/* try more than once, "just in case" */
		for (int i = 0; i < 3; i++) {
			try {
				Thread.sleep(100 * i); // fancy sleep
			} catch (InterruptedException e) {
				// ignore
			}
			assertFalse(op.isRunning());
		}
	}

	/**
	 * This test is used to find out if two operations can start concurrently. It assumes
	 * that they cannot.
	 */
	@Test
	public void testConcurrentOperations() throws CoreException {
		IProject project = getWorkspace().getRoot().getProject("MyProject");
		project.create(null);
		project.open(null);

		ConcurrentOperation01 op1 = new ConcurrentOperation01(getWorkspace());
		ConcurrentOperation01 op2 = new ConcurrentOperation01(getWorkspace());

		/* start first operation */
		new Thread(op1, "op1").start();
		assertTrue(op1.hasStarted());
		op1.returnWhenInSyncPoint();
		assertTrue(op1.isRunning());

		/* start second operation but it should not run until the first finishes */
		new Thread(op2, "op2").start();
		assertTrue(op2.hasStarted());
		assertIsNotRunning(op2);

		/* free operations */
		op1.proceed();
		op2.returnWhenInSyncPoint();
		assertTrue(op2.isRunning());
		op2.proceed();
		assertTrue(op1.getStatus().isOK());
		assertTrue(op2.getStatus().isOK());

		/* remove trash */
		removeFromWorkspace(getWorkspace().getRoot());
	}

}
