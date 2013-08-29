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

package de.uka.ipd.idaho.goldenGateServer.dis.connectors;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Properties;

import org.icepdf.core.pobjects.Document;

import de.uka.ipd.idaho.gamta.DocumentRoot;
import de.uka.ipd.idaho.gamta.Gamta;
import de.uka.ipd.idaho.gamta.QueriableAnnotation;
import de.uka.ipd.idaho.gamta.util.ProgressMonitor;
import de.uka.ipd.idaho.gamta.util.imaging.ImagingConstants;
import de.uka.ipd.idaho.gamta.util.imaging.pdf.PdfExtractor;
import de.uka.ipd.idaho.goldenGateServer.GoldenGateServerComponentRegistry;
import de.uka.ipd.idaho.goldenGateServer.dio.connectors.GoldenGateDioEXP;
import de.uka.ipd.idaho.goldenGateServer.dis.GoldenGateDIS;
import de.uka.ipd.idaho.goldenGateServer.exp.GoldenGateEXP;

/**
 * This exporter attempts to obtain the original PDFs of documents stored in
 * DIO, to extract or render images of the pages, and to make them available via
 * DIS.
 * 
 * @author sautter
 */
public class GoldenGateDisDioConnector extends GoldenGateEXP implements ImagingConstants {
	
	private static final boolean DEBUG = true;
	
	private PdfExtractor pdfExtractor;
	
	private GoldenGateDIS dis;
	
	/**
	 * Constructor handing 'DIO-DIS' as the letter code to the super class.
	 */
	public GoldenGateDisDioConnector() {
		super("DIO-DIS");
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.exp.GoldenGateEXP#getExporterName()
	 */
	protected String getExporterName() {
		return "DioDis";
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.exp.GoldenGateEXP#getBinding()
	 */
	protected GoldenGateExpBinding getBinding() {
		return new GoldenGateDioEXP(this);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.exp.GoldenGateEXP#initComponent()
	 */
	protected void initComponent() {
		
		//	initialize super class
		super.initComponent();
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.exp.GoldenGateEXP#link()
	 */
	public void link() {

		// get access authority
		this.dis = ((GoldenGateDIS) GoldenGateServerComponentRegistry.getServerComponent(GoldenGateDIS.class.getName()));

		// check success
		if (this.dis == null) throw new RuntimeException(GoldenGateDIS.class.getName());
		
		//	link super class
		super.link();
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.exp.GoldenGateEXP#linkInit()
	 */
	public void linkInit() {
		
		//	create PDF Extractor
		this.pdfExtractor = new PdfExtractor(this.dataPath, this.dis);
		
		//	initialize super class
		super.linkInit();
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.exp.GoldenGateEXP#doUpdate(de.uka.ipd.idaho.gamta.QueriableAnnotation, java.util.Properties)
	 */
	protected void doUpdate(QueriableAnnotation doc, Properties docAttributes) throws IOException {
		
		//	try to obtain PDF
		String docId = ((String) doc.getAttribute(DOCUMENT_ID_ATTRIBUTE));
		String pdfUrl = ((String) doc.getAttribute(DOCUMENT_SOURCE_LINK_ATTRIBUTE));
		if ((docId == null) || (pdfUrl == null) || !pdfUrl.toLowerCase().startsWith("http://") || !pdfUrl.toLowerCase().endsWith(".pdf"))
			return;
		
		//	get localized PDF
		File pdfFile = this.getPdfFile(pdfUrl, docId);
		if (pdfFile == null)
			return;
		
		//	load PDF into memory
		BufferedInputStream pdfIn = new BufferedInputStream(new FileInputStream(pdfFile));
		ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		for (int read; (read = pdfIn.read(buffer, 0, buffer.length)) != -1;)
			pdfBytes.write(buffer, 0, read);
		pdfIn.close();
		byte[] bytes = pdfBytes.toByteArray();
		
		//	set up rendering
		DocumentRoot pdfDoc = Gamta.newDocument(Gamta.INNER_PUNCTUATION_TOKENIZER);
		pdfDoc.setAnnotationNestingOrder(PAGE_TYPE + " " + BLOCK_ANNOTATION_TYPE + " " + LINE_ANNOTATION_TYPE + " " + WORD_ANNOTATION_TYPE);
		pdfDoc.setAttribute("docId", docId);
		pdfDoc.setDocumentProperty("docId", docId);
		Document icePdfDoc = new Document();
		try {
			icePdfDoc.setInputStream(new ByteArrayInputStream(bytes), "");
		}
		catch (Exception e) {
			return;
		}
		
		//	render PDF (DIS automatically gets the images as it is passed to the constructor of the latter)
		ProgressMonitor pm = new ProgressMonitor() {
			public void setStep(String importStep) {
				if (DEBUG)
					System.out.println(importStep);
			}
			public void setInfo(String text) {
				if (DEBUG)
					System.out.println(text);
			}
			public void setBaseProgress(int baseProgress) {}
			public void setMaxProgress(int maxProgress) {}
			public void setProgress(int progress) {}
		};
		try {
			this.pdfExtractor.loadImagePdfPages(pdfDoc, icePdfDoc, bytes, pm);
		}
		catch (IOException ioe) {
			ioe.printStackTrace(System.out);
			this.pdfExtractor.loadTextPdf(pdfDoc, icePdfDoc, bytes, pm);
		}
	}
	
	private File getPdfFile(String docId, String pdfUrl) throws IOException {
		File pdfFile = this.getDocumentPdfFile(docId);
		if (pdfFile.exists() && (pdfFile.length() != 0))
			return pdfFile;
		
		URL url = new URL(pdfUrl);
		InputStream pdfIn = url.openStream();
		if (!pdfFile.createNewFile())
			return null;
		
		OutputStream pdfFileOut = new BufferedOutputStream(new FileOutputStream(pdfFile));
		byte[] buffer = new byte[1024];
		for (int read; (read = pdfIn.read(buffer, 0, buffer.length)) != -1;)
			pdfFileOut.write(buffer, 0, read);
		pdfFileOut.flush();
		pdfFileOut.close();
		pdfIn.close();
		
		return pdfFile;
	}
	
	private File getDocumentPdfFile(String docId) {
		File documentFolder = new File(this.dataPath, (docId.substring(0, 2) + "/" + docId.substring(2, 4)));
		documentFolder.mkdirs();
		return new File(documentFolder, (docId + ".pdf"));
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.goldenGateServer.exp.GoldenGateEXP#doDelete(java.lang.String, java.util.Properties)
	 */
	protected void doDelete(String docId, Properties docAttributes) throws IOException {
		//	retain images for now
	}
}