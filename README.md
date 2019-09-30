# Single-cell resolution view of the transcriptional landscape of developing Drosophila eye.
### Image processing tools for the RDN-WDP project

This repository contains the Fiji scripts and plugins used segment the raw images and create the
nuclear point clouds from the RDP-WDP project. See the [paper repository](https://github.com/HassanLab/rdn-wdp-paper)
for the license and the software description.

---

## Data reproduction
First, download all data from the [data repository](https://github.com/HassanLab/rdn-wdp-data).
We suggest placing the folders in the `rdn-wdp-data` directory. Warning: the complete datasets
are quite large (>1TB).

### Dependencies
Dependencies for plugins can be obtained via Maven. For the Jython scripts used for classifier training
and the segmentation, please make sure that your Fiji installation is current (ImageJ2 is required)
and includes the following plugins, at minimum:

* Jython scripting (usually included by default)
* [HDF5](https://imagej.net/HDF5_Vibez)
* [trainableSegmentation](https://imagej.net/Trainable_Weka_Segmentation)
* [ImageScience](https://imagej.net/ImageScience)
* [3D Image Suite](https://imagejdocu.tudor.lu/doku.php?id=plugin:stacks:3d_ij_suite:start)

### Building
The package with all plugins can be built using Maven. The resulting jar should be placed in fiji
plugins folder. Jython scripts can be run from directly from the segmentation directory.

### Image preprocessing
The raw data was acquired in the `.oif` (Olympus Image File) format. The data available in the
repository is already pre-processed and includes pixel-perfect copies of the images from these files
as well as complete metadata. If you really insist on repeating the pre-processing, please contact R.E.
via email to obtain the `.oif` files.

The preprocessing in done using the [PreProcessing](src/main/java/eu/hassanlab/rdnwdp/PreProcessing.java) fiji plugin.
The plugin can be run from Fiji Menu `Plugins>RDN-WDP>PreProcessing` after installation or from the command line:

`fiji --ij2 --headless --run PreProcessing 'inputFolder="value",outputFolder="value"'`

the available parameters are (see source code for details):

* `inputFolder` - path to the folder containing input files
* `outputFolder` - path to the output folder
* `dataFormat` - input format ("HDF5" or "Olympus OIF")
* `datasetNameString` - names of raw datasets if using HDF5 input
* `offsetString` - z-offset to apply if channels are z-shifted
* `rawPrefix` - prefix to use for raw dataset names in output
* `alignedPrefix` - prefix to use for aligned datasets in output
* `threads` - number of threads to run with

### Classifier training
The classifier is trained on label files created with Ilastik and the input images.
The [training.py](segmentation/training.py) Jython script is used for training. Please beware
that training is very computationally intensive. You should run this on a compute node
with a lot of cores (we used a 16 core processor) and large (64GB) amount of RAM.

`fiji --ij2 --headless --run training.py 'inputdir="value",classifier="value"'`

the available parameters are (see source code for details):

* `inputdir` - directory containing `_Label.h5` files from Ilastik and the corresponding `.tiff` images.
* `classifier` - path and filename where to save the classifier
* `featurestr` - the list of features to compute
* `smin` - minimum feature radius
* `smax` - maximum feature radius

### Pixel classification
The classification is done using the [classification-oneshot-split.py](segmentation/classification-oneshot-split.py)
Jython script. Please beware that training is extremely computationally intensive. You should run
this on a compute node with a lot of cores (we used a 28 core processor) and large
(>64GB, best 128GB or even 256GB) amount of RAM. The RAM can be traded for compute time using
split processing. Processing a single image using our setup took ~10-20'.

`fiji --ij2 --headless --run classification-oneshot-split.py 'classifier="value",imagefile="value"'`

the available parameters are (see source code for details):

* `classifier` - path and filename of the classifier
* `imagefile` - the HDF image file containing preprocessed data
* `dataset` - the dataset to segment
* `hsplit` - how many horizontal tiles should the image be divided into to save RAM
* `vsplit` - how many vertical tiles should the image be divided into to save RAM
* `overlap` - overlap between tiles (in pixels)

### Image segmentation and quantification
The classification is done using the [dog-segment-pmap.py](segmentation/dog-segment-pmap.py)
Jython script. This script also generates the point cloud CSV file.
Please beware that this step is quite computationally expensive.

`fiji --ij2 --headless --run dog-segment-pmap.py 'inputfile="value",outputfile="value"'`

the available parameters are (see source code for details):

* `inputfile` - input HDF5 file
* `outputfile` - the output HDF5 file (can be the same as input)
* `dsegm` - dataset to segment (usually the probability map dataset)
* `dmeas` - datasets containing signals to quantify
* `sigma` - high sigma of the DoG filter
* `div` - DoG sigma ratio
* `radius` - radius of the local maxima filter
* `thresh` - probability threshold for watershed mask
* `cutoff` - local maxima cutoff value

### Generating point clouds (standalone)
Nuclear point clouds can also be generated from pre-segmented images using the
[Quantification](src/main/java/eu/hassanlab/rdnwdp/Quantification.java) fiji plugin.
The plugin can be run from Fiji Menu `Plugins>RDN-WDP>Quantification` after installation or from the command line:

`fiji --ij2 --headless --run Quantification 'inputFolder="value",outputFolder="value"'`

the available parameters are (see source code for details):

* `inputFolder` - path to the folder containing segmented HDF5 files
* `outputFolder` - path to the folder where point cloud CSV files will be saved
* `labelDataset` - dataset containing object labels
* `quantNameString` - datasets containing signals to quantify
* `threads` - number of threads to run with

---

In case of problems with running these programs or if you find a bug, please contact R.E.
via email (addres availabale in the manuscript text), or create an issue in this repository.
