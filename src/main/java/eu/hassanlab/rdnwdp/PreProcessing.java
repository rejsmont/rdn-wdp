package eu.hassanlab.rdnwdp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ij.ImagePlus;
import io.scif.Format;
import io.scif.Metadata;
import io.scif.services.DatasetIOService;
import io.scif.services.FormatService;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImageJ;
import net.imagej.axis.Axes;
import net.imagej.axis.CalibratedAxis;
import net.imagej.ops.OpService;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.interpolation.randomaccess.FloorInterpolatorFactory;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.IntervalView;
import org.apache.commons.lang3.RandomStringUtils;
import org.scijava.command.Command;
import org.scijava.convert.ConvertService;
import org.scijava.log.LogLevel;
import org.scijava.log.LogService;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import sc.fiji.hdf5.HDF5ImageJ;
import org.yaml.snakeyaml.DumperOptions;
import net.imglib2.FinalInterval;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;


@Plugin(type = Command.class, menuPath = "Plugins>RDN-WDP>PreProcessing")
public class PreProcessing implements Command {

    @Parameter
    private DatasetIOService ioService;

    @Parameter
    private ConvertService convertService;

    @Parameter
    private OpService opService;

    @Parameter
    private DatasetService datasetService;

    @Parameter
    private FormatService formatService;

    @Parameter
    private LogService logService;

    @Parameter(label = "Input folder", style = "directory")
    private File inputFolder;

    @Parameter(label = "Output folder", style = "directory", required = false, persist = false)
    private File outputFolder;

    @Parameter(label = "Data format", style = "listBox", choices = {"HDF5", "Olympus OIF"})
    private String dataFormat = "Olympus OIF";

    @Parameter(label = "HDF5 datasets")
    private String datasetNameString = "/raw/dapi/channel0, /raw/venus/channel0, /raw/mcherry/channel0";

    @Parameter(label = "Alignment offsets")
    private String offsetString = "0, 0, 0";

    @Parameter(label = "Raw dataset prefix (output)")
    private String rawPrefix = "raw";

    @Parameter(label = "Aligned dataset prefix (output)")
    private String alignedPrefix = "aligned";

    @Parameter(label = "Number of threads", required = false)
    private Integer threads;


    @Override
    public void run() {
        String extension;
        if (dataFormat.equals("Olympus OIF")) {
            extension = ".oif";
        } else {
            extension = ".h5";
        }
        if (outputFolder == null) {
            outputFolder = inputFolder;
        }
        if (threads == null) {
            threads = Runtime.getRuntime().availableProcessors() - 2;
        }

        List<FileNameSet> samples = findSamples(extension);
        String[] offsetStringArray = offsetString.replaceAll("\\s","").split( "," );
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ExecutorCompletionService<Object> ecs = new ExecutorCompletionService<>(pool);

        double[] offsets = new double[offsetStringArray.length];
        for (int i = 0; i < offsetStringArray.length; i++) {
            offsets[i] = Double.parseDouble(offsetStringArray[i]);
        }

        for (FileNameSet sample : samples) {
            ecs.submit(new ImagePreprocessor(sample, offsets));
        }

        int submitted = samples.size();
        while (submitted > 0) {
            try {
                ecs.take().get();
            } catch (Exception e) {
                logService.log(LogLevel.WARN, "One of processing threads failed!");
                logService.log(LogLevel.ERROR, e.toString());
            }
            submitted--;
        }

        pool.shutdown();
    }

    private List<FileNameSet> findSamples(String extension) {
        List<File> list = new ArrayList<>();
        List<FileNameSet> samples = new ArrayList<>();
        String[] datasetNames = datasetNameString.replaceAll("\\s","").split(",");

        Path path = inputFolder.toPath();
        try {
            Files.walk(path).forEach(entry -> {
                File file = entry.toFile();
                if (file.isFile() && file.getPath().endsWith(extension)) {
                    list.add(file);
                }
            });
        } catch (IOException e) {
            logService.log(LogLevel.WARN, "Error when walking path " + path);
        }


        for (File file : list) {
            FileNameSet sample = null;
            if (extension.equals(".h5")) {
                sample = new HDF5FileNameSet(file, datasetNames);
            } else if (file.getPath().toLowerCase().contains("dapi")) {
                sample = new MATLFileNameSet(file, list, outputFolder.getPath());
            }
            if ((sample != null) && (sample.initialized)) {
                samples.add(sample);
            }
        }

        return samples;
    }

    class DatasetFile extends File {

        private String dataset;

        DatasetFile(String pathname, String dataset) {
            super(pathname);
            this.dataset = dataset;
        }

        DatasetFile(String parent, String child, String dataset) {
            super(parent, child);
            this.dataset = dataset;
        }

        DatasetFile(File parent, String child, String dataset) {
            super(parent, child);
            this.dataset = dataset;
        }

        DatasetFile(URI uri, String dataset) {
            super(uri);
            this.dataset = dataset;
        }

        String getDataset() {
            return dataset;
        }
    }


    class FileNameSet {
        Map<String, File> sources;
        File hdf5;
        File yml;
        boolean initialized;

        FileNameSet() {
            sources = new LinkedHashMap<>();
            hdf5 = null;
            yml = null;
            initialized = false;
        }
    }


    class MATLFileNameSet extends FileNameSet {

        MATLFileNameSet(File dapi, List<File> fileList, String baseDir) {
            super();
            findFiles(dapi, fileList, baseDir);
        }

        MATLFileNameSet(String sample, String disc, List<File> fileList, String baseDir) {
            super();
            String regex = "^.*?" + sample + "\\s*[-_]?\\s*?[Dd]is[ck]\\s*[-_]?\\s*" + disc + "\\s*[-_]?\\s*[Dd][Aa][Pp][Ii].*$";
            fileList.stream().filter(x -> x.getPath().matches(regex)).findAny()
                    .ifPresent(dapi -> findFiles(dapi, fileList, baseDir));
        }

        private void findFiles(File dapi, List<File> fileList, String baseDir) {
            File venus = new File(dapi.getPath().replace("DAPI", "Venus"));
            File mcherry = new File(dapi.getPath().replace("DAPI", "mCherry"));
            //Pattern pattern = Pattern.compile("^.*?(\\d+)\\s*[-_]?\\s*?[Dd]is[ck]\\s*[-_]?\\s*(\\d+).*$");
            Pattern pattern = Pattern.compile("^.*?(\\d+_?(\\w*?))?\\s*[-_]?\\s*?[Dd]is[ck]\\s*[-_]?\\s*(\\d+\\w?).*$");
            Matcher match = pattern.matcher(dapi.getPath());
            if (match.find()) {
                String sample = match.group(1);
                String disc = match.group(3);

                String venusPath = venus.getPath();
                if (fileList.stream().noneMatch(x -> x.getPath().equals(venusPath))) {
                    String regex = "^.*?" + sample + "\\s*[-_]?\\s*?[Dd]is[ck]\\s*[-_]?\\s*" + disc + "\\s*[-_]?\\s*[Vv][Ee][Nn][Uu][Ss].*$";
                    venus = fileList.stream().filter(x -> x.getPath().matches(regex)).findAny().orElse(null);
                }

                String mcherryPath = mcherry.getPath();
                if (fileList.stream().noneMatch(x -> x.getPath().equals(mcherryPath))) {
                    String regex = "^.*?" + sample + "\\s*[-_]?\\s*?[Dd]is[ck]\\s*[-_]?\\s*" + disc + "\\s*[-_]?\\s*[Mm][Cc][Hh][Ee][Rr][Rr]?[Yy].*$";
                    mcherry = fileList.stream().filter(x -> x.getPath().matches(regex)).findAny().orElse(null);
                }

                if (venus != null && mcherry != null) {
                    String random_id = id_generator();
                    String hdf5BaseName = sample + "_disc_" + disc + "_" + random_id + ".h5";
                    String ymlBaseName = sample + "_disc_" + disc + "_" + random_id + ".yml";
                    hdf5 = new File(baseDir, hdf5BaseName);
                    yml = new File(baseDir, ymlBaseName);
                    sources.put("dapi", dapi);
                    sources.put("venus", venus);
                    sources.put("mcherry", mcherry);
                    initialized = true;
                }
            }
            if (! initialized) {
                logService.log(LogLevel.WARN, "Failed to find data for " + dapi);
            }
        }

        private String id_generator(int length, String charset) {
            return RandomStringUtils.random(length, charset.toCharArray());
        }

        private String id_generator(int length) {
            return RandomStringUtils.random(length, true, true);
        }

        private String id_generator() {
            return RandomStringUtils.random(6, true, true);
        }
    }


    class HDF5FileNameSet extends FileNameSet {

        HDF5FileNameSet(File hdf5file, String[] datasets) {
            super();
            sources.put("dapi", new DatasetFile(hdf5file.getPath(), datasets[0]));
            sources.put("venus", new DatasetFile(hdf5file.getPath(), datasets[1]));
            sources.put("mcherry", new DatasetFile(hdf5file.getPath(), datasets[2]));
            hdf5 = hdf5file;
            initialized = true;
        }
    }


    class SourceImageSet {

        private Map<String, Dataset> images;
        private boolean initialized;

        SourceImageSet(FileNameSet names) {
            images = new LinkedHashMap<>();
            initialized = openImages(names);
        }

        private boolean openImages(FileNameSet names) {
            names.sources.forEach((k, v) -> {
                Dataset image;
                if (v instanceof DatasetFile) {
                    DatasetFile d = (DatasetFile) v;
                    ImagePlus imp = HDF5ImageJ.hdf5read(v.getPath(), d.getDataset(), "zyx");
                    image = convertService.convert(imp, Dataset.class).duplicate();
                    imp.close();
                } else {
                    try {
                        image = ioService.open(v.getPath());
                    } catch (Exception e) {
                        image = null;
                    }
                }

                if (checkImage(image)) {
                    images.put(k, image);
                }
            });

            return names.sources.size() == images.size();
        }

        private boolean checkImage(Dataset image) {
            if (image != null) {
                int d = image.dimensionIndex(Axes.Z);
                return d >= 0 && image.max(d) > 20;
            }
            return false;
        }

        Dataset getReference() {
            return images.get("dapi");
        }
    }


    class MetadataSet {
        private Map<String, Map<String, Object>> metadata;
        private boolean initialized;

        MetadataSet(FileNameSet names) {
            metadata = new LinkedHashMap<>();
            initialized = true;
            names.sources.forEach((k, v) -> {
                Map<String, Object> meta = readMetadata(v);
                if (meta != null) {
                    metadata.put(k, meta);
                } else {
                    initialized = false;
                }
            });
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> readMetadata(File image) {
            if (image == null) {
                return null;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            try {
                Format format = formatService.getFormat(image.getPath());
                Metadata metadata = format.createParser().parse(image);
                metadata.getTable().forEach((key, value) -> {
                    Pattern pattern = Pattern.compile("^\\[(.*?)\\]\\s*(.*)$");
                    Matcher match = pattern.matcher(key);
                    if (match.find()) {
                        String group = match.group(1);
                        key = match.group(2);
                        Map<String, Object> entry;
                        if (! meta.containsKey(group)) {
                            entry = new LinkedHashMap<>();
                            meta.put(group, entry);
                        } else {
                            try {
                                entry = (Map<String, Object>) meta.get(group);
                            } catch (ClassCastException e) {
                                entry = null;
                            }
                        }
                        if (entry != null) {
                            entry.put(key, value);
                        }
                    } else {
                        meta.put(key, value);
                    }
                });
                return meta;
            } catch (Exception e) {
                return null;
            }
        }

        String getYaml() {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
            options.setPrettyFlow(true);
            options.setIndent(4);
            Yaml yaml = new Yaml(options);
            return yaml.dump(metadata);
        }
    }

    class ImageFusion<T extends RealType<T>> {

        private List<RandomAccessibleInterval<T>> images;
        private Dataset reference;
        private boolean initialized;


        ImageFusion(SourceImageSet sourceSet) {
            if (sourceSet.initialized) {
                Map<String, Dataset> sources = sourceSet.images;
                reference = sourceSet.getReference();
                images = new ArrayList<>();

                sources.forEach((k, image) -> {
                    int d = image.dimensionIndex(Axes.CHANNEL);
                    RandomAccessibleInterval<T> scaled = scaleImage(image);
                    if (d >= 0) {
                        for (long c = scaled.min(d); c <= scaled.max(d); c++) {
                            IntervalView<T> channel = opService.transform().hyperSliceView(scaled, d, c);
                            images.add(channel);
                        }
                    } else {
                        images.add(scaled);
                    }
                });
                initialized = true;
            } else {
                initialized = false;
            }
        }

        private RandomAccessibleInterval<T> scaleImage(Dataset image) {
            List<Double> scales = new ArrayList<>();
            boolean resize = false;
            for (int d = 0; d < image.numDimensions(); d++) {
                CalibratedAxis axis = image.axis(d);
                if (axis.type().isSpatial()) {
                    int bd = reference.dimensionIndex(axis.type());
                    double scale = (reference.max(bd) - reference.min(bd) + 1.0) / (image.max(d) - image.min(d) + 1.0);
                    resize = resize || (scale != 1.0);
                    scales.add(scale);
                } else {
                    scales.add(1.0);
                }
            }

            return resize ? opService.transform().scaleView((RandomAccessibleInterval<T>) image,
                    scales.stream().mapToDouble(d -> d).toArray(), new FloorInterpolatorFactory<>()) : (RandomAccessibleInterval<T>) image;
        }


        Dataset getAlignedImage(double[] offsets) {
            if (! initialized) {
                return null;
            }

            long[] zshifts;
            int zidx = reference.dimensionIndex(Axes.Z);
            CalibratedAxis zaxis = reference.axis(zidx);

            if (offsets.length == images.size()) {
                zshifts = Arrays.stream(offsets).mapToLong(d -> Math.round(zaxis.rawValue(d))).toArray();
            } else {
                zshifts = new long[images.size()];
            }

            OptionalLong ex = Arrays.stream(zshifts).min();
            long zsmin = (ex.isPresent() && ex.getAsLong() < 0) ? ex.getAsLong() : 0;
            ex = Arrays.stream(zshifts).max();
            long zsmax = (ex.isPresent() && ex.getAsLong() > 0) ? ex.getAsLong() : 0;
            long zslices = reference.max(zidx) + zsmax - zsmin;

            List<RandomAccessibleInterval<T>> stack = new ArrayList<>();
            RealType zero = reference.firstElement().createVariable();
            zero.setZero();

            IntStream.range(0, images.size()).forEach(i -> {
                ExtendedRandomAccessibleInterval<T, RandomAccessibleInterval<T>> image = opService.transform().extendZeroView(images.get(i));
                List<Long> imin = new ArrayList<>();
                List<Long> imax = new ArrayList<>();

                for (int d = 0; d < image.numDimensions(); d++) {
                    long dmin, dmax;
                    if ((d == zidx) || ((image.numDimensions() <= zidx) && (d == (zidx - 1)))) {
                        dmin = reference.min(zidx) + zshifts[i] - zsmax;
                        dmax = dmin + zslices;
                    } else {
                        dmin = reference.min(d);
                        dmax = reference.max(d);
                    }
                    imin.add(dmin);
                    imax.add(dmax);
                }
                Interval interval = new FinalInterval(
                        imin.stream().mapToLong(l -> l).toArray(),
                        imax.stream().mapToLong(l -> l).toArray());

                logService.log(LogLevel.INFO, "Interval: " + imin + " - " + imax);
                stack.add(opService.transform().offsetView(image, interval));
            });

            return createResult(opService.transform().stackView(stack));
        }

        Dataset getImage() {
            return initialized ? createResult(opService.transform().stackView(images)) : null;
        }

        private Dataset createResult(RandomAccessibleInterval<T> image) {
            Dataset result = datasetService.create(image);
            result.setAxis(reference.axis(reference.dimensionIndex(Axes.X)), 0);
            result.setAxis(reference.axis(reference.dimensionIndex(Axes.Y)), 1);
            result.setAxis(reference.axis(reference.dimensionIndex(Axes.Z)), 2);
            result.axis(3).setType(Axes.CHANNEL);
            return result;
        }
    }

    class ImagePreprocessor implements Callable<Object> {

        private double[] offsets;
        private FileNameSet files;
        private boolean initialized;
        private SourceImageSet sources;
        private MetadataSet metadata;
        private ImageFusion processed;

        ImagePreprocessor(FileNameSet files, double[] offsets) {
            this.offsets = offsets;
            this.files = files;
            initialized = false;
            sources = null;
            metadata = null;
            processed = null;
        }

        void initialize() {
            if (files != null) {
                sources = new SourceImageSet(files);
                if (files.yml != null) {
                    metadata = new MetadataSet(files);
                }
                if (sources != null) {
                    processed = new ImageFusion(sources);
                    initialized = true;
                }
            }
        }

        void saveMetadata() {
            if ((! initialized) || (files.yml == null) || (! metadata.initialized)) {
                return;
            }
            logService.log(LogLevel.INFO, "Exporting metadata " + files.yml);

            try (PrintWriter out = new PrintWriter(files.yml)) {
                out.println(metadata.getYaml());
            } catch (Exception e) {
                logService.log(LogLevel.WARN, "Writing metadata failed!");
            }

            logService.log(LogLevel.INFO, "Metadata export done.");
        }

        void saveRaw() {
            if ((! initialized) || (! sources.initialized)) {
                return;
            }
            logService.log(LogLevel.INFO, "Exporting raw data " + files.hdf5);

            sources.images.forEach((name, image) -> {
                ImagePlus imp = convertService.convert(image, ImagePlus.class);
                if (image.dimension(Axes.CHANNEL) > 1) {
                    HDF5ImageJ.hdf5write(imp, files.hdf5.getPath(), "/" + rawPrefix + "/" + name + "/channel{c}", "", "%d", 0, false);
                } else {
                    HDF5ImageJ.hdf5write(imp, files.hdf5.getPath(), "/" + rawPrefix + "/" + name, "", "", 0, false);
                }
                imp.close();
            });

            logService.log(LogLevel.INFO, "Raw data export done.");
        }

        void saveAligned() {
            if ((! initialized) || (! processed.initialized)) {
                return;
            }
            logService.log(LogLevel.INFO, "Exporting aligned data " + files.hdf5);
            Dataset image = processed.getAlignedImage(offsets);
            if (image != null) {
                ImagePlus imp = convertService.convert(image, ImagePlus.class);
                HDF5ImageJ.hdf5write(imp, files.hdf5.getPath(), "/" + alignedPrefix + "/channel{c}", "", "%d", 0, false);
                imp.close();
            }
            logService.log(LogLevel.INFO, "Aligned data export done.");
        }

        public Object call() {
            logService.log(LogLevel.INFO, "Initializing...");
            initialize();
            logService.log(LogLevel.INFO, "Saving metadata...");
            saveMetadata();
            logService.log(LogLevel.INFO, "Saving raw images...");
            saveRaw();
            logService.log(LogLevel.INFO, "Saving aligned images...");
            saveAligned();
            logService.log(LogLevel.INFO, "Done!");
            return this;
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
            Future future = ij.command().run(PreProcessing.class, true);
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
