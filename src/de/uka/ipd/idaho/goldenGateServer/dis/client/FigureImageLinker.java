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

import java.io.File;
import java.io.IOException;

import de.uka.ipd.idaho.easyIO.settings.Settings;
import de.uka.ipd.idaho.gamta.Annotation;
import de.uka.ipd.idaho.gamta.QueriableAnnotation;
import de.uka.ipd.idaho.gamta.util.constants.LiteratureConstants;
import de.uka.ipd.idaho.goldenGateServer.srs.webPortal.SearchPortalConstants.SearchResultLinker;
import de.uka.ipd.idaho.htmlXmlUtil.accessories.HtmlPageBuilder;

/**
 * This search result linker produces hyperlinks from captions to the page
 * images that contain the respective figures.
 * 
 * @author sautter
 */
public class FigureImageLinker extends SearchResultLinker implements LiteratureConstants {
	
	private String pageImageServletUrl;
	
	private static final String[] unloadCalls = {
		"closePageImage();"
	};
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.srs.webPortal.SearchPortalConstants.SearchResultLinker#getName()
	 */
	public String getName() {
		return "Figure Image Linker";
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.srs.webPortal.SearchPortalConstants.SearchResultLinker#init()
	 */
	protected void init() {
		Settings set = Settings.loadSettings(new File(this.dataPath, "config.cnfg"));
		this.pageImageServletUrl = set.getSetting("pageImageServletUrl");
		if ((this.pageImageServletUrl != null) && !this.pageImageServletUrl.endsWith("/"))
			this.pageImageServletUrl += "/";
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.srs.webPortal.SearchPortalConstants.SearchResultLinker#getUnloadCalls()
	 */
	public String[] getUnloadCalls() {
		return unloadCalls;
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.srs.webPortal.SearchPortalConstants.SearchResultLinker#writePageHeadExtensions(de.uka.ipd.idaho.htmlXmlUtil.accessories.HtmlPageBuilder)
	 */
	public void writePageHeadExtensions(HtmlPageBuilder hpb) throws IOException {
		hpb.writeLine("<script type=\"text/javascript\">");
		hpb.writeLine("var pageImageBasePath = '" + this.pageImageServletUrl + "';");
		hpb.writeLine("var pageImageWindow;");
		hpb.writeLine("function openPageImage(docId, page) {");
		hpb.writeLine("  if ((pageImageWindow != null) && !pageImageWindow.closed) {");
		hpb.writeLine("    pageImageWindow.location.href = (pageImageBasePath + docId + '/' + page);");
		hpb.writeLine("    pageImageWindow.focus();");
		hpb.writeLine("  }");
		hpb.writeLine("  else pageImageWindow = window.open((pageImageBasePath + docId + '/' + page), ('Page ' + page), 'width=415,height=635,top=100,left=100,resizable=yes,scrollbars=yes');");
		hpb.writeLine("}");
		hpb.writeLine("function closePageImage() {");
		hpb.writeLine("  if ((pageImageWindow != null) && !pageImageWindow.closed)");
		hpb.writeLine("    pageImageWindow.close();");
		hpb.writeLine("}");
		hpb.writeLine("</script>");
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.srs.webPortal.SearchPortalConstants.SearchResultLinker#getAnnotationLinks(de.uka.ipd.idaho.gamta.Annotation)
	 */
	public SearchResultLink[] getAnnotationLinks(Annotation annotation) {
		if (this.pageImageServletUrl == null) return null;
		
		if (CAPTION_TYPE.equals(annotation.getType()) && (annotation instanceof QueriableAnnotation)) {
			String docId = annotation.getDocumentProperty(MASTER_DOCUMENT_ID_ATTRIBUTE, annotation.getDocumentProperty(DOCUMENT_ID_ATTRIBUTE));
			if (docId == null)
				return null;
			
			String pageIdString = null; 
			Annotation[] nested = ((QueriableAnnotation) annotation).getAnnotations();
			for (int n = 0; (pageIdString == null) && (n < nested.length); n++)
				pageIdString = ((String) nested[n].getAttribute(PAGE_ID_ATTRIBUTE));
			String pageNumberString = null; 
			for (int n = 0; (pageNumberString == null) && (n < nested.length); n++)
				pageNumberString = ((String) nested[n].getAttribute(PAGE_NUMBER_ATTRIBUTE));
			
			if ((pageIdString == null) && (pageNumberString == null))
				return null;
			
			SearchResultLink[] links = new SearchResultLink[1];
			links[0] = new SearchResultLink(VISUALIZATION,
					this.getClass().getName(),
					("View Figure"), 
					null,
					("View the image of the page containing the referenced figure."),
					"", 
					("openPageImage('" + docId + "', '" + ((pageIdString == null) ? pageNumberString : pageIdString) + "'); return false;")
				);
			return links;
		}
		else return null;
	}
}