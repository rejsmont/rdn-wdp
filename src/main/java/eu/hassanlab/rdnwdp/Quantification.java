package eu.hassanlab.rdnwdp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.plugin.ChannelSplitter;
import ij.plugin.RGBStackMerge;
import mcib3d.geom.Object3D;
import mcib3d.geom.Object3DVoxels;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.geom.Voxel3D;
import mcib3d.image3d.ImageHandler;
import mcib3d.image3d.ImageInt;
import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.log.LogLevel;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.slf4j.LoggerFactory;
import sc.fiji.hdf5.HDF5ImageJ;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;


@Plugin(type = Command.class, menuPath = "Plugins>RDN-WDP>Quantification")
public class Quantification implements Command {

    @Parameter
    private LogService logService;

    @Parameter(label = "Input folder", style = "directory")
    private File inputFolder;

    @Parameter(label = "Output folder", style = "directory", required = false, persist = false)
    private File outputFolder;

    @Parameter(label = "Labeling Dataset")
    private String labelDataset = "/watershed/objects";

    @Parameter(label = "Quantification Datasets")
    private String quantNameString = "/aligned/channel0, /aligned/channel1, /aligned/channel2";

    @Parameter(label = "Number of threads")
    private Integer threads;

    @Override
    public void run() {
        List<File> list = new ArrayList<>();
        try {
            Files.walk(inputFolder.toPath()).forEach(entry -> {
                File file = entry.toFile();
                if (file.isFile() && file.getPath().endsWith(".h5")) {
                    list.add(file);
                }
            });
        } catch (IOException e) {
            logService.log(LogLevel.WARN, "Error when walking path " + inputFolder.getPath());
        }

        String[] quantDatasets = quantNameString.replaceAll("\\s","").split(",");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ExecutorCompletionService<Object> ecs = new ExecutorCompletionService<>(pool);

        for (File file : list) {
            ecs.submit(new ImageQuantifier(file, quantDatasets));
        }

        int submitted = list.size();
        while (submitted > 0) {
            try {
                ecs.take().get();
            } catch (Exception e) {
                logService.log(LogLevel.WARN, "One of the quantification threads failed!");
                e.printStackTrace();
            }
            submitted--;
        }

        pool.shutdown();
    }

    class ImageQuantifier implements Callable<Object> {

        private File file;
        private String[] datasets;

        ImageQuantifier(File file, String[] datasets) {
            this.file = file;
            this.datasets = datasets;
        }

        @Override
        public Object call() {
            ImagePlus[] channels = Arrays.stream(datasets)
                    .map(dataset -> HDF5ImageJ.hdf5read(file.getPath(), dataset, "zyx"))
                    .toArray(ImagePlus[]::new);

            ImagePlus image = RGBStackMerge.mergeChannels(channels, false);
            Objects3DPopulation objects = addVoxels(HDF5ImageJ.hdf5read(file.getPath(), labelDataset, "zyx"));
            ResultsTable result = getMeasurements(objects, image);

            result.save(file.getPath().replace(".h5", ".csv"));

            return this;
        }
    }

    private List<ArrayList<Voxel3D>> readVoxels(ImagePlus imp) {
        ImageInt image = ImageInt.wrap(imp);
        int minX = 0;
        int maxX = image.sizeX;
        int minY = 0;
        int maxY = image.sizeY;
        int minZ = 0;
        int maxZ = image.sizeZ;
        int minV = (int)(image.getMinAboveValue(0));
        int maxV = (int)(image.getMax());

        List<ArrayList<Voxel3D>> voxels = new ArrayList<>();
        for (int i = 0; i < maxV - minV + 1; i++) {
            ArrayList<Voxel3D> vlist = new ArrayList<>();
            voxels.add(vlist);
        }
        for (int k = minZ; k < maxZ; k++) {
            for (int j = minY; j<maxY; j++) {
                for (int i = minX; i < maxX; i++) {
                    float pixel = image.getPixel(i, j, k);
                    if (pixel > 0) {
                        Voxel3D voxel = new Voxel3D(i, j, k, pixel);
                        int oid = (int)(pixel) - minV;
                        voxels.get(oid).add(voxel);
                    }
                }
            }
        }

        List<ArrayList <Voxel3D>> objectVoxels = new ArrayList<>();
        for (int i = 0; i < maxV - minV + 1; i++) {
            if (voxels.get(i).size() > 0) {
                objectVoxels.add(voxels.get(i));
            }
        }

        return objectVoxels;
    }

    private Objects3DPopulation addVoxels(List<ArrayList<Voxel3D>> objectVoxels, ImagePlus imagePlus) {
        Objects3DPopulation objects = new Objects3DPopulation();
        ImageInt image = ImageInt.wrap(imagePlus);
        double xyres = image.getScaleXY();
        double zres = image.getScaleZ();
        objectVoxels.forEach(voxels -> {
            if (voxels.size() > 0) {
                Object3DVoxels objectV = new Object3DVoxels(voxels);
                objectV.setResXY(xyres);
                objectV.setResZ(zres);
                objectV.setLabelImage(image);
                objectV.computeContours();
                objectV.setLabelImage(null);
                objects.addObject(objectV);
            }
        });

        return objects;
    }

    private Objects3DPopulation addVoxels(ImagePlus imp) {
        List<ArrayList<Voxel3D>> voxels = readVoxels(imp);
        return addVoxels(voxels, imp);
    }

    private ResultsTable getMeasurements(Objects3DPopulation objects, ImagePlus image) {
        ArrayList<ImageHandler> imageChannels = new ArrayList<>();
        for (int channel = 0; channel < image.getNChannels(); channel++) {
            ImageStack channelStack = ChannelSplitter.getChannel(image, channel + 1);
            imageChannels.add(ImageHandler.wrap(channelStack));
        }

        ResultsTable results = new ResultsTable();
        results.showRowNumbers(false);
        for (int index = 0; index < objects.getObjectsList().size(); index++) {
            Object3D objectV = objects.getObjectsList().get(index);
            results.incrementCounter();
            results.addValue("Particle", index + 1);
            results.addValue("cx", objectV.getCenterX());
            results.addValue("cy", objectV.getCenterY());
            results.addValue("cz", objectV.getCenterZ());
            results.addValue("Volume", objectV.getVolumePixels());
            for (int channel = 0; channel < imageChannels.size(); channel++) {
                ImageHandler channelImage = imageChannels.get(channel);
                results.addValue("Integral " + channel,  objectV.getIntegratedDensity(channelImage));
                results.addValue("Mean " + channel, objectV.getPixMeanValue(channelImage));
            }
        }

        return results;
    }

    public static void main(String... args) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);

        final ImageJ ij = new ImageJ();
        ij.launch(args);

        int received = 0;
        boolean errors = false;

        while(received < 1 && !errors) {
            Future future = ij.command().run(Quantification.class, true);
            try {
                future.get();
                received++;
            }
            catch(Exception e) {
                errors = true;
            }
        }

        System.exit(0);
    }
}
