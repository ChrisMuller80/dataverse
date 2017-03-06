package edu.harvard.iq.dataverse.engine.command.impl;

import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.dataset.DatasetThumbnail;
import edu.harvard.iq.dataverse.dataset.DatasetUtil;
import edu.harvard.iq.dataverse.engine.command.AbstractCommand;
import edu.harvard.iq.dataverse.engine.command.CommandContext;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.RequiredPermissions;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.exception.IllegalCommandException;
import edu.harvard.iq.dataverse.util.FileUtil;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

@RequiredPermissions(Permission.EditDataset)
public class UpdateDatasetThumbnailCommand extends AbstractCommand<DatasetThumbnail> {

    private static final Logger logger = Logger.getLogger(UpdateDatasetThumbnailCommand.class.getCanonicalName());

    private final Dataset dataset;
    private final UserIntent userIntent;
    /**
     * @todo make this a long rather than a Long.
     */
    private final Long dataFileIdSupplied;
    private final InputStream inputStream;
    private String stagingFilePath;

    public enum UserIntent {
        userHasSelectedDataFileAsThumbnail, userWantsToRemoveThumbnail, userWantsToUseNonDatasetFile
    };

    public UpdateDatasetThumbnailCommand(DataverseRequest aRequest, Dataset theDataset, UserIntent theUserIntent, Long theDataFileIdSupplied, InputStream theInputStream, String theStagingFilePath) {
        super(aRequest, theDataset);
        dataset = theDataset;
        userIntent = theUserIntent;
        inputStream = theInputStream;
        stagingFilePath = theStagingFilePath;
        this.dataFileIdSupplied = theDataFileIdSupplied;
    }

    @Override
    public DatasetThumbnail execute(CommandContext ctxt) throws CommandException {
        if (userIntent == null) {
            throw new IllegalCommandException("No changes to save.", this);
        }
        switch (userIntent) {

            case userHasSelectedDataFileAsThumbnail:
                if (dataFileIdSupplied == null) {
                    throw new CommandException("A file was not selected to be the new dataset thumbnail.", this);
                }
                DataFile datasetFileThumbnailToSwitchTo = ctxt.files().find(dataFileIdSupplied);
                if (datasetFileThumbnailToSwitchTo == null) {
                    throw new CommandException("Could not find file based on id supplied: " + dataFileIdSupplied + ".", this);
                }
                Dataset ds1 = ctxt.datasets().setDataFileAsThumbnail(dataset, datasetFileThumbnailToSwitchTo);
                DatasetThumbnail datasetThumbnail = ds1.getDatasetThumbnail(ctxt.datasetVersion(), ctxt.files());
                if (datasetThumbnail != null) {
                    DataFile dataFile = datasetThumbnail.getDataFile();
                    if (dataFile != null) {
                        if (dataFile.getId().equals(dataFileIdSupplied)) {
                            return datasetThumbnail;
                        } else {
                            throw new CommandException("Dataset thumnail is should be based on file id " + dataFile.getId() + " but instead it is " + dataFileIdSupplied + ".", this);
                        }
                    }
                } else {
                    throw new CommandException("Dataset thumnail is unexpectedly absent.", this);
                }

            case userWantsToUseNonDatasetFile:
                if (stagingFilePath == null) {
                    // If the stagingFilePath is null we must be getting the file from the InputStream.
                    File uploadedFile;
                    try {
                        uploadedFile = FileUtil.inputStreamToFile(inputStream);
                    } catch (IOException ex) {
                        throw new CommandException("Problem uploading file: " + ex, this);
                    }
                    long uploadLogoSizeLimit = ctxt.systemConfig().getUploadLogoSizeLimit();
                    if (uploadedFile.length() > uploadLogoSizeLimit) {
                        throw new IllegalCommandException("File is larger than maximum size: " + uploadLogoSizeLimit + ".", this);
                    }
                    JsonObjectBuilder jsonObjectBuilder = ctxt.datasets().writeDatasetLogoToStagingArea(dataset, uploadedFile);
                    JsonObject jsonObject = jsonObjectBuilder.build();
                    try {
                        stagingFilePath = jsonObject.getString(DatasetUtil.stagingFilePathKey);
                    } catch (NullPointerException ex1) {
                        String errorDetails = null;
                        try {
                            errorDetails = jsonObject.getString(DatasetUtil.stagingFileErrorKey);
                        } catch (NullPointerException ex2) {
                        }
                        String error = "Could not move thumbnail from staging area to final destination: " + errorDetails;
                        logger.info(error);
                    }
                }
                Dataset datasetWithNewThumbnail = ctxt.datasets().moveDatasetLogoFromStagingToFinal(dataset, stagingFilePath);
                if (datasetWithNewThumbnail != null) {
                    return datasetWithNewThumbnail.getDatasetThumbnail(ctxt.datasetVersion(), ctxt.files());
                } else {
                    return null;
                }

            case userWantsToRemoveThumbnail:
                Dataset ds2 = ctxt.datasets().removeDatasetThumbnail(dataset);
                DatasetThumbnail datasetThumbnail2 = ds2.getDatasetThumbnail(ctxt.datasetVersion(), ctxt.files());
                if (datasetThumbnail2 == null) {
                    return null;
                } else {
                    throw new CommandException("User wanted to remove the thumbnail it still has one!", this);
                }
            default:
                throw new IllegalCommandException("Whatever you are trying to do to the dataset thumbnail is not supported.", this);
        }
    }

}
