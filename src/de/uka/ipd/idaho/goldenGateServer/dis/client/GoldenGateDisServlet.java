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
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeSet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import de.uka.ipd.idaho.gamta.util.imaging.BoundingBox;
import de.uka.ipd.idaho.gamta.util.imaging.PageImage;
import de.uka.ipd.idaho.gamta.util.imaging.PageImageInputStream;
import de.uka.ipd.idaho.gamta.util.imaging.PageImageSource;
import de.uka.ipd.idaho.goldenGateServer.client.GgServerClientServlet;
import de.uka.ipd.idaho.goldenGateServer.client.GgServerWebFrontendLogger;
import de.uka.ipd.idaho.goldenGateServer.dis.GoldenGateDisConstants;

/**
 * This servlet provides document page images hosted by a DIS in a backing
 * GoldenGATE Server, as well as derivative images like thumbnails and parts.
 * The exact handling of images is controlled by several parameters:
 * <ul>
 * <li><b>useDiscCache</b>: cache images on disc? Setting this parameter to true
 * is advantageous if if many different images are requested multiple times over
 * longer periods of time.</li>
 * <li><b>discCacheSynchronized</b>: synchronize disc cache access? Setting this
 * parameter to true is advantageous if images are often requested by multiple
 * threads at the same time. Namely, it prevents parallel fetching operations.</li>
 * <li><b>memoryCacheSize</b>: size of in-memory cache. If many different images
 * are requested multiple times over longer periods of time, a small cache is
 * sufficient (backed by a disc cache). However, if each individual image is
 * requested only for a short period of time, but then very often, a larger
 * in-memory cache is advantageous.</li>
 * <li><b>memoryCacheSynchronized</b>: synchronize in-memory cache access?
 * Setting this parameter to true is advantageous if images are often requested
 * by multiple threads at the same time. Namely, it prevents parallel fetching
 * operations.</li>
 * <li>imageDpi<b></b>: resolution for full image displaying. The default value
 * for this parameter is 96.</li>
 * <li><b>thumbnailDpi</b>: resolution for thumbnails. The default value for
 * this parameter is 24.</li>
 * <li><b>defaultImage</b>: path and file name of a default image to display if
 * a requested image is not found. The path is relative to the servlet's data
 * path.</li>
 * <li><b>defaultThumbnail</b>: path and file name of a default thumbnail to
 * display if a requested image is not found. The path is relative to the
 * servlet's data path.</li>
 * </ul>
 * Requests to this servlet specify in the path info which page image to display:
 * <code>http://&lt;server:port&gt;/GgServer/images/&lt;docId&gt;/&lt;pageId&gt;.png</code>.<br>
 * To retrieve an HTML page with thumbnails of one or more pages, specify an
 * enumeration of pages and/or page ranges, and omit the <code>.png</code> ending.<br>
 * In addition, requests take multiple optional parameters:
 * <ul>
 * <li><b>box</b>: the bounding box of a requested image part, or the
 * concatenation of multiple bounding boxes to obtain parts from consecutive
 * pages.</li>
 * <li><b>dpi</b>: the resolution to retrieve an image in. If omitted, it
 * defaults to the value of the <code>imageDpi</code> configuration parameter.
 * To retrieve an image in its original resolution, no matter which, set this
 * parameter to 0.</li>
 * </ul>
 * 
 * @author sautter
 */
public class GoldenGateDisServlet extends GgServerClientServlet implements GoldenGateDisConstants, PageImageSource {
	private static final int DEFAULT_IMAGE_DPI = 96; // quarter of 96 dpi default screen resolution
	private static final int DEFAULT_THUMBNAIL_DPI = 24;
	private static final String THUMBNAIL_INFIX = ".thumb";
	
	private GoldenGateDisClient disClient;
	
	private File discCacheFolder;
	private boolean useDiscCache = true; // set to true if many different images are requested multiple times over longer periods of time
	private boolean discCacheSynchronized = false; // set to true if images are often requested by multiple threads at the same time
	
	private int memoryCacheSize = 32; // if many different images are requested multiple times over longer periods of time, a small cache is sufficient (backed by a disc cache). However, if each individual image is requested only for a short period of time, but then very often, a larger cache helps
	private boolean memoryCacheSynchronized = false; // set to true if images are often requested by multiple threads at the same time
	
	private int imageDpi = DEFAULT_IMAGE_DPI;
	private PageImage defaultImage = null;
	
	private int thumbnailDpi = DEFAULT_THUMBNAIL_DPI;
	private PageImage defaultThumbnail = null;
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.client.GgServerClientServlet#doInit()
	 */
	protected void doInit() throws ServletException {
		super.doInit();
		
		this.disClient = new GoldenGateDisClient(this.serverConnection);
		this.discCacheFolder = new File(new File(this.webInfFolder, "caches"), "disData");
		
		PageImage.addPageImageSource(this);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.easyIO.web.HtmlServlet#reInit()
	 */
	protected void reInit() throws ServletException {
		super.reInit();
		
		try {
			this.imageDpi = Integer.parseInt(config.getSetting("imageDpi", ("" + this.imageDpi)));
		} catch (NumberFormatException nfe) {}
		
		String defaultImageName = config.getSetting("defaultImage");
		if (defaultImageName != null) try {
			InputStream defaultImageIn = new FileInputStream(new File(this.dataFolder, defaultImageName));
			this.defaultImage = new PageImage(PageImage.readImage(defaultImageIn), 1, this);
			defaultImageIn.close();
		}
		catch (IOException ioe) {
			ioe.printStackTrace(System.out);
		}
		
		try {
			this.thumbnailDpi = Integer.parseInt(config.getSetting("thumbnailDpi", ("" + this.thumbnailDpi)));
		} catch (NumberFormatException nfe) {}
		
		String defaultThumbnailName = config.getSetting("defaultThumbnail");
		if (defaultThumbnailName != null) try {
			InputStream defaultThumbnailIn = new FileInputStream(new File(this.dataFolder, defaultThumbnailName));
			this.defaultThumbnail = new PageImage(PageImage.readImage(defaultThumbnailIn), 1, this);
			defaultThumbnailIn.close();
		}
		catch (IOException ioe) {
			ioe.printStackTrace(System.out);
		}
		
		this.useDiscCache = "true".equals(config.getSetting("useDiscCache", "true"));
		this.discCacheSynchronized = "true".equals(config.getSetting("discCacheSynchronized", "false"));
		if (this.useDiscCache) {
			this.disClient.setCacheFolder(this.discCacheFolder);
			this.disClient.setCacheSynchronized(this.discCacheSynchronized);
		}
		else this.disClient.setCacheFolder(null);
		
		try {
			this.memoryCacheSize = Integer.parseInt(config.getSetting("memoryCacheSize", ("" + this.memoryCacheSize)));
		} catch (NumberFormatException nfe) {}
		this.memoryCacheSynchronized = "true".equals(config.getSetting("memoryCacheSynchronized", "false"));
	}
	
	/* (non-Javadoc)
	 * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		
		//	get and check path
		String pathInfo = request.getPathInfo();
		if (pathInfo == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		
		//	truncate path and parse
		if (pathInfo.startsWith("/"))
			pathInfo = pathInfo.substring(1);
		String[] pathInfoParts = pathInfo.split("\\/++");
		
		//	one more test ...
		if (pathInfoParts.length == 0) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		
		//	first part of path is doc ID
		String docId = pathInfoParts[0];
		
		//	get session for logging
		HttpSession session = request.getSession(false);
		if (session != null)
			GgServerWebFrontendLogger.setDataForSession(session.getId(), null, request.getRemoteAddr());
		
		//	and yet another test ...
		if (pathInfoParts.length != 2) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		
		//	second part of path indicates page ID(s) and desired response
		String pageIdString = pathInfoParts[1];
		
		//	request for actual image
		if (pageIdString.endsWith("." + IMAGE_FORMAT)) {
			pageIdString = pageIdString.substring(0, (pageIdString.length() - ("." + IMAGE_FORMAT).length()));
			
			//	request for thumbnail
			if (pageIdString.endsWith(THUMBNAIL_INFIX)) {
				pageIdString = pageIdString.substring(0, (pageIdString.length() - (THUMBNAIL_INFIX).length()));
				this.doThumbnail(PageImage.getPageImageName(docId, Integer.parseInt(pageIdString)), request, response);
			}
			else this.doImage(PageImage.getPageImageName(docId, Integer.parseInt(pageIdString)), request, response);
		}
		
		//	request for wrapper page
		else {
			
			//	wrapper for individual page
			if (pageIdString.matches("[0-9]++")) {
				if (session == null)
					GgServerWebFrontendLogger.logForAddress(request.getRemoteAddr(), ("Full image retrieved for page " + pageIdString + " of document " + docId));
				else GgServerWebFrontendLogger.logForSession(session.getId(), ("Full image retrieved for page " + pageIdString + " of document " + docId));
				this.doImagePage(docId, Integer.parseInt(pageIdString), request, response);
			}
			
			//	wrapper for page sequence
			else if (pageIdString.matches("[0-9\\,\\-]++")) {
				TreeSet pageIdSet = new TreeSet();
				String[] pageRanges = pageIdString.split("\\,++");
				for (int pr = 0; pr < pageRanges.length; pr++) {
					String[] pageIds = pageRanges[pr].split("\\-++");
					if (pageIds.length != 0) try {
						if (pageIds.length == 1)
							pageIdSet.add(new Integer(pageIds[0]));
						else {
							int firstPageNumber = Integer.parseInt(pageIds[0]);
							int lastPageNumber = Integer.parseInt(pageIds[pageIds.length-1]);
							for (int p = firstPageNumber; p <= lastPageNumber; p++)
								pageIdSet.add(new Integer(p));
						}
					}
					catch (NumberFormatException nfe) {
						response.sendError(HttpServletResponse.SC_NOT_FOUND);
					}
				}
				if (session == null)
					GgServerWebFrontendLogger.logForAddress(request.getRemoteAddr(), ("Thumbnail page retrieved for pages " + pageIdString + " of document " + docId));
				else GgServerWebFrontendLogger.logForSession(session.getId(), ("Thumbnail page retrieved for pages " + pageIdString + " of document " + docId));
				this.doThumbnailPage(docId, pageIdSet, request, response);
			}
			
			//	invalid page number string
			else response.sendError(HttpServletResponse.SC_NOT_FOUND);
		}
	}
	
	private void doThumbnailPage(String docId, TreeSet pageIds, HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setContentType("text/html; charset=" + ENCODING);
		
		BufferedWriter out = new BufferedWriter(new OutputStreamWriter(response.getOutputStream(), ENCODING));
		out.write("<html><head>");
		out.newLine();
		out.write("<title>Pages " + pageIds.first() + " - " + pageIds.last() + "</title>");
		out.newLine();
		out.write("<script type=\"text/javascript\">");
		out.newLine();
		out.write("var thePageImageBasePath = \"" + request.getContextPath() + request.getServletPath() + "/\";");
		out.newLine();
		out.write("var PageImageWindow;");
		out.newLine();
		out.write("");
		out.newLine();
		out.write("function openPageImage(docId, page) {");
		out.newLine();
		out.write("	if ((PageImageWindow != null) && !PageImageWindow.closed) {");
		out.newLine();
		out.write("	  PageImageWindow.location.href = (thePageImageBasePath + docId + \"/\" + page);");
		out.newLine();
		out.write("	  PageImageWindow.focus();");
		out.newLine();
		out.write("	}");
		out.newLine();
		out.write("  else PageImageWindow = window.open((thePageImageBasePath + docId + \"/\" + page), \"PageImageWindow\", \"width=415,height=635,top=100,left=100,resizable=yes,scrollbars=yes\");");
		out.newLine();
		out.write("}");
		out.newLine();
		out.write("");
		out.newLine();
		out.write("function closePageImage() {");
		out.newLine();
		out.write("  if ((PageImageWindow != null) && !PageImageWindow.closed)");
		out.newLine();
		out.write("    PageImageWindow.close();");
		out.newLine();
		out.write("}");
		out.newLine();
		out.write("</script>");
		out.newLine();
		out.write("</head><body onunload=\"closePageImage();\">");
		out.newLine();
		out.write("<table style=\"border-width: 0px;\" align=\"center\" width=\"100%\">");
		out.newLine();
		out.write("<tr>");
		out.newLine();
		for (Iterator pnit = pageIds.iterator(); pnit.hasNext();) {
			Integer pageId = ((Integer) pnit.next());
			out.write("<td style=\"text-align: center; font-family: Verdana; font-size: 10pt;\">Page " + pageId + "</td>");
			out.newLine();
		}
		out.write("</tr>");
		out.newLine();
		out.write("<tr>");
		out.newLine();
		for (Iterator pnit = pageIds.iterator(); pnit.hasNext();) {
			Integer pageId = ((Integer) pnit.next());
			out.write("<td style=\"padding: 5px; vertical-align: middle;\">");
			out.newLine();
			out.write("<a onclick=\"openPageImage('" + docId + "', '" + pageId + "'); return false;\" href=\"#\">");
			out.newLine();
			out.write("<img title=\"View page " + pageId + " in full resolution\" src=\"" + request.getContextPath() + request.getServletPath() + "/" + docId + "/" + pageId + THUMBNAIL_INFIX + "." + IMAGE_FORMAT + "\">");
			out.newLine();
			out.write("</a>");
			out.newLine();
			out.write("</td>");
			out.newLine();
		}
		out.write("</tr>");
		out.newLine();
		out.write("</table>");
		out.newLine();
		out.write("</body></html>");
		out.newLine();
		out.flush();
	}
	
	private void doThumbnail(String name, HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setContentType("image/" + IMAGE_FORMAT);
		
		try {
			OutputStream out = response.getOutputStream();
			PageImage pi = this.getCachedPageImage(name, out, this.thumbnailDpi);
			if (pi == null)
				return;
			pi = pi.scaleToDpi(this.thumbnailDpi);
			pi.writeImage(out);
		}
		catch (Exception e) {
			if (this.defaultThumbnail == null)
				response.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
			else {
				OutputStream out = response.getOutputStream();
				this.defaultThumbnail.writeImage(out);
				out.flush();
			}
		}
	}
	
	private void doImagePage(String docId, int pageId, HttpServletRequest request, HttpServletResponse response) throws IOException {
		String parameters = request.getQueryString();
		
		response.setContentType("text/html; charset=" + ENCODING);
		
		BufferedWriter out = new BufferedWriter(new OutputStreamWriter(response.getOutputStream(), ENCODING));
		out.write("<html><head>");
		out.newLine();
		out.write("<title>Page " + pageId + "</title>");
		out.newLine();
		out.write("</head><body>");
		out.newLine();
		out.write("<div align=\"center\" width=\"100%\">");
		out.newLine();
		out.write("<img src=\"" + request.getContextPath() + request.getServletPath() + "/" + docId + "/" + pageId + "." + IMAGE_FORMAT + ((parameters == null) ? "" : ("?" + parameters)) + "\">");
		out.newLine();
		out.write("</div>");
		out.newLine();
		out.write("</body></html>");
		out.newLine();
		out.flush();
	}
	
	private Map memoryCache = Collections.synchronizedMap(new LinkedHashMap((this.memoryCacheSize + (this.memoryCacheSize / 8)), 0.9f, true) {
		protected boolean removeEldestEntry(Entry e) {
			return (this.size() > memoryCacheSize);
		}
	});
	private static final PageImage CACHING = new PageImage(new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_BINARY), 1, null);
	
	private PageImage getCachedPageImage(String name, OutputStream directOut, int dpi) throws IOException {
		if (this.memoryCacheSize < 1) {
			PageImageInputStream piis = this.disClient.getPageImageAsStream(name);
			if ((piis.currentDpi != dpi) && (0 < dpi))
				directOut = null;
			if (directOut == null)
				return new PageImage(piis);
			byte[] buffer = new byte[1024];
			for (int read; (read = piis.read(buffer, 0, buffer.length)) != -1;)
				directOut.write(buffer, 0, read);
			piis.close();
			return null;
		}
		else if (this.memoryCacheSynchronized)
			return this.getCachedPageImageSynchronized(name, directOut, dpi);
		else return this.getCachedPageImageUnSynchronized(name, directOut, dpi);
	}
	
	private PageImage getCachedPageImageUnSynchronized(String name, OutputStream directOut, int dpi) throws IOException {
		PageImage pageImage = ((PageImage) this.memoryCache.get(name));
		
		//	cache miss
		if (pageImage == null) {
			
			//	get stream
			PageImageInputStream piis = this.disClient.getPageImageAsStream(name);
			
			//	check if direct out possible
			if ((piis.currentDpi != dpi) && (0 < dpi))
				directOut = null;
			
			//	request for actual image, fetch it directly
			if (directOut == null)
				pageImage = new PageImage(piis);
			
			//	direct out request, cache image along the way
			else {
				ByteArrayOutputStream cacheOut = new ByteArrayOutputStream();
				byte[] buffer = new byte[1024];
				for (int read; (read = piis.read(buffer, 0, buffer.length)) != -1;) {
					directOut.write(buffer, 0, read);
					cacheOut.write(buffer, 0, read);
				}
				piis.close();
				pageImage = new PageImage(PageImage.readImage(new ByteArrayInputStream(cacheOut.toByteArray())), piis.originalWidth, piis.originalHeight, piis.originalDpi, piis.currentDpi, piis.leftEdge, piis.rightEdge, piis.topEdge, piis.bottomEdge, piis.source);
			}
			
			//	cache it
			this.memoryCache.put(name, pageImage);
			
			//	return it
			return ((directOut == null) ? pageImage : null);
		}
		
		//	check if direct out possible
		if ((pageImage.currentDpi != dpi) && (0 < dpi))
			directOut = null;
		
		//	cache hit, request for actual image
		if (directOut == null)
			return pageImage;
		
		//	write cached image to direct out
		pageImage.writeImage(directOut);
		return null;
	}
	
	private PageImage getCachedPageImageSynchronized(String name, OutputStream directOut, int dpi) throws IOException {
		
		//	prepare cache lookup
		PageImage pageImage;
		
		//	use loop instead of recursion (saves memory)
		while (true) {
			
			//	do unsynchronized cache lookup first ==> speeds up matters considerably
			pageImage = ((PageImage) this.memoryCache.get(name));
			
			//	unsynchronized cache hit, we're done
			if ((pageImage != null) && (pageImage != CACHING)) {
				if ((pageImage.currentDpi != dpi) && (0 < dpi))
					directOut = null;
				if (directOut == null)
					return pageImage;
				pageImage.writeImage(directOut);
				return null;
			}
			
			//	already caching, wait immediately
			if (pageImage == CACHING) {
				
				//	wait a little for other thread to finish caching
				try {
					Thread.sleep(50);
				} catch (InterruptedException ie) {}
				
				//	and recurse to repeat cache lookup
				continue;
			}
			
			//	re-do lookup, synchronized this time, to enter fetch synchronization
			synchronized (this.memoryCache) {
				pageImage = ((PageImage) this.memoryCache.get(name));
				
				//	cache miss, and no other thread is caching the requested image
				if (pageImage == null)
					this.memoryCache.put(name, CACHING);
			}
			
			//	another thread is in the process of caching the requested image
			if (pageImage == CACHING) {
				
				//	wait a little for other thread to finish caching
				try {
					Thread.sleep(50);
				} catch (InterruptedException ie) {}
				
				//	and recurse to repeat cache lookup
				continue;
			}
			
			//	cache hit
			if (pageImage != null) {
				if ((pageImage.currentDpi != dpi) && (0 < dpi))
					directOut = null;
				if (directOut == null)
					return pageImage;
				pageImage.writeImage(directOut);
				return null;
			}
			
			//	get stream
			PageImageInputStream piis = this.disClient.getPageImageAsStream(name);
			
			//	check if direct out possible
			if ((piis.currentDpi != dpi) && (0 < dpi))
				directOut = null;
			
			//	request for actual image, fetch it directly
			if (directOut == null)
				pageImage = new PageImage(piis);
			
			//	direct out request, cache image along the way
			else {
				ByteArrayOutputStream cacheOut = new ByteArrayOutputStream();
				byte[] buffer = new byte[1024];
				for (int read; (read = piis.read(buffer, 0, buffer.length)) != -1;) {
					directOut.write(buffer, 0, read);
					cacheOut.write(buffer, 0, read);
				}
				piis.close();
				pageImage = new PageImage(PageImage.readImage(new ByteArrayInputStream(cacheOut.toByteArray())), piis.originalWidth, piis.originalHeight, piis.originalDpi, piis.currentDpi, piis.leftEdge, piis.rightEdge, piis.topEdge, piis.bottomEdge, piis.source);
			}
			
			//	cache and return image (automatically removes marker)
			synchronized (this.memoryCache) {
				this.memoryCache.put(name, pageImage);
				return ((directOut == null) ? pageImage : null);
			}
		}
	}
	
	private void doImage(String name, HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setContentType("image/" + IMAGE_FORMAT);
		
		try {
			
			//	read bounding box parameter
			String bbString = request.getParameter(BOUNDING_BOX_ATTRIBUTE);
			BoundingBox[] bbs = BoundingBox.parseBoundingBoxes(bbString);
			
			//	read DPI parameter
			String dpiString = request.getParameter("dpi");
			int dpi = this.imageDpi;
			if (dpiString != null) try {
				dpi = Integer.parseInt(dpiString);
			} catch (NumberFormatException nfe) {}
			
			//	get ready to send
			OutputStream out = response.getOutputStream();
			
			//	do cache lookup, with direct write bypass if no scaling or cutting required
			PageImage pi = this.getCachedPageImage(name, ((bbs == null) ? out : null), dpi);
			
			//	image written directly
			if (pi == null)
				return;
			
			//	request for full image
			if (bbs == null) {
				if (dpi != pi.currentDpi)
					pi = pi.scaleToDpi(dpi);
				pi.writeImage(out);
			}
			
			//	request for image part, but bad bounding box string
			else if (bbs[0] == null) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid bounding box parameter");
				return;
			}
			
			//	request for part of single image
			else if (bbs.length == 1) {
				pi = pi.getSubImage(bbs[0], true);
				if (dpi != pi.currentDpi)
					pi = pi.scaleToDpi(dpi);
				pi.writeImage(out);
			}
			
			//	request for compiled image
			else {
				pi = new PageImage(PageImage.compileImage(name.substring(0, name.lastIndexOf('.')), Integer.parseInt(name.substring(name.lastIndexOf('.') + 1)), bbs, true, 3, null, this), pi.currentDpi, pi.source);
				if (dpi != pi.currentDpi)
					pi = pi.scaleToDpi(dpi);
				pi.writeImage(out);
			}
			
			//	send data
			out.flush();
		}
		catch (Exception e) {
			if (this.defaultImage == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
				return;
			}
			else {
				OutputStream out = response.getOutputStream();
				this.defaultImage.writeImage(out);
				out.flush();
			}
		}
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#isPageImageAvailable(java.lang.String)
	 */
	public boolean isPageImageAvailable(String name) {
		return this.disClient.isPageImageAvailable(name);
	}

	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#isPageImageAvailable(java.lang.String, int)
	 */
	public boolean isPageImageAvailable(String docId, int pageId) {
		return this.disClient.isPageImageAvailable(docId, pageId);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#getPageImage(java.lang.String)
	 */
	public PageImage getPageImage(String name) throws IOException {
		return this.disClient.getPageImage(name);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#getPageImageAsStream(java.lang.String)
	 */
	public PageImageInputStream getPageImageAsStream(String name) throws IOException {
		return this.disClient.getPageImageAsStream(name);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#getPageImage(java.lang.String, int)
	 */
	public PageImage getPageImage(String docId, int pageId) throws IOException {
		return this.disClient.getPageImage(docId, pageId);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.util.imaging.PageImageSource#getPageImageAsStream(java.lang.String, int)
	 */
	public PageImageInputStream getPageImageAsStream(String docId, int pageId) throws IOException {
		return this.disClient.getPageImageAsStream(docId, pageId);
	}
}