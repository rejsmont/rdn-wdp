package eu.hassanlab.rdnwdp;

import ij.ImagePlus;
import io.scif.services.DatasetIOService;
import net.imagej.Dataset;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.convert.ConvertService;
import org.scijava.log.LogLevel;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.hdf5.HDF5ImageJ;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                    "reference", referenceImage, "input", inputImage, "sampling", 0);
            try {
                CommandModule result = (CommandModule) future.get();
                return (ShiftCalculator.Alignment) result.getOutput("result");
            } catch (Exception e) {}

            return null;
        }
    }
}
