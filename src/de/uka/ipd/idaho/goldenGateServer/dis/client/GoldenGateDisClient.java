/*
 * Copyright (c) 2006-2008, IPD Boehm, Universitaet Karlsruhe (TH)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the Universität Karlsruhe (TH) nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY UNIVERSITÄT KARLSRUHE (TH) AND CONTRIBUTORS 
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package de.uka.ipd.idaho.goldenGateServer.dis.client;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

import de.uka.ipd.idaho.gamta.util.imaging.PageImageInputStream;
import de.uka.ipd.idaho.gamta.util.imaging.PageImageSource;
import de.uka.ipd.idaho.gamta.util.imaging.PageImageSource.AbstractPageImageSource;
import de.uka.ipd.idaho.goldenGateServer.client.ServerConnection;
import de.uka.ipd.idaho.goldenGateServer.client.ServerConnection.Connection;
import de.uka.ipd.idaho.goldenGateServer.dis.GoldenGateDisConstants;
import de.uka.ipd.idaho.goldenGateServer.util.Base64InputStream;

/**
 * Basic client for the GoldenGATE Document Image Server, providing only reading
 * operations.
 * 
 * @author sautter
 */
public class GoldenGateDisClient extends AbstractPageImageSource implements GoldenGateDisConstants, PageImageSource {
	
	private ServerConnection serverConnection = null;
	
	private File cacheFolder = null;
	private boolean cacheSynchronized = false;
	
	/**
	 * Constructor
	 * @param serverConnection the ServerConnection to use for communication
	 *            with the backing GoldenGATE DIS
	 */
	public GoldenGateDisClient(ServerConnection serverConnection) {
		this.serverConnection = serverConnection;
	}
	
	GoldenGateDisClient() {
		this(null);
	}
	
	Connection getConnection() throws IOException {
		return this.serverConnection.getConnection();
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#isPageImageAvailable(java.lang.String)
	 */
	public boolean isPageImageAvailable(String name) {
		
		//	check cache
		if (this.cacheFolder != null) {
			File picf = this.getPageImageCacheFile(name);
			if (picf.exists())
				return true;
		}
		
		//	check backing server
		Connection con = null;
		try {
			con = this.getConnection();
			BufferedWriter bw = con.getWriter();
			
			bw.write(CHECK_IMAGE_AVAILABLE);
			bw.newLine();
			bw.write(name);
			bw.newLine();
			bw.flush();
			
			BufferedReader br = con.getReader();
			String error = br.readLine();
			return (CHECK_IMAGE_AVAILABLE.equals(error));
		}
		catch (IOException e) {
			return false;
		}
		finally {
			if (con != null) try {
				con.close();
			} catch (IOException e) {}
		}
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#getPageImageAsStream(java.lang.String)
	 */
	public PageImageInputStream getPageImageAsStream(String name) throws IOException {
		if (this.cacheFolder == null)
			return new PageImageInputStream(this.getPageImageInputStream(name), this);
		else if (this.cacheSynchronized)
			return this.getPageImageAsStreamSynchronized(name);
		else return this.getPageImageAsStreamUnSynchronized(name);
	}
	
	private PageImageInputStream getPageImageAsStreamSynchronized(final String name) throws IOException {
		
		//	check cache status
		final Object cacheMarker;
		synchronized (this.cacheMarkers) {
			cacheMarker = this.cacheMarkers.get(name);
			
			//	no other thread is caching the requested image ==> it's either completely on disc, or not at all
			if (cacheMarker == null) {
				
				//	try returning cached image
				try {
					return new PageImageInputStream(new FileInputStream(this.getPageImageCacheFile(name)), this);
				}
				
				//	cache miss ==> indicate image in process of being cache
				catch (FileNotFoundException fnfe) {
					this.cacheMarkers.put(name, CACHING);
				}
			}
		}
		
		//	another thread is in the process of caching the requested image
		if (cacheMarker == CACHING) {
			
			//	wait a little for other thread to finish caching
			try {
				Thread.sleep(100);
			} catch (InterruptedException ie) {}
			
			//	and recurse to repeat cache lookup
			return getPageImageAsStreamSynchronized(name);
		}
		
		//	fetch image from backing DIS
		InputStream imageIn = this.getPageImageInputStream(name);
		
		//	cache image
		FileOutputStream cacheOut = new FileOutputStream(this.getPageImageCacheFile(name));
		ByteArrayOutputStream memOut = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		int read;
		while ((read = imageIn.read(buffer, 0, buffer.length)) != -1) {
			cacheOut.write(buffer, 0, read);
			memOut.write(buffer, 0, read);
		}
		cacheOut.flush();
		cacheOut.close();
		memOut.flush();
		memOut.close();
		
		//	remove marker and return image
		synchronized (this.cacheMarkers) {
			this.cacheMarkers.remove(name);
			return new PageImageInputStream(new ByteArrayInputStream(memOut.toByteArray()), this);
		}
	}
	
	private HashMap cacheMarkers = new HashMap();
	private static final Object CACHING = new Object();
	
	private PageImageInputStream getPageImageAsStreamUnSynchronized(String name) throws IOException {
		InputStream imageIn = this.getCachedPageImageInputStream(name);
		if (imageIn == null) {
			imageIn = this.getPageImageInputStream(name);
			imageIn = this.cachePageImageInputStream(imageIn, name);
		}
		return new PageImageInputStream(imageIn, this);
	}
	
	private InputStream cachePageImageInputStream(InputStream imageIn, String name) {
		if (this.cacheFolder == null)
			return imageIn;
		try {
			final OutputStream imageOut = new BufferedOutputStream(new FileOutputStream(this.getPageImageCacheFile(name)));
			return new FilterInputStream(imageIn) {
				public void close() throws IOException {
					byte[] buffer = new byte[1024];
					int read;
					do {
						read = this.read(buffer, 0, buffer.length);
					} while (read != -1);
					imageOut.flush();
					imageOut.close();
					
					super.close();
				}
				public int read() throws IOException {
					int read = super.read();
					if (read != -1)
						imageOut.write(read);
					return read;
				}
				public int read(byte[] b, int off, int len) throws IOException {
					int read = super.read(b, off, len);
					if (read != -1)
						imageOut.write(b, off, read);
					return read;
				}
			};
		}
		catch (IOException ioe) {
			ioe.printStackTrace(System.out);
			return imageIn;
		}
	}
	
	private InputStream getCachedPageImageInputStream(String name) {
		if (this.cacheFolder == null)
			return null;
		try {
			return new FileInputStream(this.getPageImageCacheFile(name));
		}
		catch (FileNotFoundException fnfe) {
			return null;
		}
	}
	
	private InputStream getPageImageInputStream(String name) throws IOException {
		final Connection con = this.getConnection();
		BufferedWriter bw = con.getWriter();
		
		bw.write(GET_IMAGE);
		bw.newLine();
		bw.write(name);
		bw.newLine();
		bw.flush();
		
		BufferedReader br = con.getReader();
		String error = br.readLine();
		if (GET_IMAGE.equals(error))
			return new FilterInputStream(new Base64InputStream(br)) {
				private Connection connection = con;
				public void close() throws IOException {
					this.connection.close();
					this.connection = null;
				}
				protected void finalize() throws Throwable {
					if (this.connection != null)
						this.connection.close();
				}
			};
		
		else {
			con.close();
			throw new IOException(error);
		}
	}
	
	private File getPageImageCacheFile(String name) {
		File pageImageFolder = new File(this.cacheFolder, name);
		if (!pageImageFolder.exists())
			pageImageFolder.mkdir();
		return new File(pageImageFolder, (name + "." + IMAGE_FORMAT));
	}
	
	/**
	 * Test whether caching is enabled for this GoldenGATE DIS Client, i.e.,
	 * whether the cache folder is set to a valid directory.
	 * @return true is caching is anabled, false otherwise
	 */
	public boolean isCachingEnabled() {
		return (this.cacheFolder != null);
	}
	
	/**
	 * Make the GoldenGATE DIS Client know the folder to use for caching images.
	 * If the specified file is null or does not denote a directory, the cache
	 * folder is set to null, disabeling caching.
	 * @param cacheFolder the cache folder
	 */
	public void setCacheFolder(File cacheFolder) {
		
		//	if argument file is not a directory, set it to null
		if ((cacheFolder != null) && cacheFolder.exists() && !cacheFolder.isDirectory())
			cacheFolder = null;
		
		//	set cache folder
		this.cacheFolder = cacheFolder;
		
		//	make sure cache folder exists, disable cache if creation fails
		if (this.cacheFolder != null) {
			this.cacheFolder.mkdirs();
			if (!this.cacheFolder.exists() || !this.cacheFolder.isDirectory())
				this.cacheFolder = null;
		}
	}
	
	/**
	 * Set caching operations to be or not to be synchronized. Synchronizing
	 * cache operations prevents fetching images from the backing DIS multiple
	 * times if the same image is requested by multiple threads simultaneously.
	 * However, synchronizing threads always incurs overhead, so cache
	 * synchronization is not recommended if such parallel requests for the same
	 * image are rare. This setting has an effect only if caching is enabled in
	 * the first place, i.e., if a cache folder is set.
	 * @param cacheSynchronized sychronize caching?
	 */
	public void setCacheSynchronized(boolean cacheSynchronized) {
		this.cacheSynchronized = cacheSynchronized;
	}
}