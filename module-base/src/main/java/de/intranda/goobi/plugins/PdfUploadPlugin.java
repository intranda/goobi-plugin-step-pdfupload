package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.plugin.interfaces.AbstractStepPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IStepPlugin;
import org.primefaces.event.FileUploadEvent;

import de.intranda.goobi.plugins.util.PdfFile;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.dl.Reference;

@PluginImplementation

public class PdfUploadPlugin extends AbstractStepPlugin implements IStepPlugin, IPlugin {

    private static final String PLUGIN_NAME = "intranda_step_pdfUpload";
    private static final Logger logger = Logger.getLogger(PdfUploadPlugin.class);

    private Process process;
    private Fileformat fileformat;
    private String imagefolder;

    private static String ALLOWED_CHARACTER = "[A-Za-z0-9öüäß\\-_.]";

    private List<String> allowedFileExtensions;

    private String comment;
    private PdfFile currentFile;
    private List<PdfFile> uploadedFiles = new ArrayList<>();

    private Prefs prefs;
    private DocStructType pageType;
    private MetadataType physType;
    private MetadataType logType;

    private DocStruct logical;
    private DocStruct physical;

    @Override
    public void initialize(Step step, String returnPath) {

        super.returnPath = returnPath;
        super.myStep = step;
        process = myStep.getProzess();

        prefs = process.getRegelsatz().getPreferences();
        pageType = prefs.getDocStrctTypeByName("page");
        physType = prefs.getMetadataTypeByName("physPageNumber");
        logType = prefs.getMetadataTypeByName("logicalPageNumber");

        try {
            fileformat = process.readMetadataFile();
            physical = fileformat.getDigitalDocument().getPhysicalDocStruct();
            logical = fileformat.getDigitalDocument().getLogicalDocStruct();
            if (logical.getType().isAnchor()) {
                logical = logical.getAllChildren().get(0);
            }
        } catch (Exception e) {
            logger.error(e);
        }

        XMLConfiguration config = ConfigPlugins.getPluginConfig(PLUGIN_NAME);
        String folder = config.getString("folder", "derivate");
        try {
            if ("master".equalsIgnoreCase(folder)) {
                imagefolder = process.getImagesOrigDirectory(false);
            } else if ("derivate".equalsIgnoreCase(folder)) {
                imagefolder = process.getImagesTifDirectory(false);
            } else if ("source".equalsIgnoreCase(folder)) {
                imagefolder = process.getSourceDirectory() + File.separator;
            } else {
                Helper.setFehlerMeldung("unknownFolderConfigurationError");
                imagefolder = process.getImagesTifDirectory(false);
            }
        } catch (Exception e) {
            logger.error(e);
        }

        File f = new File(imagefolder);
        if (!f.isDirectory()) {
            f.mkdirs();
        }

        allowedFileExtensions = Arrays.asList(config.getStringArray("extensions.extension"));
        if (allowedFileExtensions == null || allowedFileExtensions.isEmpty()) {
            allowedFileExtensions = new ArrayList<>();
            allowedFileExtensions.add("pdf");
        }

        // load old data from mets file/filesystem

        if (physical.getAllChildren() != null && !physical.getAllChildren().isEmpty()) {
            for (DocStruct page : physical.getAllChildren()) {
                String filename = page.getImageName();
                String comment = "";
                if (filename.contains(File.separator)) {
                    filename = filename.substring(filename.lastIndexOf(File.separator) + 1);
                }
                List<? extends Metadata> pageNoMetadata = page.getAllMetadataByType(logType);
                if (pageNoMetadata != null && !pageNoMetadata.isEmpty()) {
                    comment = pageNoMetadata.get(0).getValue();
                    if ("uncounted".equals(comment)) {
                        comment = "";
                    }
                }

                File file = new File(imagefolder + filename);
                PdfFile pdf = new PdfFile(filename, comment, file.length());
                uploadedFiles.add(pdf);
            }
        }

    }

    public void handleFileUpload(FileUploadEvent event) {
        try {
            copyFile(event.getFile().getFileName(), event.getFile().getInputStream());

        } catch (IOException e) {
            logger.error(e);
        }

    }

    public void copyFile(String fileName, InputStream in) throws IOException {

        if (!checkExtension(fileName)) {
            Helper.setFehlerMeldung("fileTypeNotAllowed");
            return;
        }

        if (fileName.startsWith(".")) {
            fileName = fileName.substring(1);
        }
        if (fileName.contains("/")) {
            fileName = fileName.substring(fileName.lastIndexOf("/") + 1);
        }
        if (fileName.contains("\\")) {
            fileName = fileName.substring(fileName.lastIndexOf("\\") + 1);
        }

        fileName = fileName.replace(" ", "_");

        String replacement = fileName.replaceAll(ALLOWED_CHARACTER, "");
        if (!replacement.isEmpty()) {

            Helper.setFehlerMeldung("invalidCharacter");
            return;
        }

        // remove any folder information or '..'
        Path filePath = Path.of(fileName).getFileName();

        Path tmpFile = Paths.get(imagefolder, filePath.toString());

        // Check that filename does not link outside the folder
        if (!tmpFile.normalize().startsWith(imagefolder)) {
            throw new IOException("Invalid filename");
        }

        OutputStream out = null;
        File f = tmpFile.toFile();
        try {

            // write the inputStream to a FileOutputStream
            out = new FileOutputStream(f);

            int read = 0;
            byte[] bytes = new byte[1024];

            while ((read = in.read(bytes)) != -1) {
                out.write(bytes, 0, read);
            }
            out.flush();
        } catch (IOException e) {
            logger.error(e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    logger.error(e);
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    logger.error(e);
                }
            }

        }
        PdfFile file = new PdfFile(fileName, comment, f.length());

        uploadedFiles.add(file);

        try {
            // add to mets file
            DocStruct page = fileformat.getDigitalDocument().createDocStruct(pageType);
            int order = 1;
            if (physical.getAllChildren() != null && !physical.getAllChildren().isEmpty()) {

                order = physical.getAllChildren().size() + 1;
            }

            Metadata phys = new Metadata(physType);
            phys.setValue("" + order);
            page.addMetadata(phys);

            Metadata log = new Metadata(logType);
            log.setValue(comment);
            page.addMetadata(log);
            // add file name

            page.setImageName(name);
            physical.addChild(page);

            logical.addReferenceTo(page, "logical_physical");

        } catch (Exception e) {
            logger.error(e);
        }

        try {
            process.writeMetadataFile(fileformat);
        } catch (Exception e) {
            logger.error(e);
        }

        comment = "";

    }

    private boolean checkExtension(String basename) {
        for (String extension : allowedFileExtensions) {
            if (basename.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean execute() {
        return false;
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.PART;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public String getTitle() {
        return PLUGIN_NAME;
    }

    @Override
    public String getDescription() {
        return PLUGIN_NAME;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public PdfFile getCurrentFile() {
        return currentFile;
    }

    public void setCurrentFile(PdfFile currentFile) {
        this.currentFile = currentFile;
    }

    public void setUploadedFiles(List<PdfFile> uploadedFiles) {
        this.uploadedFiles = uploadedFiles;
    }

    public List<PdfFile> getUploadedFiles() {
        return uploadedFiles;
    }

    public void deleteFile() {
        // remove from list
        if (uploadedFiles.contains(currentFile)) {
            uploadedFiles.remove(currentFile);
        }
        // delete file
        File f = new File(imagefolder + currentFile.getFilename());
        FileUtils.deleteQuietly(f);

        //  remove from mets file

        try {
            for (DocStruct child : physical.getAllChildren()) {
                if (child.getImageName().endsWith(currentFile.getFilename())) {
                    fileformat.getDigitalDocument().getFileSet().removeFile(child.getAllContentFiles().get(0));

                    fileformat.getDigitalDocument().getPhysicalDocStruct().removeChild(child);
                    List<Reference> refs = new ArrayList<>(child.getAllFromReferences());
                    for (ugh.dl.Reference ref : refs) {
                        ref.getSource().removeReferenceTo(child);
                    }
                    break;
                }
            }
            if (physical.getAllChildren() != null && !physical.getAllChildren().isEmpty()) {
                int order = 1;
                for (DocStruct child : physical.getAllChildren()) {
                    // FIX phys order
                    Metadata pageNo = child.getAllMetadataByType(physType).get(0);
                    pageNo.setValue(String.valueOf(order));
                    order++;
                }
            }
            process.writeMetadataFile(fileformat);
        } catch (Exception e) {
            logger.error(e);
        }
    }

}
