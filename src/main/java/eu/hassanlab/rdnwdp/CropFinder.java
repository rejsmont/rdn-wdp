package eu.hassanlab.rdnwdp;

import ij.ImagePlus;
import ij.plugin.Resizer;
import io.scif.services.DatasetIOService;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.ops.OpService;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.convert.ConvertService;
import org.scijava.log.LogLevel;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.hdf5.HDF5ImageJ;
import sc.fiji.hdf5.DataSetInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

@Plugin(type = Command.class, menuPath = "Plugins>RDN-WDP>Crop reference")
public class CropFinder implements Command {

    @Parameter
    private LogService logService;

    @Parameter
    private CommandService commandService;

    @Parameter
    private ConvertService convertService;

    @Parameter
    private DatasetIOService ioService;

    @Parameter
    private DatasetService datasetService;

    @Parameter
    private OpService opService;

    @Parameter(label = "Input file")
    private File inputFile;

    @Parameter(label = "Search folder", style = "directory")
    private File searchFolder;

    @Parameter(label = "Search filter", required = false)
    private String filterString;

    @Parameter(label = "Reference dataset")
    private String dataset;

    @Parameter(label = "Output folder", style = "directory")
    private File outputFolder;

    @Parameter(label = "Number of threads", required = false)
    private Integer threads;

    @Override
    public void run() {
        List<File> list = new ArrayList<>();
        try {
            Files.walk(searchFolder.toPath()).forEach(entry -> {
                File file = entry.toFile();
                if (file.isFile() && file.getPath().endsWith(".h5") && (filterString.isEmpty() || file.getPath().contains(filterString))) {
                        list.add(file);
                }
            });
        } catch (IOException e) {
            logService.log(LogLevel.WARN, "Error when walking path " + searchFolder.getPath());
        }

        Dataset inputImage;
        try {
            inputImage = ioService.open(inputFile.getPath());
        } catch (Exception e) {
            return;
        }

        Map<File, Future<ShiftCalculator.Alignment>> futures = new HashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (File referenceFile : list) {
            AlignmentCalculator calculator = new AlignmentCalculator(inputImage, referenceFile, dataset);
            futures.put(referenceFile, pool.submit(calculator));
        }

        File bestFile = null;
        ShiftCalculator.Alignment bestAlignment = null;

        for(Map.Entry<File, Future<ShiftCalculator.Alignment>> entry : futures.entrySet()) {
            File referenceFile = entry.getKey();
            Future<ShiftCalculator.Alignment> future = entry.getValue();

            try {
                ShiftCalculator.Alignment alignment = future.get();
                if (bestAlignment == null) {
                    bestAlignment = alignment;
                    bestFile = referenceFile;
                }
                if (alignment.count > bestAlignment.count) {
                    bestAlignment = alignment;
                    bestFile = referenceFile;
                }
            } catch (Exception e) {}
        }

        logService.log(LogLevel.INFO, "The image comes from " + bestFile + " cropped " + bestAlignment);

        if (bestFile == null) {
            return;
        }

        ArrayList<DataSetInfo> datasets = HDF5ImageJ.hdf5list(bestFile.getPath());

        ImagePlus refimp =  HDF5ImageJ.hdf5read(bestFile.getPath(), dataset, "zyx");
        Dataset referenceImage = convertService.convert(refimp, Dataset.class).duplicate();
        refimp.close();

        for (DataSetInfo dataset : datasets) {
            ImagePlus imp = HDF5ImageJ.hdf5read(bestFile.getPath(), dataset.getPath(), "zyx");
            if ((imp.getWidth() == referenceImage.getWidth()) && (imp.getHeight() == referenceImage.getHeight())) {
                Dataset image = convertService.convert(imp, Dataset.class);
                long[] min = new long[image.numDimensions()];
                long[] max = new long[image.numDimensions()];

                for (int i = 0; i < image.numDimensions(); i++) {
                    if (image.axis(i).type() == Axes.X) {
                        min[i] = bestAlignment.getX() - 1;
                        max[i] = min[i] + inputImage.getWidth();
                    } else if (image.axis(i).type() == Axes.Y) {
                        min[i] = bestAlignment.getY() - 1;
                        max[i] = min[i] + inputImage.getHeight();
                    } else {
                        min[i] = image.min(i);
                        max[i] = image.max(i);
                    }
                }
                Interval interval = new FinalInterval(min, max);
                logService.log(LogLevel.INFO, "Cropping " + dataset.getPath() + " to " + Arrays.toString(min) + "-" + Arrays.toString(max));
                RandomAccessibleInterval rai = opService.transform().crop(image.getImgPlus(), interval);
                Dataset cropds = datasetService.create(rai);
                ImagePlus cropimp = convertService.convert(cropds, ImagePlus.class);

                //HDF5ImageJ.hdf5write( cropimp, outputFolder.getPath() + bestFile.getName(), dataset.getPath(), false);
                logService.log(LogLevel.INFO, "Saving " + dataset.getPath() + " " + cropimp + " to " + outputFolder.getPath() + bestFile.getName());
            }
            imp.close();
        }
    }

    /*
    public <T> Dataset doCrop(ImgPlus<T> img, Interval interval) {
        RandomAccessibleInterval<T> rai = opService.transform().crop(img, interval);
        Dataset ds = datasetService.create(rai);
        return ds;
    }

    public <T extends RealType<T>> Dataset doCrop(ImgPlus<T> img, Interval interval) {
        RandomAccessibleInterval<T> rai = opService.transform().crop(img, interval);
        Dataset ds = datasetService.create(rai);
        return ds;
    }
    */

    class AlignmentCalculator implements Callable<ShiftCalculator.Alignment> {

        private Dataset inputImage;
        private Dataset referenceImage;

        public AlignmentCalculator(Dataset inputImage, Dataset referenceImage) {
            this.inputImage = inputImage;
            this.referenceImage = referenceImage;
        }

        public AlignmentCalculator(Dataset inputImage, File referenceFile, String referenceDataset) {
            this.inputImage = inputImage;
            ImagePlus imp =  HDF5ImageJ.hdf5read(referenceFile.getPath(), referenceDataset, "zyx");
            this.referenceImage = convertService.convert(imp, Dataset.class).duplicate();
            imp.close();
        }

        @Override
        public ShiftCalculator.Alignment call() {

            Future future = commandService.run(ShiftCalculator.class, true,
                    "reference", referenceImage, "input", inputImage, "sampling", 0, "strict", true);
            try {
                CommandModule result = (CommandModule) future.get();
                return (ShiftCalculator.Alignment) result.getOutput("result");
            } catch (Exception e) {}

            return null;
        }
    }
}
