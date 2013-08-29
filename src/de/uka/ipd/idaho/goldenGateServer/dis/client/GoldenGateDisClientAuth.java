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

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;

import de.uka.ipd.idaho.gamta.util.imaging.PageImage;
import de.uka.ipd.idaho.gamta.util.imaging.PageImageStore;
import de.uka.ipd.idaho.goldenGateServer.client.ServerConnection.Connection;
import de.uka.ipd.idaho.goldenGateServer.uaa.client.AuthenticatedClient;
import de.uka.ipd.idaho.goldenGateServer.util.Base64OutputStream;

/**
 * Authenticated client for the GoldenGATE Document Image Server, providing both
 * reading and upload operations.
 * 
 * @author sautter
 */
public class GoldenGateDisClientAuth extends GoldenGateDisClient implements PageImageStore {
	private AuthenticatedClient authClient;
	
	/**
	 * Constructor
	 * @param authClient the connection to the backing DIS
	 */
	public GoldenGateDisClientAuth(AuthenticatedClient authClient) {
		this.authClient = authClient;
	}
	
	Connection getConnection() throws IOException {
		return this.authClient.getConnection();
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageStore#storePageImage(java.lang.String, de.uka.ipd.idaho.gamta.util.imaging.PageImage)
	 */
	public void storePageImage(String name, PageImage pageImage) throws IOException {
		Connection con = null;
		try {
			con = this.getConnection();
			BufferedWriter bw = con.getWriter();
			
			//	indicate image upload coming
			bw.write(STORE_IMAGE);
			bw.newLine();
			
			//	send authentication
			bw.write(this.authClient.getSessionID());
			bw.newLine();
			
			//	send image name
			bw.write(name);
			bw.newLine();
			
			//	send image
			Base64OutputStream bos = new Base64OutputStream(bw);
			pageImage.write(bos);
			bos.close(false);
			bw.flush();
			
			BufferedReader br = con.getReader();
			String error = br.readLine();
			if (!STORE_IMAGE.equals(error))
				throw new IOException(error);
		}
		finally {
			if (con != null)
				con.close();
		}
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageStore#storePageImage(java.lang.String, java.awt.image.BufferedImage, int)
	 */
	public void storePageImage(String name, BufferedImage image, int dpi) throws IOException {
		this.storePageImage(name, new PageImage(image, dpi, this));
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageStore#storePageImage(java.lang.String, int, java.awt.image.BufferedImage, int)
	 */
	public String storePageImage(String docId, int pageId, BufferedImage image, int dpi) throws IOException {
		String name = PageImage.getPageImageName(docId, pageId);
		this.storePageImage(name, new PageImage(image, dpi, this));
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