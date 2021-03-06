package eu.hassanlab.rdnwdp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ij.ImagePlus;
import ij.plugin.RGBStackMerge;
import mcib3d.geom.ObjectCreator3D;
import net.imagej.ImageJ;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.scijava.command.Command;
import org.scijava.log.LogLevel;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.slf4j.LoggerFactory;
import sc.fiji.hdf5.HDF5ImageJ;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;


@Plugin(type = Command.class, menuPath = "Plugins>RDN-WDP>Plotting")
public class Plotting implements Command {

    @Parameter
    private LogService logService;

    @Parameter(label = "Input folder", style = "directory")
    private File inputFolder;

    @Parameter(label = "Output folder", style = "directory", required = false, persist = false)
    private File outputFolder;

    @Parameter(label = "Reference Dataset")
    private String referenceDataset = "/aligned/channel0";

    @Parameter(label = "Plot Dataset")
    private String plotDataset = "/plot/nuclei/channel{c}";

    @Parameter(label = "Number of threads", required = false)
    private Integer threads;

    @Override
    public void run() {
        List<File> list = new ArrayList<>();
        try {
            Files.walk(inputFolder.toPath()).forEach(entry -> {
                File file = entry.toFile();
                if (file.isFile() && file.getPath().endsWith(".csv")) {
                    list.add(file);
                }
            });
        } catch (IOException e) {
            logService.log(LogLevel.WARN, "Error when walking path " + inputFolder.getPath());
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ExecutorCompletionService<Object> ecs = new ExecutorCompletionService<>(pool);

        for (File file : list) {
            //ImagePlotter plotter = new ImagePlotter(file);
            //plotter.call();
            ecs.submit(new ImagePlotter(file));
        }

        int submitted = list.size();
        while (submitted > 0) {
            try {
                ecs.take().get();
            } catch (Exception e) {
                logService.log(LogLevel.WARN, "One of the plotting threads failed!");
            }
            submitted--;
        }

        pool.shutdown();
    }

    class ImagePlotter implements Callable<Object> {

        private File file;
        private File hdf5;
        private ImagePlus reference;

        ImagePlotter(File file) {
            this.file = file;
            hdf5 = new File(file.getPath().replace(".csv", ".h5"));
            reference = HDF5ImageJ.hdf5read(hdf5.getPath(), referenceDataset, "zyx");
        }

        @Override
        public Object call() {

            logService.log(LogLevel.INFO, "Processing " + file.getPath());
            List<Nucleus> nuclei = readCSV();
            ImagePlus plot = plotNuclei(nuclei);
            if (plot != null) {
                HDF5ImageJ.hdf5write(plot, hdf5.getPath(), plotDataset, "", "%d", 0, false);
                logService.log(LogLevel.INFO, "Results saved to " + hdf5.getPath());
                plot.close();
                plot = null;
            } else {
                logService.log(LogLevel.WARN, "Failed to generate plot for " + file.getPath());
            }
            reference.close();
            reference = null;

            return this;
        }

        public List<Nucleus> readCSV() {

            List<Nucleus> nuclei = new ArrayList<>();
            try {
                Reader in = new FileReader(file);
                Iterable<CSVRecord> records = CSVFormat.RFC4180.withHeader().parse(in);
                for (CSVRecord record : records) {
                    Nucleus nucleus = new Nucleus(record);
                    nuclei.add(nucleus);
                }
                in.close();
            } catch (Exception e) {
                logService.log(LogLevel.WARN, "Failed to read CSV file " + file.getPath());
                e.printStackTrace();
            };

            return nuclei;
        }

        public ImagePlus plotNuclei(List<Nucleus> nuclei) {

            if (nuclei.size() == 0) {
                return null;
            }

            final int nChannels = nuclei.get(0).sizeF();
            ImagePlus[] channelImages = new ImagePlus[nChannels];

            for (int k = 0; k < nChannels; k++) {
                ObjectCreator3D objectImage =
                        new ObjectCreator3D(reference.getWidth(), reference.getHeight(), reference.getNSlices());
                for (Nucleus nucleus : nuclei) {
                    objectImage.createEllipsoid(
                        nucleus.getX(), nucleus.getY(),	nucleus.getZ(),
                        nucleus.getR(), nucleus.getR(), nucleus.getR(),
                        nucleus.getF(k), false);
                }
                channelImages[k] = new ImagePlus("Rendering C" + (k + 1), objectImage.getStack());
            }

            ImagePlus result = RGBStackMerge.mergeChannels(channelImages, false);

            for (int k = 0; k < nChannels; k++) {
                channelImages[k].close();
                channelImages[k] = null;
            }

            return result;
        }
    }

    class Nucleus {
        private int x;
        private int y;
        private int z;
        private int r;
        private List<Integer> f;

        public Nucleus(CSVRecord record) {
            x = Math.round(Float.parseFloat(record.get(1)));
            y = Math.round(Float.parseFloat(record.get(2)));
            z = Math.round(Float.parseFloat(record.get(3)));
            r = (int) Math.round(Math.pow(Double.parseDouble(record.get(4)) * 0.75 / Math.PI, 1.0 / 3.0));
            f = new ArrayList<>();
            for (int i = 6; i < record.size(); i = i + 2) {
                f.add(Math.round(Float.parseFloat(record.get(i))));
            }
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getZ() {
            return z;
        }

        public int getR() {
            return r;
        }

        public int getF(int i) {
            return f.get(i);
        }

        public int sizeF() {
            return f.size();
        }
    }

    public static void main(String... args) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);

        final ImageJ ij = new ImageJ();
        ij.launch(args);

        int received = 0;
        boolean errors = false;

        while(received < 1 && !errors) {
            Future future = ij.command().run(Plotting.class, true);
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
