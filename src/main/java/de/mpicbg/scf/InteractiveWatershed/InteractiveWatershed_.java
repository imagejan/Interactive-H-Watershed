package de.mpicbg.scf.InteractiveWatershed;


import java.awt.Component;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageRoi;
import ij.gui.Overlay;
import ij.gui.ScrollbarWithLabel;
import ij.plugin.Duplicator;
import ij.plugin.ImageCalculator;
import ij.plugin.LutLoader;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.LUT;
import net.imagej.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

import org.apache.commons.lang3.ArrayUtils;
import org.scijava.command.Command;
import org.scijava.command.InteractiveCommand;
import org.scijava.command.Previewable;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.NumberWidget;
import org.scijava.module.MutableModuleItem;
import org.scijava.ItemIO;
import org.scijava.ItemVisibility;

import de.mpicbg.scf.InteractiveWatershed.HWatershedLabeling.Connectivity;

/**
 * An ImageJ2 command using the HWatershedLabelling class
 * 
 *  
 * TODO:
 *  - relabel the exported image (to get a more intuitive labeling)
 *  - implement the run method so the plugin is macroable 
 *  - key listener sur le slider de l'image don't trigger segmentation update 
 *  - recreate display window on preview() if it was closed
 *  - list and prioritize Todos
 *  
 * Development ideas:
 *  - could implement a proper contour copnstruction (might be faster and label will be the same in solid/contour/image mode)
 *  - update status info with label value  under the slider
 *	- resample data to give an isotropic view (especially in side view)
 *	- building a swing UI would give a lot more flexibility in polishing the ui
 *	- faster watershed to limit waiting time
 *	- identify bottleneck in ui update 
 *	- there is an histogram widget in IJ, it could be put in sync with the intensity threshold parameter
 *	- the log slider are nice but the value display is not intuitive (fine to do in a classic ui)
 *	- the input harvester cannot be refreshed => view image list  is not updated  (fine to do in a classic ui)
 *
 *	Beyond the current plugin:
 *	- Given all the possible segment in the space (seed dynamics, flooding level) would it be possible to select the segments 
 *    with the best shapes given a simple shape model (feature vector on each regions)
 *  - probably not possible to explore all segments in the tree x threshold space
 *  	* build a new tree on the segment intersecting a given binarisation (i.e. segmentation on the dynamics hierarchy) 
 *  - Let user select some good/bad segment to create a shape/non-shape model
 *  - Let user correct some bad segmentation to create an improved merging criteria
 *  - learn a merging criteria
 * 
 */
@Plugin(type = Command.class, menuPath = "SCF>Labeling>Interactive watershed", initializer="initialize_HWatershed", headless = true, label="Interactive watershed")
//public class InteractiveMaxTree_<T extends RealType<T> > extends DynamicCommand implements Previewable  {
public class InteractiveWatershed_ extends InteractiveCommand implements Previewable  {

	// -- Parameters --
	@Parameter (type = ItemIO.BOTH)
	private	ImagePlus imp0;
	
	@Parameter(label = "Analyzed image" , visibility = ItemVisibility.MESSAGE)
	private String analyzedImageName = "test";

	
	@Parameter(style = NumberWidget.SCROLL_BAR_STYLE, persist = false, label="Seed dynamics", stepSize="0.05")
	private Float hMin_log;
	
	@Parameter(style = NumberWidget.SCROLL_BAR_STYLE, persist = false, label="Intensity threshold")
	private Float thresh_log;
	
	@Parameter(style = NumberWidget.SCROLL_BAR_STYLE, persist = false, label="peak flooding (in %)", min="0", max="100")
	private Float peakFlooding;
	
	@Parameter(style = ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE, choices = { "Image", "Contour overlay", "Solid overlay" } )
	private String displayStyle = "Image";
	
	@Parameter(label = "Slicing axis", style = ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE, choices = { "X", "Y", "Z" })
	private String slicingDirection = "Z";
	
	@Parameter(label = "View image", style = ChoiceWidget.LIST_BOX_STYLE, choices = { "" })
	private String imageToDisplayName;
	
	@Parameter(label = "export", callback="exportButton_callback" )
	private Button exportButton;
	
	
	//@Parameter
	//private EventService eventService;
	
	int[] pos= new int[] { 1, 1, 1};
	int displayOrient = 2; // 2 is for display orient perpendicular to z direction
	LUT segLut;
	LUT imageLut;
	double[] segDispRange = new double[2];
	double[] imageDispRange = new double[2];
	
	// to keep track of UI change
	String displayStyleOld;			//
	String imageToDisplayOldName;	//
	Map<String, Boolean> changed;	//
	Map<String, Double> previous;	//
	
	float minI, maxI; 				// min and max intensity of the input image
	int nDims; 						// dimensionnality of the input image
	SegmentHierarchyToLabelMap<FloatType> segmentTreeLabeler;

	ImagePlus impSegmentationDisplay; // the result window interactively updated
	ImagePlus imp_curSeg; // container of the current labelMap slice
	
	
	
	
	
	// -- Command methods --

	@Override
	public void run() {  
		// implement to allow macroability !
	}


	// -- Initializer methods --
	protected void initialize_HWatershed() {	
		
		if (imp0 == null){
			return;
		}
		
		Img<FloatType> input = ImageJFunctions.convertFloat(imp0); 
		nDims = input.numDimensions();
		if( nDims>3){
			IJ.error("The Interactive Watershed plugin handles only graylevel 2D/3D images \n Current image has more dimensions." );
		}
		
		
		imageToDisplayName = imp0.getTitle();
		imageToDisplayOldName = imageToDisplayName;
		displayStyleOld = displayStyle;
		///////////////////////////////////////////////////////////////////////////
		// create the HSegmentTree ////////////////////////////////////////////////
		
		float threshold = Float.NEGATIVE_INFINITY;
		HWatershedLabeling<FloatType> segmentTreeConstructor = new HWatershedLabeling<FloatType>(input, threshold, Connectivity.FACE);
		Tree hSegmentTree = segmentTreeConstructor.getTree();
		Img<IntType> hSegmentMap = segmentTreeConstructor.getLabelMapMaxTree();
		segmentTreeLabeler = new SegmentHierarchyToLabelMap<FloatType>( hSegmentTree, hSegmentMap, input );
		
		

		///////////////////////////////////////////////////////////////////////////
		// Initialize the UI //////////////////////////////////////////////////////

		double[] dynamics = hSegmentTree.getFeature("dynamics");
		maxI = (float) Arrays.stream(dynamics).max().getAsDouble();
		minI = (float) Arrays.stream(dynamics).min().getAsDouble(); ;
		
		
		// initialize analyzed image name  ////////////////////// 
		final MutableModuleItem<String> AnalyzedImageItem = getInfo().getMutableInput("analyzedImageName", String.class);
		AnalyzedImageItem.setValue(this, imp0.getTitle() );
		
		// initialize seed threshold (jMin) slider attributes ////////////////////// 
		final MutableModuleItem<Float> thresholdItem = getInfo().getMutableInput("hMin_log", Float.class);
		thresholdItem.setMinimumValue( new Float(0) );
		thresholdItem.setMaximumValue( new Float(Math.log(maxI-minI+1)) );
		thresholdItem.setStepSize( 0.05);
		hMin_log = 0f;
		thresholdItem.setValue(this, hMin_log);
		
		// initialize intensity threshold slider attributes ////////////////////////////
		final MutableModuleItem<Float> thresholdItem2 = getInfo().getMutableInput("thresh_log", Float.class);
		thresholdItem2.setMinimumValue( new Float(0) );
		thresholdItem2.setMaximumValue( new Float(Math.log(maxI-minI+1)) );
		thresholdItem2.setStepSize( 0.05);
		thresh_log = 0f;
		thresholdItem2.setValue(this, thresh_log);
		
		// initialize peak flooding (%) slider attributes ////////////////////////////
		final MutableModuleItem<Float> thresholdItem3 = getInfo().getMutableInput("peakFlooding", Float.class);
		peakFlooding = 100f;
		thresholdItem3.setValue(this, peakFlooding);
		
				
		// initialize the image List attributes ////////////////////////////
		updateImagesToDisplay();
		
		
		// slicing direction
		if( nDims==2){
			final MutableModuleItem<String> slicingItem = getInfo().getMutableInput("slicingDirection", String.class);
			getInfo().removeInput( slicingItem );
		}
		
		
		///////////////////////////////////////////////////////////////////////////////////
		// initialize plugin state ////////////////////////////////////////////////////////
		
		// collect information to initialize visualization of the segmentation display
		imageDispRange[0] = (float)imp0.getDisplayRangeMin();
		imageDispRange[1] = (float)imp0.getDisplayRangeMax();
		
		int nLabel = hSegmentTree.getNumNodes();
		segDispRange[0] = 0;
		segDispRange[1] = 2*nLabel;
		
		segLut = LutLoader.openLut( IJ.getDirectory("luts") + File.separator + "glasbey_inverted.lut");
		imageLut = (LUT) imp0.getProcessor().getLut().clone();
		
		
		// initialize UI status
		changed = new HashMap<String,Boolean>();
		changed.put("pos", 				false);
		changed.put("hMin", 			false);
		changed.put("thresh", 			false);
		changed.put("peakFlooding", 	false);
		changed.put("displayOrient",	false);
		//System.out.println(displayOrientString + " : "+ displayOrient);
		
		displayOrient = getDisplayOrient();
		previous = new HashMap<String,Double>();
		previous.put("pos", 			(double)pos[displayOrient]);
		previous.put("hMin", 			(double)getHMin());
		previous.put("thresh", 			(double)getThresh());
		previous.put("peakFlooding", 	(double)peakFlooding);
		
		
		
		// create the window to show the segmentation display
		updateSegmentationDisplay();
		
		segmentTreeLabeler.updateTreeLabeling( getHMin() );
		Img<IntType> img_currentSegmentation = segmentTreeLabeler.getLabelMap(getThresh(), peakFlooding, displayOrient, pos[displayOrient]-1);
		imp_curSeg = ImageJFunctions.wrapFloat(img_currentSegmentation, "treeCut");
		render();
		
		
	} // end of the initialization! 
	
	
	private float getHMin(){
		return (float)Math.exp(hMin_log)+minI-1;
	}
	
	
	
	private float getThresh(){
		return (float)Math.exp(thresh_log)+minI-1;
	}
	
	
	
	private int getDisplayOrient(){
		
		int displayOrient;
		
		if( slicingDirection.equals("Y") ){
			displayOrient=1;
		}
		else if( slicingDirection.equals("X") ){
			displayOrient=0;
		}
		else{ //if( slicingDirection.equals("Z") ){
			displayOrient=2;
		}
		return displayOrient;
	}
	
	
	
	private void updateImagesToDisplay(){
		
		List<String> nameList = new ArrayList<String>();
		String[] imageNames = WindowManager.getImageTitles();
		
		System.out.println(ArrayUtils.toString(imageNames) );
		
		int[] dims0 = imp0.getDimensions();
		for(String imageName : imageNames){
			ImagePlus impAux = WindowManager.getImage(imageName);
			
			int[] dims = impAux.getDimensions();
			
			System.out.println(imageName+" : "+impAux.getTitle() );
			System.out.println(ArrayUtils.toString(dims) );
			
			boolean isSizeEqual = true;
			for(int d=0; d<dims.length; d++)
				if( dims0[d]!=dims[d] )
					isSizeEqual = false;
			if( isSizeEqual ){
				nameList.add(imageName);
			}
		}
		nameList.add("None");
		if( impSegmentationDisplay != null){
			if ( nameList.contains( impSegmentationDisplay.getTitle() ) ){
				nameList.remove( impSegmentationDisplay.getTitle() );
			}
		}
		if( !nameList.contains(imageToDisplayName) ){
			imageToDisplayName = nameList.get(0);
		}
			
		final MutableModuleItem<String> imageToDisplayItem = getInfo().getMutableInput("imageToDisplayName", String.class);
		imageToDisplayItem.setChoices( nameList );
		imageToDisplayItem.setValue(this, imageToDisplayName );
		
		//this.update( imageToDisplayItem , imageToDisplayName );
		//this.updateInput( imageToDisplayItem );
		
	}
	
	
	
	@Override
	public void preview(){
		
		 // check which parameter changed and update necessary value
		if( !wasStateChanged() ){
			return;
		}
		
		// update labelMap slice to visualize
		if( changed.get("thresh") || changed.get("pos") || changed.get("peakFlooding") || changed.get("displayOrient"))
		{
			Img<IntType> img_currentSegmentation = segmentTreeLabeler.getLabelMap(getThresh(), peakFlooding, displayOrient, pos[displayOrient]-1);
			RandomAccessibleInterval<IntType> rai_currentSegmentation =  Views.dropSingletonDimensions(img_currentSegmentation);
			
			
			long[] dimTest = new long[img_currentSegmentation.numDimensions()];
			img_currentSegmentation.dimensions(dimTest);
			System.out.println("img_currentSegmentation "+ArrayUtils.toString( dimTest ) );
			System.out.println("slicing direction "+ displayOrient);
			System.out.println("pos[slicingDir] "+ pos[displayOrient]);
			
			imp_curSeg = ImageJFunctions.wrapFloat(rai_currentSegmentation, "treeCut");
		}
		else if( changed.get("hMin") )
		{
			segmentTreeLabeler.updateTreeLabeling( getHMin() );
			Img<IntType> img_currentSegmentation = segmentTreeLabeler.getLabelMap(getThresh(), peakFlooding, displayOrient, pos[displayOrient]-1);
			RandomAccessibleInterval<IntType> rai_currentSegmentation =  Views.dropSingletonDimensions(img_currentSegmentation);
			
			imp_curSeg = ImageJFunctions.wrapFloat(rai_currentSegmentation, "treeCut");
		}
		
		int[] dimsTest = imp_curSeg.getDimensions();
		System.out.println("imp_curSeg "+ArrayUtils.toString( dimsTest ) );
		System.out.println("slicing direction "+ displayOrient);
		System.out.println("pos[slicingDir] "+ pos[displayOrient]);
		
		render();	
		
	}
	
	
	
	private boolean wasStateChanged(){
		
		// update the saved display parameter in case they were changed
		if( !(impSegmentationDisplay==null) ){
			if (displayStyleOld.startsWith("Contour") | displayStyleOld.startsWith("Solid") ){
				imageDispRange[0] = impSegmentationDisplay.getDisplayRangeMin();
				imageDispRange[1] = impSegmentationDisplay.getDisplayRangeMax();
				imageLut = (LUT) impSegmentationDisplay.getProcessor().getLut().clone();
			}
			else{
				segDispRange[0] = impSegmentationDisplay.getDisplayRangeMin();
				segDispRange[1] = impSegmentationDisplay.getDisplayRangeMax();
				segLut = (LUT) imp_curSeg.getProcessor().getLut().clone();
			}
		}
		else{
			updateSegmentationDisplay();
		}
		
		//update the list of image that can be overlaid by the segmentation
		updateImagesToDisplay();
		
		// reset all changed field to false
		for(String key : changed.keySet() ){
			changed.put(key, false);
		}
		
		// test which parameter has changed (only one can change at a time rk: not true if long update :-\ )
		boolean wasChanged  = false;
		System.out.println("getTresh():"+getThresh()+"  ;  previous thresh"+previous.get("thresh"));
		if( getThresh() != previous.get("thresh") ){
			changed.put("thresh",true);
			previous.put( "thresh" , (double)getThresh() );
			System.out.println("updated  :    getTresh():"+getThresh()+"  ;  previous thresh"+previous.get("thresh"));
			
			wasChanged  = true;
		}
		else if( getHMin() != previous.get("hMin") ){
			changed.put("hMin",true);
			previous.put( "hMin" , (double)getHMin() );
			wasChanged  = true;
		}
		else if( (double)peakFlooding != previous.get("peakFlooding") ){
			changed.put("peakFlooding",true);
			previous.put( "peakFlooding" , (double)peakFlooding );
			wasChanged  = true;
		}
		else if( displayOrient != getDisplayOrient() ){
			displayOrient = getDisplayOrient();
			changed.put("displayOrient",true);
			updateSegmentationDisplay();
			wasChanged  = true;
		}
		else if( (double)pos[displayOrient] != previous.get("pos") ){
			changed.put("pos",true);
			previous.put( "pos" , (double)pos[displayOrient] );
			wasChanged  = true;
		}
		else if( displayStyleOld != displayStyle ){
			displayStyleOld = displayStyle;
			wasChanged  = true;
		}
		else if( ! imageToDisplayOldName.equals(imageToDisplayName) ){
			imageToDisplayOldName = imageToDisplayName;
			wasChanged  = true;
		}
		
		return wasChanged;
	}
	
	
	
	private void updateSegmentationDisplay(){
		
		
		
		int[] dims=imp0.getDimensions();
		
		if( nDims==2 ){
			if( impSegmentationDisplay == null )
				impSegmentationDisplay = IJ.createImage("interactive watershed", (int)imp0.getWidth(), (int)imp0.getHeight(), 1, 32);
		}
		else{
			int[] dimensions = new int[nDims-1];
			
			int count=0;
			int[] dIndex = new int[] {0,1,3};
			for(int d=0; d<nDims; d++){
				if(d != displayOrient){
					dimensions[count]= dims[ dIndex[d] ];
					count++;
				}
			}
			if( impSegmentationDisplay != null ){
				impSegmentationDisplay.hide();
				impSegmentationDisplay.flush();
			}
			impSegmentationDisplay = IJ.createImage("interactive watershed-"+slicingDirection, "32-bit", (int)dimensions[0], (int)dimensions[1], 1, (int)dims[dIndex[displayOrient]], 1);
			
		}
		
		impSegmentationDisplay.show();
		addListenerToSegDisplay();
		
		impSegmentationDisplay.setZ(pos[displayOrient]);
	}
	
	
	
	private void addListenerToSegDisplay(){
		// ready to use component listener on the slider of impSegmentationDisplay
		Component[] components = impSegmentationDisplay.getWindow().getComponents();
		for(Component comp : components){
			if( comp instanceof ScrollbarWithLabel){
				ScrollbarWithLabel scrollBar = (ScrollbarWithLabel) comp;
				scrollBar.addAdjustmentListener( new AdjustmentListener(){

					@Override
					public void adjustmentValueChanged(AdjustmentEvent e) {	
						pos[displayOrient] = impSegmentationDisplay.getSlice();
						preview();	
					}
					
				});
			}
		}
	}
	
	
	
	protected <T extends NumericType<T>> void render(){
			
		//imp_curSeg.updateImage();
		ImageProcessor ip_curSeg  = imp_curSeg.getProcessor();
		ip_curSeg.setLut( segLut );
		
		ImageProcessor input_ip;
		if( imageToDisplayName.equals("None") ){
			input_ip = new FloatProcessor( impSegmentationDisplay.getWidth(), impSegmentationDisplay.getHeight() );
		}
		else{
			// todo: get the image pointed at by the ui when implemented
			ImagePlus impToDisplay = WindowManager.getImage( imageToDisplayName );
			
			System.out.println(imageToDisplayName+" : "+impToDisplay.toString() );
			System.out.println(imageToDisplayName+" : "+impToDisplay.getTitle() );
			
			
			ImagePlus impToDisplaySlice;
			if( nDims == 2){
				impToDisplaySlice = impToDisplay;
			}
			else{
				Img<?> imgToDisplay = ImageJFunctions.wrap( impToDisplay );
				
				
				long[] dimTest = new long[ imgToDisplay.numDimensions()];
				imgToDisplay.dimensions(dimTest);
				System.out.println("impToDisplaySlice "+ArrayUtils.toString( dimTest ) );
				System.out.println("slicing direction "+ displayOrient);
				System.out.println("pos[slicingDir] "+ pos[displayOrient]);
				
				RandomAccessibleInterval<?> slice =  Views.hyperSlice(imgToDisplay, displayOrient, pos[displayOrient]-1);
				slice =  Views.dropSingletonDimensions(slice);
				Cursor<? extends RealType<?>> cSlice0 = (Cursor<? extends RealType<?>>) Views.iterable(slice).cursor();
				Img<FloatType> imgSlice = new ArrayImgFactory< FloatType >().create( new long[] { impSegmentationDisplay.getWidth(), impSegmentationDisplay.getHeight() }, new FloatType() );
				Cursor<FloatType> cSlice = imgSlice.cursor();
				while(cSlice.hasNext())
					cSlice.next().set(cSlice0.next().getRealFloat() );
					
				impToDisplaySlice = ImageJFunctions.wrap(imgSlice, "test");
			}
			
			System.out.println("nDims "+ nDims);
			System.out.println("impToDisplaySlice "+ArrayUtils.toString(impToDisplaySlice.getDimensions() ) );
			
			input_ip = impToDisplaySlice.getProcessor().convertToFloat();
		}
		
		
		
		Overlay overlay = new Overlay();
		ImagePlus imp_Contour= null;
		if ( displayStyle.startsWith("Contour"))
		{
			
			Duplicator duplicator = new Duplicator();
			ImagePlus imp_curSeg_Dilate = duplicator.run(imp_curSeg);
			
			IJ.run(imp_curSeg_Dilate, "Maximum...", "radius=1");
			ImageCalculator ic = new ImageCalculator();
			imp_Contour = ic.run("Subtract create", imp_curSeg_Dilate, imp_curSeg);
			
			ImageRoi imageRoi = new ImageRoi(0,0, imp_Contour.getProcessor() );
			imageRoi.setOpacity(0.75);
			imageRoi.setZeroTransparent(true);
			imageRoi.setPosition(pos[displayOrient]);
			overlay.add(imageRoi);
			
			//input_imp.setPosition(zSlice);
			impSegmentationDisplay.setProcessor( input_ip );
			impSegmentationDisplay.setDisplayRange(imageDispRange[0], imageDispRange[1]);
			
		}
		else if ( displayStyle.startsWith("Solid"))
		{
			ImageRoi imageRoi = new ImageRoi(0,0, ip_curSeg );
			imageRoi.setOpacity(0.5);
			imageRoi.setZeroTransparent(true);
			imageRoi.setPosition(pos[displayOrient]);
			overlay.add(imageRoi);

			impSegmentationDisplay.setProcessor( input_ip );
			impSegmentationDisplay.setDisplayRange(imageDispRange[0], imageDispRange[1]);

		}
		else
		{
			impSegmentationDisplay.setProcessor( ip_curSeg );
			impSegmentationDisplay.setDisplayRange( segDispRange[0], segDispRange[1]);
		}
		
		impSegmentationDisplay.setOverlay(overlay);
		impSegmentationDisplay.show();
		
		
		
	}
	
	
	
	
	// todo: 
	//	- give proper hyperStack shape to the output (cf imagTools utils)
	//	- label from one to number of region
	
	protected void exportButton_callback(){
		
		segmentTreeLabeler.updateTreeLabeling( getHMin() );
		Img<IntType> export_img = segmentTreeLabeler.getLabelMap( getThresh() , peakFlooding);
		ImagePlus exported_imp = ImageJFunctions.wrapFloat(export_img, imp0.getTitle() + " - watershed (h="+getHMin()+",T="+getThresh()+",%="+peakFlooding+")" );
		
		LUT segmentationLUT = (LUT) imp_curSeg.getProcessor().getLut().clone();
		exported_imp.setLut(segmentationLUT);
		exported_imp.show();

	}
	
	
	@Override
	public void cancel(){
		// this function in never called in interactive command
		
	}

		
		
		
			
			
			
		
	
	
	
	public static <T extends RealType<T>> void main(final String... args) throws Exception {
		// Launch ImageJ as usual.
		final ImageJ ij = net.imagej.Main.launch(args);
		
		
		
		// Launch the command .
		IJ.openImage("F:\\projects\\2DEmbryoSection_Mette.tif").show();
		//Dataset dataset = (Dataset) ij.io().open("F:\\projects\\2DEmbryoSection_Mette.tif");
		//Dataset dataset2 = (Dataset) ij.io().open("F:\\projects\\2D_8peaks.tif");
		//ij.ui().show(dataset);
		
		ij.command().run(InteractiveWatershed_.class, true);
		
		
	}


	
}