package org.eclipse.help.internal.ui;
/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */


import java.util.*;
import org.eclipse.swt.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.events.*;
import org.eclipse.swt.custom.*;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.help.WorkbenchHelp;
import org.eclipse.help.internal.contributors.ContextManager;
import org.eclipse.help.*;
import org.eclipse.help.internal.HelpSystem;
import org.eclipse.help.internal.ui.util.*;
import org.eclipse.help.internal.util.*;

/**
 * ContextHelpDialog
 */
public class ContextHelpDialog implements Runnable {

	private Cursor defaultCursor = null;
	private Cursor waitCursor = null;
	private ContextManager cmgr = HelpSystem.getContextManager();
	private Object contexts[];
	private IHelpTopic farRelatedTopics[] = new IHelpTopic[0];
	private Thread getMoreRelatedTopicsThread = null;
	private Map menuItems;
	private IHelpTopic relatedTopics[] = null;

	// TO DO:
	// Register these resources with the HelpSystem and have them disposed
	private Color backgroundColour = null;
	private Color foregroundColour = null;
	private Color linkColour = null;
	private static HyperlinkHandler linkManager = new HyperlinkHandler();

	private final static String IMAGE_MORE = "moreImage";
	private static ImageRegistry imgRegistry = null;

	private Shell shell;

	private int x;
	private int y;

	class LinkListener extends HyperlinkAdapter {
		IHelpTopic topic;

		public LinkListener(IHelpTopic topic) {
			this.topic = topic;
		}
		public void linkActivated(Control c) {
			launchFullViewHelp(topic);
		}
	}

	class MenuItemsListener implements SelectionListener {
		public void widgetSelected(SelectionEvent e) {
			IHelpTopic t = (IHelpTopic) menuItems.get(e.widget);
			shell.close();
			launchFullViewHelp(t);
		}
		public void widgetDefaultSelected(SelectionEvent e) {
			widgetSelected(e);
		}
	}

	class ShowMoreListener implements Listener {
		public void handleEvent(Event e) {
			if (e.type == SWT.MouseDown) {
				if (getMoreRelatedTopicsThread != null &&
				    getMoreRelatedTopicsThread.isAlive()) 
				{
					Display d = shell.getDisplay();
					if (waitCursor == null)
						waitCursor = new Cursor(d, SWT.CURSOR_WAIT);
					((Label) e.widget).setCursor(waitCursor);
					try {
						getMoreRelatedTopicsThread.join();
					} catch (InterruptedException ie) {
						return;
					}
					if (defaultCursor == null)
						defaultCursor = new Cursor(d, SWT.CURSOR_ARROW);
					((Label) e.widget).setCursor(defaultCursor);
				}

				showMoreLinks();
			}
		}
	}

	/**
	 * Constructor:
	 * @param context an array of String or an array of IContext
	 * @param x the x mouse location in the current display
	 * @param y the y mouse location in the current display
	 */
	ContextHelpDialog(Object[] contexts, int x, int y) {
		this.contexts = contexts;
		this.x = x;
		this.y = y;

		Display display = Display.getCurrent();
		backgroundColour = display.getSystemColor(SWT.COLOR_INFO_BACKGROUND);
		foregroundColour = display.getSystemColor(SWT.COLOR_INFO_FOREGROUND);
		linkColour = display.getSystemColor(SWT.COLOR_BLUE);

		if (imgRegistry == null) {
			imgRegistry = WorkbenchHelpPlugin.getDefault().getImageRegistry();
			imgRegistry.put(
				IMAGE_MORE,
				ImageDescriptor.createFromURL(WorkbenchResources.getImagePath("moreImage")));
		}

		shell = new Shell(display.getActiveShell(), SWT.NONE);

		if (Logger.DEBUG)
			Logger.logDebugMessage(
				"ContextHelpDialog",
				" Constructor: Shell is:" + shell.toString());

		WorkbenchHelp.setHelp(shell, new String[] { IHelpUIConstants.F1_SHELL });

		shell.addDisposeListener(new DisposeListener() {
			public void widgetDisposed(DisposeEvent e) {
				if (Logger.DEBUG)
					Logger.logDebugMessage("ContextHelpDialog", "widgetDisposed: called. ");
				if (waitCursor != null)
					waitCursor.dispose();
				if (defaultCursor != null)
					defaultCursor.dispose();
			}
		});

		// This is commented out for now because it does not work on Linux
		// Using addListener for now.
		/*shell.addShellListener(new ShellAdapter() {
			public void shellDeactivated(ShellEvent e) {
				e.widget.getDisplay().asyncExec(new Runnable() {
					public void run() {
						close();
					}
				});
			}
		});*/

		shell.addListener(SWT.Deactivate, new Listener() {
			public void handleEvent(Event e) {
				if (Logger.DEBUG)
					Logger.logDebugMessage(
						"ContextHelpDialog",
						"handleEvent: SWT.Deactivate called. ");
				close();
			};
		});

		shell.addControlListener(new ControlAdapter() {
			public void controlMoved(ControlEvent e) {
				if (Logger.DEBUG)
					Logger.logDebugMessage("ContextHelpDialog", "controlMoved: called. ");
				Rectangle clientArea = shell.getClientArea();
				shell.redraw(
					clientArea.x,
					clientArea.y,
					clientArea.width,
					clientArea.height,
					true);
				shell.update();
			}
		});

		if (Logger.DEBUG)
			Logger.logDebugMessage(
				"ContextHelpDialog",
				"Constructor: Focus owner is: "
					+ Display.getCurrent().getFocusControl().toString());

		linkManager.setHyperlinkUnderlineMode(HyperlinkHandler.UNDERLINE_ROLLOVER);

		createContents(shell);

		shell.pack();

		// Correct x and y of the shell if it not contained within the screen
		int width = shell.getBounds().width;
		int height = shell.getBounds().height;
		// check lower boundaries
		x = x >= 0 ? x : 0;
		y = y >= 0 ? y : 0;
		// check upper boundaries
		int margin = 0;
		if (System.getProperty("os.name").startsWith("Win"))
			margin = 28; // for the Windows task bar in the ussual place;
		Rectangle screen = Display.getCurrent().getBounds();
		x = x + width <= screen.width ? x : screen.width - width;
		y = y + height <= screen.height - margin ? y : screen.height - margin - height;

		shell.setLocation(x, y);

	}
	public synchronized void close() {
		try {
			if (Logger.DEBUG)
				Logger.logDebugMessage("ContextHelpDialog", "close: called. ");
			if (shell != null) {
				shell.close();
				if (!shell.isDisposed())
					shell.dispose();
				shell = null;
			}

			if (getMoreRelatedTopicsThread != null
				&& getMoreRelatedTopicsThread.isAlive()) {
				try {
					getMoreRelatedTopicsThread.join();
				} catch (InterruptedException ie) {
				}
			}
		} catch (Throwable ex) {
		}
	}
	/**
	 */
	protected Control createContents(Composite contents) {

		contents.setBackground(backgroundColour);

		GridLayout layout = new GridLayout();
		layout.marginHeight = 5;
		layout.marginWidth = 5;
		contents.setLayout(layout);
		contents.setLayoutData(new GridData(GridData.FILL_BOTH));

		// create the dialog area and button bar
		createInfoArea(contents);
		createLinksArea(contents);
		if (contexts != null && contexts.length > 1)
			createMoreButton(contents);

		// if any errors or parsing errors have occurred, display them in a pop-up
		Util.displayStatus();

		return contents;
	}
	protected Control createInfoArea(Composite parent) {
		Composite composite = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout();
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		composite.setLayout(layout);
		composite.setBackground(backgroundColour);

		GridData data =
			new GridData(
				GridData.FILL_BOTH
					| GridData.HORIZONTAL_ALIGN_CENTER
					| GridData.VERTICAL_ALIGN_CENTER);
		composite.setLayoutData(data);
		
		// Create the text field.    
		String styledText = cmgr.getDescription(contexts);
		if (styledText == null) 
			// no description found in context objects.
			styledText = WorkbenchResources.getString("WW002");
		StyledText text = new StyledText(composite, SWT.MULTI|SWT.READ_ONLY|SWT.WRAP);
		text.setCaret(null);
		text.setBackground(backgroundColour);
		text.setForeground(foregroundColour);
		StyledLineWrapper content = new StyledLineWrapper(styledText);
		text.setContent(content);
		text.setStyleRanges(content.getStyles());

		data = new GridData();
		data.horizontalAlignment = data.FILL;
		data.grabExcessHorizontalSpace = true;
		data.verticalAlignment = data.FILL;
		data.grabExcessVerticalSpace = true;
		text.setLayoutData(data);
			
		text.pack();
		
		return composite;
	}
	
	protected Control createLink(Composite parent, IHelpTopic topic) {
		Label link = new Label(parent, SWT.NONE);
		link.setText(topic.getLabel());
		link.setBackground(backgroundColour);
		link.setForeground(linkColour);

		GridData data = new GridData();
		data.horizontalAlignment = data.HORIZONTAL_ALIGN_BEGINNING;
		data.verticalAlignment = data.VERTICAL_ALIGN_BEGINNING;
		data.horizontalIndent = 4;
		link.setLayoutData(data);

		linkManager.registerHyperlink(link, new LinkListener(topic));
		return link;
	}
	protected Control createLinksArea(Composite parent) {
		// get links from first context with links
		relatedTopics = cmgr.getRelatedTopics(contexts);

		if (relatedTopics == null)
			// none of the contexts have Topics
			return null;

		// Create control
		Composite composite = new Composite(parent, SWT.NONE);
		composite.setBackground(backgroundColour);

		GridLayout layout = new GridLayout();
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		layout.verticalSpacing = 3;
		composite.setLayout(layout);

		GridData data =
			new GridData(
				GridData.FILL_BOTH
					| GridData.HORIZONTAL_ALIGN_BEGINNING
					| GridData.VERTICAL_ALIGN_CENTER);
		composite.setLayoutData(data);

		// Create separator.    
		Label label = new Label(composite, SWT.SEPARATOR | SWT.HORIZONTAL);
		label.setBackground(backgroundColour);
		label.setForeground(foregroundColour);

		data =
			new GridData(
				GridData.HORIZONTAL_ALIGN_BEGINNING
					| GridData.VERTICAL_ALIGN_BEGINNING
					| GridData.FILL_HORIZONTAL);

		label.setLayoutData(data);

		// Create related links
		for (int i = 0; i < relatedTopics.length; i++) {
			createLink(composite, relatedTopics[i]);
		}

		return composite;
	}

	protected void createMoreButton(Composite parent) {
		// Create Show More button
		CLabel showMoreButton = new CLabel(parent, SWT.NONE);
		showMoreButton.setBackground(backgroundColour);
		showMoreButton.setImage(imgRegistry.get(IMAGE_MORE));

		Listener l = new ShowMoreListener();
		showMoreButton.addListener(SWT.MouseDown, l);

		// Before returning start thread obtaining more related links in the bacground
		getMoreRelatedTopicsInBackground();
	}
	protected void getMoreRelatedTopicsInBackground() {
		getMoreRelatedTopicsThread = new Thread(this);
		getMoreRelatedTopicsThread.setDaemon(true);
		getMoreRelatedTopicsThread.setName("MoreRelatedTopics");
		getMoreRelatedTopicsThread.setPriority(
			Thread.currentThread().getPriority() - 1);
		getMoreRelatedTopicsThread.start();
	}
	/**
	 */
	protected void launchFullViewHelp(IHelpTopic selectedTopic) {
		// wait for more related links
		if (getMoreRelatedTopicsThread != null &&
		    getMoreRelatedTopicsThread.isAlive()) 
		{
			Display d = Display.getCurrent();
			if (waitCursor == null)
				waitCursor = new Cursor(d, SWT.CURSOR_WAIT);
			shell.setCursor(waitCursor);
			try {
				getMoreRelatedTopicsThread.join();
			} catch (InterruptedException ie) {
				return;
			}
		}

		// group related topics, and far related topics together
		IHelpTopic allTopics[] =
			new IHelpTopic[relatedTopics.length + farRelatedTopics.length];
		System.arraycopy(relatedTopics, 0, allTopics, 0, relatedTopics.length);
		System.arraycopy(
			farRelatedTopics,
			0,
			allTopics,
			relatedTopics.length,
			farRelatedTopics.length);

		// launch help view
		DefaultHelp.getInstance().displayHelp(allTopics, selectedTopic);

		// now close the infopop
		close();
		if (Logger.DEBUG)
			Logger.logDebugMessage("ContextHelpDialog", "launchFullViewHelp: closes shell");
	}
	public synchronized void open() {
		try {
			shell.open();
			if (Logger.DEBUG)
				Logger.logDebugMessage(
					"ContextHelpDialog",
					"open: Focus owner after open is: "
						+ Display.getCurrent().getFocusControl().toString());

		} catch (Throwable e) {
		}
	}
	/**
	 * Obtains more related Links
	 */
	public void run() {
		farRelatedTopics = cmgr.getMoreRelatedTopics(contexts);
	}
	protected void showMoreLinks() {
		Menu menu = new Menu(shell);
		if (farRelatedTopics == null || farRelatedTopics.length < 1) {
			// show "no more links" menu item only
			MenuItem item = new MenuItem(menu, SWT.CASCADE);
			item.setText(WorkbenchResources.getString("No_more_links_exist"));
		} else {
			// create and show menu items with related links
			menuItems = new HashMap();
			SelectionListener l = new MenuItemsListener();
			for (int i = 0; i < farRelatedTopics.length; i++) {
				MenuItem item = new MenuItem(menu, SWT.CASCADE);
				item.setText(((IHelpTopic) farRelatedTopics[i]).getLabel());
				menuItems.put(item, (IHelpTopic) farRelatedTopics[i]);
				item.addSelectionListener(l);
			}
		}
		menu.setVisible(true);
	}
}
