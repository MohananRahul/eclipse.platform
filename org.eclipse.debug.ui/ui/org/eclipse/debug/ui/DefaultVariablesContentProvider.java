/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.debug.ui;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;

public class DefaultVariablesContentProvider implements IVariablesContentProvider {

	/* (non-Javadoc)
	 * @see org.eclipse.debug.ui.IVariablesContentProvider#getVariableChildren(org.eclipse.debug.ui.IDebugView, org.eclipse.debug.core.model.IVariable)
	 */
	public IVariable[] getVariableChildren(IDebugView view, IValue value) throws DebugException {
		return value.getVariables();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.ui.IVariablesContentProvider#hasVariableChildren(org.eclipse.debug.ui.IDebugView, org.eclipse.debug.core.model.IVariable)
	 */
	public boolean hasVariableChildren(IDebugView view, IValue value) throws DebugException {
		return value != null && value.hasVariables();
	}

}
