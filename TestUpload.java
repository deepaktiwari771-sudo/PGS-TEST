package com.hp.cks.soar.uploads;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.struts2.interceptor.ServletRequestAware;

import com.hp.cks.soar.uploads.utils.ProcessUploads;
import com.hp.cks.soar.uploads.utils.UploadResources;

public class TestUpload extends HttpServlet implements ServletRequestAware {

    private static final long serialVersionUID = 1L;

    private static final Logger logger =
            LogManager.getLogger(TestUpload.class);

    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; //20 MB
    private static final long MAX_REQUEST_SIZE = 50 * 1024 * 1024; //50 MB

    private HttpServletRequest request;

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        super.init(servletConfig);
        logger.info("TestUpload servlet initialized");
    }

    @Override
    protected void doGet(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        processRequest(request);
    }

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response)
            throws ServletException, IOException {

        processRequest(request);
    }

    /**
     * Process upload request
     */
    public void processRequest(HttpServletRequest request)
            throws ServletException, IOException {

        String contentType = request.getContentType();

        if (contentType == null ||
                !contentType.toLowerCase().startsWith("multipart")) {

            logger.warn("Invalid content type received");
            return;
        }

        try {

            if (!ServletFileUpload.isMultipartContent(request)) {
                logger.warn("Non-multipart request received");
                return;
            }

            ProcessUploads processUploads = new ProcessUploads();

            processUploads.resetUploadedFilesList(
                    request.getSession());

            ServletFileUpload upload =
                    new ServletFileUpload();

            upload.setFileSizeMax(MAX_FILE_SIZE);
            upload.setSizeMax(MAX_REQUEST_SIZE);

            List<FileItem> fileItems =
                    upload.parseRequest(request);

            List<FileItem> uploadableFiles =
                    new ArrayList<>();

            for (FileItem fileItem : fileItems) {

                if (fileItem.isFormField()) {
                    continue;
                }

                String fileName = fileItem.getName();

                if (fileName == null || fileName.trim().isEmpty()) {
                    continue;
                }

                // Sanitize filename
                fileName = FilenameUtils.getName(fileName);

                // Allowed file types
                if (!(fileName.endsWith(".pdf")
                        || fileName.endsWith(".txt")
                        || fileName.endsWith(".csv")
                        || fileName.endsWith(".xls")
                        || fileName.endsWith(".xlsx"))) {

                    logger.warn(
                            "Rejected unsupported file type: {}",
                            fileName);

                    continue;
                }

                uploadableFiles.add(fileItem);

                logger.info(
                        "Accepted upload file: {}",
                        fileName);
            }

            if (uploadableFiles.isEmpty()) {
                logger.warn("No valid files found for upload");
                return;
            }

            boolean uploadCompleted =
                    processUploads.initUploads(
                            request,
                            uploadableFiles,
                            UploadResources.getRootContext());

            if (uploadCompleted) {

                logger.info(
                        "File upload completed successfully. Total Files: {}",
                        uploadableFiles.size());

            } else {

                logger.warn(
                        "File upload processing returned failure.");
            }

        } catch (Exception ex) {

            logger.error(
                    "Error occurred while processing upload request",
                    ex);

            throw new ServletException(
                    "Upload processing failed",
                    ex);
        }
    }

    @Override
    public void setServletRequest(HttpServletRequest request) {
        this.request = request;
    }
}
