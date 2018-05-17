package eu.hassanlab.rdnwdp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ij.ImagePlus;
import ij.plugin.filter.RankFilters;
import ij.process.ImageProcessor;
import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel2D;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.ops.OpService;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.convert.ConvertService;
import org.scijava.log.LogLevel;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.slf4j.LoggerFactory;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.Future;

@Plugin(type = Command.class, menuPath = "Plugins>RDN-WDP>Calculate Shift")
public class ShiftCalculator implements Command {

    @Parameter
    private LogService logService;

    @Parameter
    private ConvertService convertService;

    @Parameter
    private OpService opService;

    @Parameter(label = "Reference image")
    private Dataset reference;

    @Parameter(label = "Input image")
    private Dataset input;

    @Parameter(label = "Sampling (0 = use all slices)")
    private int sampling = 0;

    @Parameter(label = "Strict", required = false)
    private boolean strict = false;

    @Parameter(type = ItemIO.OUTPUT)
    private Alignment result;

    @Override
    @SuppressWarnings("unchecked")
    public void run() {
        result = align(reference, input);
        logService.log(LogLevel.INFO,"Final result: " + result);
    }

    private Alignment align(Dataset reference, Dataset sample) {
        final FloatArray2DSIFT.Param siftParam = new FloatArray2DSIFT.Param();
        final FloatArray2DSIFT sift = new FloatArray2DSIFT(siftParam);
        final SIFT ijSIFT = new SIFT(sift);

        final List< Feature > fs1 = new ArrayList<>();
        final List< Feature > fs2 = new ArrayList<>();

        RankFilters filter = new RankFilters();

        ImagePlus imp1 = convertService.convert(reference, ImagePlus.class);
        ImagePlus imp2 = convertService.convert(sample, ImagePlus.class);

        int maxslices = imp1.getNSlices();

        if ((strict) && (imp2.getNSlices() != imp1.getNSlices())) {
            return new Alignment(0,0,0,0);
        }

        if (maxslices > imp2.getNSlices()) {
            maxslices = imp2.getNSlices();
        }

        if (sampling == 0)
            sampling = maxslices;

        Alignment[] shifts = new Alignment[sampling];

        for (int i = 0; i < sampling; i++) {

            int slice;
            if (sampling == maxslices) {
                slice = i + 1;
            } else {
                slice = new Random().nextInt(maxslices) + 1;
            }

            ImageProcessor ip1 = imp1.getStack().getProcessor((int) Math.ceil(slice));
            ImageProcessor ip2 = imp2.getStack().getProcessor((int) Math.ceil(slice));

            ijSIFT.extractFeatures( ip1, fs1 );
            ijSIFT.extractFeatures( ip2, fs2 );

            final Vector< PointMatch > candidates =
                    FloatArray2DSIFT.createMatches( fs2, fs1, 1.5f, null, Float.MAX_VALUE, 0.75f );
            final Vector< PointMatch > inliers = new Vector<>();

            AbstractAffineModel2D model = new TranslationModel2D();

            boolean modelFound;
            try
            {
                modelFound = model.filterRansac(
                        candidates,
                        inliers,
                        1000,
                        25.0f,
                        0.005f );
            }
            catch (final Exception e)
            {
                modelFound = false;
                logService.log(LogLevel.WARN, e.getMessage());
            }
            if (modelFound) {
                AffineTransform transform = model.createAffine();
                shifts[i] = new Alignment(Math.round(transform.getTranslateX()), Math.round(transform.getTranslateY()), model.getCost());
                logService.log(LogLevel.INFO, "i: " + slice + ", " + shifts[i]);
            }
        }

        int counts[] = new int[sampling];
        double costs[] = new double[sampling];
        int total = 0;
        int i = 0;
        int j = 0;
        int k = 0;
        int l = 0;

        while (total < sampling) {
            if (j >= sampling) {
                i = k;
                j = k;
                k = 0;
            }
            if (shifts[i] == null) {
                total++;
                i++;
                j = i;
                continue;
            }
            if (shifts[j] == null) {
                j++;
                continue;
            }
            if ((shifts[i].getX() == shifts[j].getX()) && (shifts[i].getY() == shifts[j].getY())) {
                counts[i] += 1;
                costs[i] += shifts[j].getCost();
                if (counts[i] > counts[l]) {
                    l = i;
                }
                total++;
            } else if (k == 0) {
                k = j;
            }
            j++;
        }

        imp1.close();
        imp2.close();

        return new Alignment(shifts[l].getX(), shifts[l].getY(), costs[l] / counts[l], counts[l]);
    }

    public static void main(String... args) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);

        final ImageJ ij = new ImageJ();
        ij.launch(args);

        int received = 0;
        boolean errors = false;

        Dataset ds1, ds2;

        try {
            ds1 = (Dataset) ij.io().open("/Users/radoslaw.ejsmont/Desktop/alignment/mCherry.tif");
            ds2 = (Dataset) ij.io().open("/Users/radoslaw.ejsmont/Desktop/alignment/mCherry-cropped.tif");

            while(received < 1 && !errors) {
                Future future = ij.command().run(ShiftCalculator.class, true,
                        "reference", ds1,
                        "input", ds2, "sampling", 0);
                try {
                    CommandModule result = (CommandModule) future.get();
                    ij.log().log(LogLevel.INFO, "Result: " + result.getOutput("result"));
                    received++;
                }
                catch(Exception e) {
                    errors = true;
                }
            }
        } catch (Exception e) {};
    }

    class Alignment {
        int x;
        int y;
        double cost;
        int count;

        Alignment(int x, int y, double cost, int count) {
            this.x = x;
            this.y = y;
            this.cost = cost;
            this.count = count;
        }

        Alignment(long x, long y, double cost, int count) {
            this.x = (int) x;
            this.y = (int) y;
            this.cost = cost;
            this.count = count;
        }

        Alignment(long x, long y, double cost) {
            this.x = (int) x;
            this.y = (int) y;
            this.cost = cost;
            this.count = 1;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public double getCost() {
            return cost;
        }

        @Override
        public String toString() {
            return "[" + x + "," + y + "," + cost + "," + count + "]";
        }
    }
}
