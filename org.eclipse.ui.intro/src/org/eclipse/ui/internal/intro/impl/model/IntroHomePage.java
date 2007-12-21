/*******************************************************************************
 * Copyright (c) 2004, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.ui.internal.intro.impl.model;

import java.util.Vector;

import org.osgi.framework.Bundle;
import org.w3c.dom.Element;

/**
 * An Intro Home page. A home page is special because it is the page that
 * decides whether the OOBE pages are dynamic or static. This model class models
 * the home and the standby page (since there is no difference between the two).
 */
public class IntroHomePage extends AbstractIntroPage {

	IntroHomePage(Element element, Bundle bundle, String base) {
        super(element, bundle, base);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.internal.intro.impl.model.IntroElement#getType()
     */
    public int getType() {
        return AbstractIntroElement.HOME_PAGE;
    }

    // THESE METHODS WILL BE REMOVED!
    /**
     * This method is a customized method for root page to return the root page
     * links. Try to get the real links in the page, and all links in all divs.
     */
    public IntroLink[] getLinks() {
        Vector linkVector = new Vector();

        AbstractIntroElement[] children = getChildren();
        for (int i = 0; i < children.length; i++) {
            AbstractIntroElement child = children[i];
            if (child.isOfType(AbstractIntroElement.LINK))
                linkVector.add(child);
            else if (child.isOfType(AbstractIntroElement.GROUP)) {
                addLinks((IntroGroup) child, linkVector);
            }
        }

        IntroLink[] links = new IntroLink[linkVector.size()];
        linkVector.copyInto(links);
        return links;
    }

    private void addLinks(IntroGroup group, Vector linkVector) {
        AbstractIntroElement[] children = group.getChildren();
        for (int i = 0; i < children.length; i++) {
            AbstractIntroElement child = children[i];
            if (child.isOfType(AbstractIntroElement.LINK))
                linkVector.add(child);
            else if (child.isOfType(AbstractIntroElement.GROUP)) {
                addLinks((IntroGroup) child, linkVector);
            }
        }
    }


}
