package eu.hassanlab.rdnwdp;

import ij.ImagePlus;
import ij.plugin.Resizer;
import io.scif.services.DatasetIOService;
import net.imagej.Data;
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
    private String dsReference;

    @Parameter(label = "Training dataset")
    private String dsTrain;

    @Parameter(label = "Mask dataset")
    private String dsMask;

    @Parameter(label = "Label file")
    private File labelFile;

    @Parameter(label = "Label dataset")
    private String dsLabel;

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
            AlignmentCalculator calculator = new AlignmentCalculator(inputImage, referenceFile, dsReference);
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

        Dataset referenceImage = readHDF5(bestFile, dsReference, "zyx");
        Map<String, Dataset> datasetMap = new HashMap<>();

        for (DataSetInfo dataset : datasets) {
            if (dataset.getPath().equals(dsTrain)) {
                datasetMap.put("/training/reference", processDataset(bestFile, dataset, bestAlignment, inputImage, referenceImage));
            } else if (dataset.getPath().equals(dsMask)) {
                datasetMap.put("/training/data", processDataset(bestFile, dataset, bestAlignment, inputImage, referenceImage));
            }
        }

        datasetMap.put("/training/labels", readHDF5(labelFile, dsLabel, "zyxc"));

        saveHDF5(datasetMap, outputFolder + File.separator + bestFile.getName());
    }

    private Dataset readHDF5(File file, String dataset, String layout) {
        ImagePlus imp =  HDF5ImageJ.hdf5read(file.getPath(), dataset, "zyx");
        Dataset ds = convertService.convert(imp, Dataset.class).duplicate();
        imp.close();
        return ds;
    }

    private void saveHDF5(Dataset img, String path, String dataset) {
        ImagePlus imp = convertService.convert(img.duplicate(), ImagePlus.class);
        logService.log(LogLevel.INFO, "Saving " + dataset + img + " to " + path);
        HDF5ImageJ.hdf5write(imp, path, dataset, false);
        imp.close();
    }

    private void saveHDF5(Map<String, Dataset> datasets, String path) {
        for (Map.Entry<String, Dataset> entry : datasets.entrySet()) {
            if (entry.getValue() != null) {
                saveHDF5(entry.getValue(), path, entry.getKey());
            }
        }
    }

    private Dataset processDataset(File file, DataSetInfo dataset, ShiftCalculator.Alignment alignment, Dataset inputImg, Dataset referenceImg) {
        Dataset img = readHDF5(file, dataset.getPath(), "zyx");
        if ((img.getWidth() == referenceImg.getWidth()) && (img.getHeight() == referenceImg.getHeight())) {
            long[] min = new long[img.numDimensions()];
            long[] max = new long[img.numDimensions()];

            for (int i = 0; i < img.numDimensions(); i++) {
                if (img.axis(i).type() == Axes.X) {
                    min[i] = alignment.getX() - 1;
                    max[i] = min[i] + inputImg.getWidth() - 1;
                } else if (img.axis(i).type() == Axes.Y) {
                    min[i] = alignment.getY() - 1;
                    max[i] = min[i] + inputImg.getHeight() - 1;
                } else {
                    min[i] = img.min(i);
                    max[i] = img.max(i);
                }
            }
            Interval interval = new FinalInterval(min, max);
            logService.log(LogLevel.INFO, "Cropping " + dataset.getPath() + " to " + Arrays.toString(min) + "-" + Arrays.toString(max));
            RandomAccessibleInterval rai = opService.transform().crop(img.getImgPlus(), interval);
            return datasetService.create(rai);
        }
        return null;
    }

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
