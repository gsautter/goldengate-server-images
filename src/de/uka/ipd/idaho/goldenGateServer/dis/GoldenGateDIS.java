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

package de.uka.ipd.idaho.goldenGateServer.dis;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import de.uka.ipd.idaho.gamta.util.imaging.PageImage;
import de.uka.ipd.idaho.gamta.util.imaging.PageImageInputStream;
import de.uka.ipd.idaho.gamta.util.imaging.PageImageOutputStream;
import de.uka.ipd.idaho.gamta.util.imaging.PageImageStore;
import de.uka.ipd.idaho.goldenGateServer.AbstractGoldenGateServerComponent;
import de.uka.ipd.idaho.goldenGateServer.GoldenGateServerComponentRegistry;
import de.uka.ipd.idaho.goldenGateServer.uaa.UserAccessAuthority;
import de.uka.ipd.idaho.goldenGateServer.util.Base64InputStream;
import de.uka.ipd.idaho.goldenGateServer.util.Base64OutputStream;

/**
 * The GoldenGATE Document Image Server implements a page image store inside
 * GoldenGATE Server. Page images are accessible both from inside the server
 * and over the network by means of respective client objects. Remote image
 * uploads require authentication.
 * 
 * @author sautter
 */
public class GoldenGateDIS extends AbstractGoldenGateServerComponent implements GoldenGateDisConstants, PageImageStore {
	
	private UserAccessAuthority uaa;
	
	/** Constructor passing 'DIS' as the letter code to super constructor
	 */
	public GoldenGateDIS() {
		super("DIS");
	}
	
	/*
	 * (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.AbstractGoldenGateServerComponent#link()
	 */
	public void link() {

		// get access authority
		this.uaa = ((UserAccessAuthority) GoldenGateServerComponentRegistry.getServerComponent(UserAccessAuthority.class.getName()));

		// check success
		if (this.uaa == null) throw new RuntimeException(UserAccessAuthority.class.getName());
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.AbstractGoldenGateServerComponent#linkInit()
	 */
	public void linkInit() {
		PageImage.addPageImageSource(this);
	}
	
	/*
	 * @see de.uka.ipd.idaho.goldenGateServer.GoldenGateServerComponent#getActions()
	 */
	public ComponentAction[] getActions() {
		ArrayList cal = new ArrayList();
		ComponentAction ca;
		
		//	page image availability check
		ca = new ComponentActionNetwork() {
			public String getActionCommand() {
				return CHECK_IMAGE_AVAILABLE;
			}
			public void performActionNetwork(BufferedReader input, BufferedWriter output) throws IOException {
				String imageId = input.readLine();
				String docId = imageId.substring(0, imageId.lastIndexOf('.'));
				int pageId = Integer.parseInt(imageId.substring(imageId.lastIndexOf('.') + 1));
				output.write(isPageImageAvailable(docId, pageId) ? CHECK_IMAGE_AVAILABLE : "SORRY");
				output.flush();
			}
		};
		cal.add(ca);
		
		//	request for document page image
		ca = new ComponentActionNetwork() {
			public String getActionCommand() {
				return GET_IMAGE;
			}
			public void performActionNetwork(BufferedReader input, BufferedWriter output) throws IOException {
				String imageId = input.readLine();
				String docId = imageId.substring(0, imageId.lastIndexOf('.'));
				int pageId = Integer.parseInt(imageId.substring(imageId.lastIndexOf('.') + 1));
				
				//	get input stream
				InputStream imageIn;
				try {
					imageIn = new FileInputStream(getDocumentPageImageFile(PageImage.getPageImageName(docId, pageId)));
				}
				catch (FileNotFoundException fnfe) {
					output.write("Could not find or load image of page " + pageId + " in document " + docId);
					output.newLine();
					return;
				}
				PageImageInputStream piis = new PageImageInputStream(imageIn, GoldenGateDIS.this);
				
				//	indicate image coming
				output.write(GET_IMAGE);
				output.newLine();
				
				//	write image
				PageImageOutputStream pios = new PageImageOutputStream(new Base64OutputStream(output), piis);
				byte[] buffer = new byte[1024];
				for (int read; (read = piis.read(buffer, 0, buffer.length)) != -1;)
					pios.write(buffer, 0, read);
				pios.flush();
				pios.close();
				
				//	clean up
				imageIn.close();
			}
		};
		cal.add(ca);
		
		//	image upload
		ca = new ComponentActionNetwork() {
			public String getActionCommand() {
				return STORE_IMAGE;
			}
			public void performActionNetwork(BufferedReader input, BufferedWriter output) throws IOException {
				
				// check authentication
				String sessionId = input.readLine();
				if (!uaa.isValidSession(sessionId)) {
					output.write("Invalid session (" + sessionId + ")");
					output.newLine();
					return;
				}
				
				//	get image meta data
				String imageId = input.readLine();
				String docId = imageId.substring(0, imageId.lastIndexOf('.'));
				int pageId = Integer.parseInt(imageId.substring(imageId.lastIndexOf('.') + 1));
				
				//	get image
				PageImageInputStream piis = new PageImageInputStream(new Base64InputStream(input), GoldenGateDIS.this);
				PageImage pi = new PageImage(piis);
				
				//	store image
				storePageImage(docId, pageId, pi);
				
				//	indicate sucsess
				output.write(STORE_IMAGE);
				output.flush();
			}
		};
		cal.add(ca);
		
		return ((ComponentAction[]) cal.toArray(new ComponentAction[cal.size()]));
	}
	
	private File getDocumentBaseFolder(String docId) {
		File primaryFolder = new File(this.dataPath, docId.substring(0, 2));
		if (!primaryFolder.exists()) primaryFolder.mkdir();
		
		File secondaryFolder = new File(primaryFolder, docId.substring(2, 4));
		if (!secondaryFolder.exists()) secondaryFolder.mkdir();
		
		return secondaryFolder;
	}
	
	private File getDocumentPageImageFolder(String docId) {
		File pageImageFolder = new File(this.getDocumentBaseFolder(docId), docId);
		if (!pageImageFolder.exists()) pageImageFolder.mkdir();
		return pageImageFolder;
	}
	
	private File getDocumentPageImageFile(String name) {
		return new File(this.getDocumentPageImageFolder(name), (name + "." + IMAGE_FORMAT));
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#isPageImageAvailable(java.lang.String)
	 */
	public boolean isPageImageAvailable(String name) {
		File pif = this.getDocumentPageImageFile(name);
		return pif.exists();
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#isPageImageAvailable(java.lang.String, int)
	 */
	public boolean isPageImageAvailable(String docId, int pageId) {
		return this.isPageImageAvailable(PageImage.getPageImageName(docId, pageId));
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#getPageImage(java.lang.String)
	 */
	public PageImage getPageImage(String name) throws IOException {
		PageImageInputStream piis = this.getPageImageAsStream(name);
		if (piis == null)
			return null;
		try {
			PageImage pi = new PageImage(PageImage.readImage(piis), piis.originalWidth, piis.originalHeight, piis.originalDpi, piis.currentDpi, piis.leftEdge, piis.rightEdge, piis.topEdge, piis.bottomEdge, piis.source);
			piis.close();
			return pi;
		}
		catch (IOException ioe) {
			return null;
		}
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#getPageImageAsStream(java.lang.String)
	 */
	public PageImageInputStream getPageImageAsStream(String name) throws IOException {
		try {
			InputStream imageIn = new FileInputStream(getDocumentPageImageFile(name));
			return new PageImageInputStream(imageIn, GoldenGateDIS.this);
		}
		catch (IOException ioe) {
			return null;
		}
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#getPageImage(java.lang.String, int)
	 */
	public PageImage getPageImage(String docId, int pageId) throws IOException {
		return this.getPageImage(PageImage.getPageImageName(docId, pageId));
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#getPageImageAsStream(java.lang.String, int)
	 */
	public PageImageInputStream getPageImageAsStream(String docId, int pageId) throws IOException {
		return this.getPageImageAsStream(PageImage.getPageImageName(docId, pageId));
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageStore#storePageImage(java.lang.String, java.awt.image.BufferedImage, int)
	 */
	public void storePageImage(String name, BufferedImage image, int dpi) throws IOException {
		this.storePageImage(name, new PageImage(image, dpi, this));
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageStore#storePageImage(java.lang.String, de.uka.ipd.idaho.gamta.util.imaging.PageImage)
	 */
	public void storePageImage(String name, PageImage pageImage) throws IOException {
		FileOutputStream fos = new FileOutputStream(getDocumentPageImageFile(name));
		pageImage.write(fos);
		fos.flush();
		fos.close();
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageStore#storePageImage(java.lang.String, int, java.awt.image.BufferedImage, int)
	 */
	public String storePageImage(String docId, int pageId, BufferedImage image, int dpi) throws IOException {
		String name = PageImage.getPageImageName(docId, pageId);
		this.storePageImage(name, image, dpi);
		return name;
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageStore#storePageImage(java.lang.String, int, de.uka.ipd.idaho.gamta.util.imaging.PageImage)
	 */
	public String storePageImage(String docId, int pageId, PageImage pageImage) throws IOException {
		String name = PageImage.getPageImageName(docId, pageId);
		this.storePageImage(name, pageImage);
		return name;
	}
}