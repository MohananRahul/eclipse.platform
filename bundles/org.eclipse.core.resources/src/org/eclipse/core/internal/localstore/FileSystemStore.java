package org.eclipse.core.internal.localstore;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.internal.resources.ResourceException;
import org.eclipse.core.internal.resources.ResourceStatus;
import org.eclipse.core.internal.resources.Workspace;
import org.eclipse.core.internal.utils.*;
import java.io.*;

public class FileSystemStore implements ILocalStoreConstants {
public FileSystemStore() {
}
public void copy(File source, File destination, int depth, IProgressMonitor monitor) throws CoreException {
	monitor = Policy.monitorFor(monitor);
	try {
		monitor.beginTask(Policy.bind("localstore.copying", source.getAbsolutePath()), 1);
		Policy.checkCanceled(monitor);
		if (source.isDirectory())
			copyDirectory(source, destination, depth, Policy.subMonitorFor(monitor, 1));
		else
			copyFile(source, destination, Policy.subMonitorFor(monitor, 1));
	} finally {
		monitor.done();
	}
}
protected void copyDirectory(File source, File destination, int depth, IProgressMonitor monitor) throws CoreException {
	monitor = Policy.monitorFor(monitor);
	try {
		String[] children = source.list();
		if(children == null){
			children = new String[0];
		}

		monitor.beginTask(Policy.bind("localstore.copying", source.getAbsolutePath()), children.length);
		// create directory
		writeFolder(destination);

		// depth
		if (depth == IResource.DEPTH_ZERO)
			return;
		if (depth == IResource.DEPTH_ONE)
			depth = IResource.DEPTH_ZERO;

		// copy children
		for (int i = 0; i < children.length; i++)
			copy(new File(source, children[i]), new File(destination, children[i]), depth, Policy.subMonitorFor(monitor, 1));
	} finally {
		monitor.done();
	}
}
protected void copyFile(File target, File destination, IProgressMonitor monitor) throws CoreException {
	monitor = Policy.monitorFor(monitor);
	try {
		int totalWork = 1 + ((int) target.length() / 8192);
		monitor.beginTask(Policy.bind("localstore.copying", target.getAbsolutePath()), totalWork);
		write(destination, read(target), false, monitor);
	} finally {
		monitor.done();
	}
}
public void delete(File target) throws CoreException {
	if (!Workspace.clear(target)) {
		String message = Policy.bind("localstore.couldnotDelete", target.getAbsolutePath());
		throw new ResourceException(IResourceStatus.FAILED_DELETE_LOCAL, new Path(target.getAbsolutePath()), message, null);
	}
}
public boolean delete(File root, MultiStatus status) {
	boolean failedRecursive = false;
	if (root.isDirectory()) {
		String[] list = root.list();
		if (list != null)
			for (int i = 0; i < list.length; i++)
				// try best effort on all children so put logical OR at end
				failedRecursive = !delete(new java.io.File(root, list[i]), status) || failedRecursive;
	}
	boolean failedThis = false;
	try {
		// don't try to delete the root if one of the children failed
		if (!failedRecursive && root.exists())
			failedThis = !root.delete();
	} catch (Exception e) {
		// we caught a runtime exception so log it
		String message = Policy.bind("localstore.couldnotDelete", root.getAbsolutePath());
		status.add(new ResourceStatus(IResourceStatus.FAILED_DELETE_LOCAL, new Path(root.getAbsolutePath()), message, e));
		return false;
	}
	if (failedThis) {
		String message = null;
		if (CoreFileSystemLibrary.isReadOnly(root.getAbsolutePath()))
			message = Policy.bind("localstore.couldnotDeleteReadOnly", root.getAbsolutePath());
		else
			message = Policy.bind("localstore.couldnotDelete", root.getAbsolutePath());
		status.add(new ResourceStatus(IResourceStatus.FAILED_DELETE_LOCAL, new Path(root.getAbsolutePath()), message, null));
	}
	return !(failedRecursive || failedThis);
}
public void move(File source, File destination, boolean force, IProgressMonitor monitor) throws CoreException {
	monitor = Policy.monitorFor(monitor);
	try {
		monitor.beginTask(Policy.bind("localstore.moving", source.getAbsolutePath()), 2);
		// are we renaming the case only?
		boolean caseRenaming = false;
		if (!CoreFileSystemLibrary.isCaseSensitive())
			caseRenaming = source.getAbsolutePath().equalsIgnoreCase(destination.getAbsolutePath());
		if (!caseRenaming && destination.exists()) {
			if (!force) {
				String message = Policy.bind("localstore.resourceExists", destination.getAbsolutePath());
				throw new ResourceException(IResourceStatus.EXISTS_LOCAL, new Path(destination.getAbsolutePath()), message, null);
			} else
				try {
					delete(destination);
				} catch (CoreException e) {
					String message = Policy.bind("localstore.couldnotDelete", destination.getAbsolutePath());
					throw new ResourceException(IResourceStatus.FAILED_DELETE_LOCAL, new Path(destination.getAbsolutePath()), message, e);
				}
		}
		if (source.renameTo(destination)) {
			// double-check to ensure we really did move
			// since java.io.File#renameTo sometimes lies
			if (!caseRenaming && source.exists()) {
				if (destination.exists()) {
					// couldn't delete the source so remove the destination
					// and throw an error
					Workspace.clear(destination);
					String message = Policy.bind("localstore.couldnotDelete", source.getAbsolutePath());
					throw new ResourceException(new ResourceStatus(IResourceStatus.FAILED_DELETE_LOCAL, new Path(source.getAbsolutePath()), message, null));
				} else {
					// source exists but destination doesn't so try to copy below
				}
			} else {
				if (destination.exists()) {
					// success case
					return;
				} else {
					// neither the source nor the destination exist. this is REALLY bad
					String message = Policy.bind("localstore.failedMove", source.getAbsolutePath(), destination.getAbsolutePath());
					throw new ResourceException(new ResourceStatus(IResourceStatus.FAILED_WRITE_LOCAL, new Path(source.getAbsolutePath()), message, null));
				}
			}
		} 
		boolean success = false;
		try {
			copy(source, destination, IResource.DEPTH_INFINITE, Policy.subMonitorFor(monitor, 1));
			success = true;
		} finally {
			if (success)
				Workspace.clear(source);
			else {
				Workspace.clear(destination);
				String message = Policy.bind("localstore.couldnotMove", source.getAbsolutePath());
				throw new ResourceException(new ResourceStatus(IResourceStatus.FAILED_WRITE_LOCAL, new Path(source.getAbsolutePath()), message, null));
			}
		}
		monitor.worked(1);
	} finally {
		monitor.done();
	}
}
/**
 * Returns an input stream containing the contents of the given
 * file as maintained by this store.  The user of the returned
 * stream is responsible for closing the stream when finished.
 *
 * @exception CoreException if the content of
 *		the resource cannot be accessed.
 */
public InputStream read(File target) throws CoreException {
	try {
		return new FileInputStream(target);
	} catch (FileNotFoundException e) {
		String message;
		if (!target.exists())
			message = Policy.bind("localstore.fileNotFound", target.getAbsolutePath());
		else
			if (target.isDirectory())
				message = Policy.bind("localstore.notAFile", target.getAbsolutePath());
			else
				message = Policy.bind("localstore.couldNotRead", target.getAbsolutePath());
		throw new ResourceException(IResourceStatus.FAILED_READ_LOCAL, new Path(target.getAbsolutePath()), message, e);
	}
}
/**
 * This method also closes both streams.
 */
public static void transferStreams(InputStream source, OutputStream destination, IProgressMonitor monitor) throws IOException {
	monitor = Policy.monitorFor(monitor);
	try {
		byte[] buffer = new byte[8192];
		while (true) {
			int bytesRead = source.read(buffer);
			if (bytesRead == -1)
				break;
			destination.write(buffer, 0, bytesRead);
			monitor.worked(1);
		}
	} finally {
		try {
			source.close();
		} catch (IOException e) {
		}
		try {
			destination.close();
		} catch (IOException e) {
		}
	}
}
/**
 * Content cannot be null and it is closed even if the operation is not
 * completed successfully.
 */
public void write(File target, InputStream content, boolean append, IProgressMonitor monitor) throws CoreException {
	try {
		try {
			writeFolder(new File(target.getParent()));
			FileOutputStream output = new FileOutputStream(target.getAbsolutePath(), append);
			transferStreams(content, output, monitor);
		} finally {
			content.close();
		}
	} catch (IOException e) {
		String message = null;
		if (CoreFileSystemLibrary.isReadOnly(target.getAbsolutePath()))
			message = Policy.bind("localstore.couldNotWriteReadOnly", target.getAbsolutePath());
		else
			message = Policy.bind("localstore.couldNotWrite", target.getAbsolutePath());
		throw new ResourceException(IResourceStatus.FAILED_WRITE_LOCAL, new Path(target.getAbsolutePath()), message, e);
	}
}
public void writeFolder(File target) throws CoreException {
	if (!target.exists())
		target.mkdirs();
	if (!target.isDirectory()) {
		String message = Policy.bind("localstore.couldNotCreateFolder", target.getAbsolutePath());
		throw new ResourceException(IResourceStatus.FAILED_WRITE_LOCAL, new Path(target.getAbsolutePath()), message, null);
	}
}
}