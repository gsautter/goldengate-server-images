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


import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;

import de.uka.ipd.idaho.gamta.Annotation;
import de.uka.ipd.idaho.gamta.QueriableAnnotation;
import de.uka.ipd.idaho.gamta.util.constants.LiteratureConstants;
import de.uka.ipd.idaho.gamta.util.imaging.PageImage;
import de.uka.ipd.idaho.goldenGate.DocumentEditor;
import de.uka.ipd.idaho.goldenGate.observers.AnnotationObserver;
import de.uka.ipd.idaho.goldenGate.observers.DisplayObserver;
import de.uka.ipd.idaho.goldenGate.plugins.AbstractDocumentEditorExtension;
import de.uka.ipd.idaho.goldenGate.plugins.Resource;
import de.uka.ipd.idaho.goldenGate.util.DialogPanel;
import de.uka.ipd.idaho.goldenGateServer.dis.GoldenGateDisConstants;
import de.uka.ipd.idaho.goldenGateServer.uaa.client.AuthenticatedClient;
import de.uka.ipd.idaho.goldenGateServer.uaa.client.AuthenticationManager;

/**
 * Plugin for showing the page images of a scanned and OCRed document in the
 * GoldenGATE Editor, automatically scrolling along with the editing window.
 * This plugin identifies the images to display based on the page ID attributes
 * of the paragraphs in the document. Thus, it does not work unless (a)
 * paragraphs are annotated and (b) the paragraphs have page ID attributes. In
 * addition, the viewer needs to know the folder (or base URL) to load the page
 * images from. The latter can be set dynamically in the editor GUI.
 * 
 * @author sautter
 */
public class PageImageViewer extends AbstractDocumentEditorExtension implements LiteratureConstants, GoldenGateDisConstants {
	
	/* 
	 * @see de.uka.ipd.idaho.goldenGate.plugins.GoldenGatePlugin#getPluginName()
	 */
	public String getPluginName() {
		return "Page Image Viewer";
	}
	
	/* 
	 * @see de.uka.ipd.idaho.goldenGate.plugins.DocumentEditorExtension#getExtensionPanel(de.goldenGate.DocumentEditor)
	 */
	public JPanel getExtensionPanel(DocumentEditor editor) {
		return new PageImageViewPanel(editor);
	}
	
	private static final String DEFAULT_PLACEHOLDER_TEXT = "Page Image View";
	
	private static final String[] zoomFactors = {"10", "25", "33", "50", "75", "100", "125", "150", "200", "Fit Size", "Fit Width", "Fit Height"};
	
	private class PageImageViewPanel extends JPanel {
		
		private Annotation[] paragraphs = null;
		
		private JLabel messageLabel = new JLabel(DEFAULT_PLACEHOLDER_TEXT, JLabel.CENTER);
		private boolean inMessageMode = true;
		
		private JPanel functionPanel = new JPanel(new BorderLayout());
		
		private ImageTray imageTray;
		private JScrollPane imageBox;
		
		private JComboBox zoomer = new JComboBox(zoomFactors);
		
		private boolean fitWidth = true;
		private boolean fitHeight = true;
		
		private DocumentEditor target;
		
		PageImageViewPanel(DocumentEditor target) {
			super(new BorderLayout(), true);
			this.target = target;
			
			this.setPreferredSize(new Dimension(100, 100));
			
			final QueriableAnnotation doc = this.target.getContent();
			
			this.target.addAnnotationObserver(new AnnotationObserver() {
				public void annotationAdded(QueriableAnnotation doc, Annotation annotation, Resource source) {
					if (PARAGRAPH_TYPE.equals(annotation.getType()))
						paragraphs = null;
				}
				public void annotationRemoved(QueriableAnnotation doc, Annotation annotation, Resource source) {
					if (PARAGRAPH_TYPE.equals(annotation.getType()))
						paragraphs = null;
				}
				public void annotationTypeChanged(QueriableAnnotation doc, Annotation annotation, String oldType, Resource source) {
					if (PARAGRAPH_TYPE.equals(oldType) || PARAGRAPH_TYPE.equals(annotation.getType()))
						paragraphs = null;
				}
				public void annotationAttributeChanged(QueriableAnnotation doc, Annotation annotation, String attributeName, Object oldValue, Resource source) {}
			});
			
			this.target.addDisplayObserver(new DisplayObserver() {
				public void displayPositionChanged(int topIndex, int bottomIndex) {
					System.out.println("PageImageViewPanel: display scrolled");
					
					if (paragraphs == null)
						paragraphs = doc.getAnnotations(PARAGRAPH_TYPE);
					
					//	get page number attribute
					Object firstPageId = null;
					for (int p = 0; p < paragraphs.length; p++) {
						if (paragraphs[p].getStartIndex() > topIndex) {
							if (paragraphs[p].hasAttribute(PAGE_ID_ATTRIBUTE)) {
								firstPageId = paragraphs[p].getAttribute(PAGE_ID_ATTRIBUTE);
								p = paragraphs.length;
							}
						}
					}
					
					//	get page number
					int fpId = -1;
					if (firstPageId != null) try {
						fpId = Integer.parseInt(firstPageId.toString());
					}
					catch (NumberFormatException nfe) {
						fpId = -1;
					}
					
					//	get last page number attribute
					Object lastPageId = null;
					for (int p = 0; p < paragraphs.length; p++) {
						if (paragraphs[p].getEndIndex() > bottomIndex) {
							if (paragraphs[p].hasAttribute(PAGE_ID_ATTRIBUTE)) {
								lastPageId = paragraphs[p].getAttribute(PAGE_ID_ATTRIBUTE);
								p = paragraphs.length;
							}
						}
					}
					
					//	update last page number
					int lpId = -1;
					if (lastPageId != null) try {
						lpId = Integer.parseInt(lastPageId.toString());
					}
					catch (NumberFormatException nfe) {
						lpId = -1;
					}
					
					//	update display
					displayPageImages(fpId, lpId);
				}
				public void displayLocked() {}
				public void displayUnlocked() {}
			});
			
			this.imageTray = new ImageTray();
			
			this.imageBox = new JScrollPane(this.imageTray);
			this.imageBox.addComponentListener(new ComponentAdapter() {
				public void componentResized(ComponentEvent ce) {
					Dimension size = imageBox.getSize();
					
					if ((2 * size.width) < size.height)
						imageTray.setUseVerticalLayout(true);
					else if ((2 * size.height) < size.width)
						imageTray.setUseVerticalLayout(false);
					
					if (fitWidth && fitHeight) imageTray.fitSize();
					else if (fitWidth) imageTray.fitWidth();
					else if (fitHeight) imageTray.fitHeight();
				}
			});
			
			this.zoomer.setBorder(BorderFactory.createLoweredBevelBorder());
			this.zoomer.setPreferredSize(new Dimension(100, 21));
			this.zoomer.setMaximumRowCount(zoomFactors.length);
			this.zoomer.setSelectedItem("Fit Size");
			this.zoomer.addItemListener(new ItemListener() {
				public void itemStateChanged(ItemEvent ie) {
					String selected = zoomer.getSelectedItem().toString();
					if ("Fit Size".equals(selected)) {
						fitWidth = true;
						fitHeight = true;
						imageTray.fitSize();
					}
					else if ("Fit Width".equals(selected)) {
						fitWidth = true;
						fitHeight = false;
						imageTray.fitWidth();
					}
					else if ("Fit Height".equals(selected)) {
						fitWidth = false;
						fitHeight = true;
						imageTray.fitHeight();
					}
					else {
						fitWidth = false;
						fitHeight = false;
						float factor = Integer.parseInt(selected);
						imageTray.zoom(factor / 100);
					}
				}
			});
			
			this.functionPanel.add(this.zoomer, BorderLayout.EAST);
			this.add(this.messageLabel, BorderLayout.CENTER);
		}
		
		void displayPageImages(int fpId, int lpId) {
			
			//	fill in gap if one of page numbers invalid
			if (fpId == -1)
				fpId = lpId;
			else if (lpId == -1)
				lpId = fpId;
			
			//	display message for missing page numbers
			if ((fpId == -1) && (lpId == -1)) {
				this.messageLabel.setText("<HTML>" + DEFAULT_PLACEHOLDER_TEXT + "<BR>Cannot display images of unknown pages.<BR>Please make sure the " + PARAGRAPH_TYPE + " annotations in the document have correct " + PAGE_NUMBER_ATTRIBUTE + " attributes.</HTML>");
				
				//	change layout if necessary
				if (!this.inMessageMode) {
					this.removeAll();
					this.add(this.messageLabel, BorderLayout.CENTER);
					this.inMessageMode = true;
				}
			}
			
			//	display page images
			else {
				
				//	change layout if necessary
				if (this.inMessageMode) {
					this.removeAll();
					this.add(this.functionPanel, BorderLayout.NORTH);
					this.add(this.imageBox, BorderLayout.CENTER);
					this.inMessageMode = false;
				}
				
				//	remove old images
				this.imageTray.clearImages();
				
				//	add first image
				Image image = this.getPageImage(fpId);
				
				//	could not retrieve image for some reason
				if (image == null) {
					this.messageLabel.setText("<HTML>" + DEFAULT_PLACEHOLDER_TEXT + "<BR>Cannot load page images if document not loaded from GoldenGATE Server.</HTML>");
					
					//	change layout if necessary
					if (!this.inMessageMode) {
						this.removeAll();
						this.add(this.messageLabel, BorderLayout.CENTER);
						this.inMessageMode = true;
					}
				}
				
				//	got images
				else {
					this.imageTray.addImageLast(image);
					
					//	add new images
					for (int pid = (fpId+1); pid <= lpId; pid++) {
						image = this.getPageImage(pid);
						if (image != null)
							this.imageTray.addImageLast(image);
					}
				}
			}
			
			//	make changes visible
			this.revalidate();
			this.repaint();
		}
		
		private String docId;
		private GoldenGateDisClient disClient;
		private HashMap imageCache = new HashMap();
		
		Image getPageImage(int pageId) {
			System.out.println("Getting image of page " + pageId);
			
			//	get document ID
			if (this.docId == null)
				this.docId = this.target.getContent().getDocumentProperty(DOCUMENT_ID_ATTRIBUTE);
			
			//	check document ID
			if (this.docId == null)
				return null;
			
			//	do cache lookup
			String cacheKey = (this.docId + "." + pageId);
			if (this.imageCache.containsKey(cacheKey)) {
				System.out.println(" - cache hit");
				return ((Image) this.imageCache.get(cacheKey));
			}
			
			//	do file cache lookup
			String cacheDataName = ("cache/" + cacheKey + "." + IMAGE_FORMAT);
			if (dataProvider.isDataAvailable(cacheDataName)) try {
				InputStream iis = dataProvider.getInputStream(cacheDataName);
				BufferedImage image = PageImage.readImage(iis);
				iis.close();
				this.imageCache.put(cacheKey, image);
				System.out.println(" - file cache hit");
				return image;
			}
			catch (IOException ioe) {
				System.out.println(" - " + ioe.getClass().getName() + " (" + ioe.getMessage() + ") while loading file cached image for '" + cacheKey + "'");
				ioe.printStackTrace(System.out);
			}
			
			/*
			 * try to eastablish server connection. If the document was loaded
			 * from DIO, this will work, and the classes used here are
			 * available. If not ... well, we catch that.
			 */
			if (this.disClient == null) try {
				if (AuthenticationManager.isAuthenticated()) {
					AuthenticatedClient authClient = AuthenticationManager.getAuthenticatedClient();
					this.disClient = new GoldenGateDisClient(authClient.getServerConnection());
				}
			}
			catch (Throwable t) {
				System.out.println(" - no access to the required connection classes");
				return null;
			}
			
			//	cache miss, load & cache image
			try {
				PageImage pImage = this.disClient.getPageImage(this.docId, pageId);
				System.out.println(" - image loaded from server");
				
				if (dataProvider.isDataEditable(cacheDataName)) {
					OutputStream ios = dataProvider.getOutputStream(cacheDataName);
					pImage.writeImage(ios);
					ios.flush();
					ios.close();
					System.out.println("   - image cached in file");
				}
				this.imageCache.put(cacheKey, pImage.image);
				return pImage.image;
			}
			catch (IOException ioe) {
				System.out.println(" - " + ioe.getClass().getName() + " (" + ioe.getMessage() + ") while loading image for '" + cacheKey + "' from server");
				ioe.printStackTrace(System.out);
			}
			
			//	this will lead to an error message in the display
			return null;
		}
		
		private class ImageTray extends JPanel {
			
			private float currentZoomFactor = 1.0f;
			
			private LinkedList images = new LinkedList();
			
			private boolean isVerticalLayout = false;
			private GridBagConstraints gbc = new GridBagConstraints();
			
			ImageTray() {
				super(new GridBagLayout(), true);
				
				this.gbc.insets.top = 3;
				this.gbc.insets.bottom = 3;
				this.gbc.insets.left = 3;
				this.gbc.insets.right = 3;
				
				this.gbc.weighty = 0;
				this.gbc.weightx = 0;
				
				this.gbc.gridheight = 1;
				this.gbc.gridwidth = 1;
				
				this.gbc.fill = GridBagConstraints.NONE;
				
				this.gbc.gridy = 0;
				this.gbc.gridx = 0;
			}
			
//			private void addImageFirst(Image image) {
//				ImagePanel imagePanel = new ImagePanel(image, true);
//				this.images.addFirst(imagePanel);
//				this.layoutImages();
//			}
			
			void addImageLast(Image image) {
				ImagePanel imagePanel = new ImagePanel(image, true);
				this.images.addLast(imagePanel);
				this.layoutImages();
			}
			
//			private void removeImageFirst() {
//				this.images.removeFirst();
//				this.layoutImages();
//			}
			
//			private void removeImageLast() {
//				this.images.removeLast();
//				this.layoutImages();
//			}
			
			void clearImages() {
				this.images.clear();
				this.layoutImages();
			}
			
			void setUseVerticalLayout(boolean useVerticalLayout) {
				if (this.isVerticalLayout != useVerticalLayout) {
					this.isVerticalLayout = useVerticalLayout;
					this.layoutImages();
				}
			}
			
			void layoutImages() {
				this.removeAll();
				
				GridBagConstraints gbc = ((GridBagConstraints) this.gbc.clone());
				
				for (Iterator ii = this.images.iterator(); ii.hasNext();) {
					ImagePanel image = ((ImagePanel) ii.next());
					
					this.add(image, gbc.clone());
					
					if (this.isVerticalLayout) gbc.gridy++;
					else gbc.gridx++;
				}
				
				if (fitWidth && fitHeight) this.fitSize();
				else if (fitWidth) this.fitWidth();
				else if (fitHeight) this.fitHeight();
				else this.zoom(this.currentZoomFactor);
				
				this.revalidate();
			}
			
			void zoom(float factor) {
				this.currentZoomFactor = factor;
				
				for (Iterator ii = this.images.iterator(); ii.hasNext();)
					((ImagePanel) ii.next()).zoom(factor);
				
				this.revalidate();
			}
			
			void fitSize() {
				Dimension size = imageBox.getViewport().getExtentSize();
				float widthFactor = ((float) (size.width - 6)) / this.getImageWidth();
				float heightFactor = ((float) (size.height - 6)) / this.getImageHeight();
				
				this.zoom(Math.min(widthFactor, heightFactor));
			}
			void fitWidth() {
				Dimension size = imageBox.getViewport().getExtentSize();
				JScrollBar vScroll = imageBox.getVerticalScrollBar();
				
				this.zoom(((float) (size.width - 6 - (vScroll.isShowing() ? vScroll.getWidth() : 0))) / this.getImageWidth());
			}
			void fitHeight() {
				Dimension size = imageBox.getViewport().getExtentSize();
				JScrollBar hScroll = imageBox.getHorizontalScrollBar();
				
				this.zoom(((float) (size.height - 6 - (hScroll.isShowing() ? hScroll.getHeight() : 0))) / this.getImageHeight());
			}
			
			int getImageWidth() {
				int width = 0;
				
				for (Iterator ii = this.images.iterator(); ii.hasNext();) {
					ImagePanel image = ((ImagePanel) ii.next());
					
					if (this.isVerticalLayout) width = Math.max(width, image.imageWidth);
					else width = (width + 6 + image.imageWidth);
				}
				
				return width;
			}
			int getImageHeight() {
				int height = 0;
				
				for (Iterator ii = this.images.iterator(); ii.hasNext();) {
					ImagePanel image = ((ImagePanel) ii.next());
					
					if (this.isVerticalLayout) height = (height + 6 + image.imageHeight);
					else height = Math.max(height, image.imageHeight);
				}
				
				return height;
			}
			
			private class ImagePanel extends JPanel {
				
				private final Image image;
				private final int imageWidth;
				private final int imageHeight;
				
				ImagePanel(Image image, boolean isInTray) {
					super(true);
					this.setBackground(Color.WHITE);
					
					this.image = image;
					
					//	image not found, display error
					if (this.image == null) {
						JLabel errorLabel = new JLabel("<HTML>Image<BR>not<BR>found.</HTML>", JLabel.CENTER);
						this.setLayout(new BorderLayout());
						this.add(errorLabel, BorderLayout.CENTER);
						
						this.imageHeight = 50;
						this.imageWidth = 50;
					}
					
					//	got image, read data
					else {
						this.imageHeight = this.image.getHeight(null);
						this.imageWidth = this.image.getWidth(null);
						
						if (isInTray)
							this.addMouseListener(new MouseAdapter() {
								public void mouseClicked(MouseEvent me) {
									if (me.getClickCount() > 1)
										showFull();
								}
							});
					}
					
					Dimension dim = new Dimension(this.imageWidth, this.imageHeight);
					
					this.setMinimumSize(dim);
					this.setPreferredSize(dim);
					this.setMaximumSize(dim);
				}
				
				void showFull() {
					if (fullImageDialog == null) {
						fullImageDialog = new FullImageDialog();
						
						float factor = (1000.0f / Math.max(1000.0f, (this.imageWidth + 10)));
						factor = Math.min(factor, (700.0f / Math.max(700.0f, (this.imageHeight + 50))));
						fullImageDialog.setSize(((int) ((this.imageWidth + 10) * factor)), ((int) ((this.imageHeight + 50) * factor)));
						
						fullImageDialog.setLocationRelativeTo(this);
					}
					
					fullImageDialog.setImage(this.image);
					fullImageDialog.setVisible(true);
				}
				
				void zoom(float factor) {
					Dimension dim = new Dimension(((int) (this.imageWidth * factor)), ((int) (this.imageHeight * factor)));
					
					this.setMinimumSize(dim);
					this.setPreferredSize(dim);
					this.setMaximumSize(dim);
				}
				
				public void paintComponent(Graphics graphics) {
					super.paintComponent(graphics);
					if (this.image != null) graphics.drawImage(this.image, 0, 0, this.getWidth(), this.getHeight(), this);
				}
			}
			
			//	unique for the tray
			private FullImageDialog fullImageDialog = null;
			
			private class FullImageDialog extends DialogPanel {
				private Image image = null; 
				FullImageDialog() {
					super(false);
					JPanel imagePanel = new JPanel(true) {
						public void paintComponent(Graphics graphics) {
							super.paintComponent(graphics);
							if (image != null) graphics.drawImage(image, 0, 0, this.getWidth(), this.getHeight(), this);
						}
						public Dimension getMaximumSize() {
							return new Dimension(image.getWidth(null), image.getHeight(null));
						}
						public Dimension getMinimumSize() {
							return new Dimension(image.getWidth(null), image.getHeight(null));
						}
						public Dimension getPreferredSize() {
							return new Dimension(image.getWidth(null), image.getHeight(null));
						}
					};
					this.add(new JScrollPane(imagePanel), BorderLayout.CENTER);
				}
				void setImage(Image image) {
					this.image = image;
					this.repaint();
				}
			}
		}
	}
}