package com.hp.cks.soar.uploads;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import com.documentum.fc.client.*;
import com.documentum.fc.common.DfException;
import com.documentum.fc.common.DfId;
import com.hp.cks.soar.beans.MD5Checksum;
import com.hp.cks.soar.beans.SwFileBean;
import com.hp.cks.soar.beans.SwItemBean;
import com.hp.cks.soar.event.SoarEventConstants;
import com.hp.cks.soar.event.SoarEventLogHandler;
import com.hp.cks.soar.modules.*;
import com.hp.cks.soar.utils.SoarFileUtils;
import com.missiondata.fileupload.MonitoredDiskFileItemFactory;

/**
 * Servlet that handles software file uploads.
 * Converted from UploadSoftwareFilesAction to a plain HttpServlet to bypass
 * Struts 2's multipart request wrapping which consumes the input stream
 * before the action can parse it manually with Commons FileUpload.
 *
 * CRITICAL: The multipart body must be parsed IMMEDIATELY (before DFC session
 * acquisition or other blocking operations) to prevent reverse proxy/WebLogic
 * connection timeouts that discard the POST body for large (1GB+) files.
 * This matches the original Struts 1 UploadSoftwareFilesAction behavior where
 * parseRequest() was called right after minimal setup.
 */
public class UploadSoftwareFilesServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // GET requests are used for status polling
        HttpSession session = request.getSession();
        if ("status".equals(request.getParameter("c"))) {
            doStatus(session, response);
        } else {
            doFileUpload(session, request, response);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // POST requests are used for file upload
        HttpSession session = request.getSession();
        doFileUpload(session, request, response);
    }

    /**
     * Parse query string parameters directly without calling request.getParameter().
     * On WebLogic, request.getParameter() on a multipart POST triggers internal body
     * buffering via Multipart.initParts() -> Streams.copy() which reads the ENTIRE
     * request body (1GB+) just to find query string parameters.
     */
    private Map<String, String> parseQueryString(HttpServletRequest request) {
        Map<String, String> params = new HashMap<String, String>();
        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isEmpty()) {
            String[] pairs = queryString.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String key = pair.substring(0, idx);
                    String value = idx < pair.length() - 1 ? pair.substring(idx + 1) : "";
                    try {
                        value = URLDecoder.decode(value, "UTF-8");
                    } catch (Exception e) {
                        // use raw value if decode fails
                    }
                    params.put(key, value);
                }
            }
        }
        return params;
    }

    private void doFileUpload(HttpSession session,
                              HttpServletRequest request,
                              HttpServletResponse response) throws IOException {

        IDfSession docbaseSession = null;
        HttpSession httpSession = request.getSession(false);
        if (httpSession == null) {
            httpSession = request.getSession();
        }

        // Extract parameters from URL query string directly (avoids consuming POST body)
        Map<String, String> queryParams = parseQueryString(request);
        String collectionId = queryParams.get("collectionId");
        String parentObjId = queryParams.get("parentObjId");

        logInfo("doFileUpload START - parentObjId: " + parentObjId + ", collectionId: " + collectionId, null);

        int defaultInterval = httpSession.getMaxInactiveInterval();

        try {
            httpSession.setMaxInactiveInterval(-1);

            // --- CRITICAL: Parse the multipart request body IMMEDIATELY ---
            // The body MUST be read before any blocking operations (DFC session, directory I/O)
            // to prevent reverse proxy/WebLogic connection timeouts for large files (1GB+).
            // This matches the original Struts 1 UploadSoftwareFilesAction pattern.

            long contentLength = 0;
            String contentLengthHeader = request.getHeader("Content-Length");
            if (contentLengthHeader != null) {
                contentLength = Long.parseLong(contentLengthHeader);
            }

            if (contentLength > Constants.MAX_FILE_UPLOAD_SIZE) {
                logError("File size exceeds limit: " + contentLength + " > " + Constants.MAX_FILE_UPLOAD_SIZE, null);
                sendCompleteResponse(response, "Exceeded allowed file size limit");
                return;
            }

            // Verify multipart content before attempting parse
            if (!ServletFileUpload.isMultipartContent(request)) {
                logError("Request is not multipart/form-data", null);
                sendCompleteResponse(response, "Invalid request: not a multipart upload");
                return;
            }

            // Resolve temp directory for multipart buffering (quick check, no DFC needed)
            File tempDir = resolveTempDir();

            // Set up progress listener and factory
            FileUploadListener listener = new FileUploadListener(contentLength);
            session.setAttribute("FILE_UPLOAD_STATS", listener.getFileUploadStats());

            MonitoredDiskFileItemFactory factory = new MonitoredDiskFileItemFactory(listener);
            factory.setRepository(tempDir);

            ServletFileUpload upload = new ServletFileUpload(factory);
            upload.setSizeMax(Constants.MAX_FILE_UPLOAD_SIZE);

            // PARSE IMMEDIATELY - start reading the POST body right now.
            // Any delay here allows proxies/WebLogic to timeout and close the connection.
            logInfo("Parsing multipart request (Content-Length: " + contentLength + " bytes)...", null);
            List<FileItem> items;
            try {
                items = upload.parseRequest(request);
            } catch (FileUploadException fue) {
                logError("FileUploadException during multipart parsing: " + fue.getMessage()
                        + " - This typically indicates the connection was closed by a reverse proxy "
                        + "or load balancer due to request body size limit. Check Apache/nginx "
                        + "LimitRequestBody/client_max_body_size and WebLogic MaxPostSize settings.", null);
                fue.printStackTrace();
                sendCompleteResponse(response, "Upload failed: connection was closed before file data "
                        + "could be received. The file may exceed the server's upload size limit. "
                        + "Please contact your administrator.");
                return;
            }
            logInfo("Multipart parsing complete. Found " + items.size() + " form items", null);

            // --- Now that the body is fully received, proceed with DFC and file processing ---

            docbaseSession = SoarSessionHelper.getLockedSession(
                    httpSession,
                    "Beginning of " + getClass().getName()
            );

            if (docbaseSession == null) {
                logError("Failed to get docbase session", null);
                // Clean up parsed file items
                for (FileItem item : items) {
                    item.delete();
                }
                sendCompleteResponse(response, "Failed to get docbase connection");
                return;
            }

            logInfo("Got docbase session successfully", docbaseSession);

            // Resolve upload directory for final file storage
            File uploadDir = resolveUploadDir();

            int count = 0;
            // Count total files to process (for attachment progress tracking)
            int totalFiles = 0;
            for (FileItem fi : items) {
                if (!fi.isFormField() && fi.getName() != null && !fi.getName().trim().isEmpty()) {
                    totalFiles++;
                }
            }
            httpSession.setAttribute("UPLOAD_STATUS", "nil");
            httpSession.setAttribute("FILE_ATTACH_TOTAL", Integer.valueOf(totalFiles));
            httpSession.setAttribute("FILE_ATTACH_CURRENT", Integer.valueOf(0));
            httpSession.setAttribute("FILE_ATTACH_PHASE", "starting");
            httpSession.setAttribute("FILE_ATTACH_FILENAME", "");
            httpSession.setAttribute("FILE_ATTACH_START_TIME", Long.valueOf(System.currentTimeMillis()));

            for (Iterator<FileItem> i = items.iterator(); i.hasNext(); ) {

                FileItem item = i.next();

                if (!item.isFormField()
                        && item.getName() != null
                        && !item.getName().trim().isEmpty()) {

                    count++;

                    // Extract filename from full path
                    String myFullFileName = item.getName();
                    String slashType = (myFullFileName.lastIndexOf("\\") > 0) ? "\\" : "/";
                    int startIndex = myFullFileName.lastIndexOf(slashType);
                    String fileName = myFullFileName.substring(startIndex + 1, myFullFileName.length());

                    logInfo("Processing file #" + count + ": " + fileName + " (size: " + item.getSize() + " bytes)", docbaseSession);

                    // Update attachment progress - writing phase
                    httpSession.setAttribute("FILE_ATTACH_CURRENT", Integer.valueOf(count));
                    httpSession.setAttribute("FILE_ATTACH_PHASE", "writing");
                    httpSession.setAttribute("FILE_ATTACH_FILENAME", fileName);

                    File uploadedFile = new File(uploadDir, fileName);

                    SoarFileUtils.writeToFile(item.getInputStream(), uploadedFile);
                    logInfo("File written to: " + uploadedFile.getAbsolutePath(), docbaseSession);

                    // Update attachment progress - attaching to repository phase
                    httpSession.setAttribute("FILE_ATTACH_PHASE", "attaching");

                    uploadAndAttachFilesToItem(
                            docbaseSession,
                            parentObjId,
                            collectionId,
                            fileName,
                            uploadedFile.getAbsolutePath()
                    );

                    logEvent(docbaseSession, parentObjId, collectionId, fileName);

                    item.delete();
                    uploadedFile.delete();
                }
            }

            // Mark attachment as complete
            httpSession.setAttribute("FILE_ATTACH_PHASE", "complete");
            httpSession.setAttribute("UPLOAD_STATUS", "done");
            logInfo("File upload completed successfully. Total files: " + count, docbaseSession);

            if (count == 0) {
                logInfo("No files were selected for upload", docbaseSession);
                sendCompleteResponse(response, "No file selected for uploading");
            } else {
                sendCompleteResponse(response, null);
            }

        } catch (Exception e) {
            logError("EXCEPTION in doFileUpload: " + e.getClass().getName() + " - " + e.getMessage(), docbaseSession);
            e.printStackTrace();
            sendCompleteResponse(response, e.getMessage());
        } finally {
            httpSession.setMaxInactiveInterval(defaultInterval);
            SoarSessionHelper.unlockAndReleaseSession(
                    docbaseSession,
                    "End of " + getClass().getName()
            );
            httpSession.removeAttribute("UPLOAD_STATUS");
            httpSession.removeAttribute("FILE_ATTACH_TOTAL");
            httpSession.removeAttribute("FILE_ATTACH_CURRENT");
            httpSession.removeAttribute("FILE_ATTACH_PHASE");
            httpSession.removeAttribute("FILE_ATTACH_FILENAME");
            httpSession.removeAttribute("FILE_ATTACH_START_TIME");
        }
    }

    /**
     * Resolve the temp directory for multipart file buffering.
     * Uses Constants.FILE_TEMP_PATH if configured, otherwise falls back to standard paths.
     * This must be fast (no DFC, no blocking) since it runs before multipart parsing.
     */
    private File resolveTempDir() {
        // Primary: use configured path
        if (Constants.FILE_TEMP_PATH != null && !Constants.FILE_TEMP_PATH.trim().isEmpty()) {
            File dir = new File(Constants.FILE_TEMP_PATH);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        }
        // Fallback: standard paths
        File dir = new File("/opt/sasuapps/soar/publish/temp");
        if (!dir.exists()) {
            dir = new File("C:/opt/sasuapps/soar/publish/tmp");
        }
        if (!dir.exists()) {
            dir = new File(System.getProperty("java.io.tmpdir") + File.separator + "soar" + File.separator + "temp");
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Resolve the upload directory for final file storage.
     * Uses Constants.FILE_UPLOAD_PATH if configured, otherwise falls back to standard paths.
     */
    private File resolveUploadDir() {
        // Primary: use configured path
        if (Constants.FILE_UPLOAD_PATH != null && !Constants.FILE_UPLOAD_PATH.trim().isEmpty()) {
            File dir = new File(Constants.FILE_UPLOAD_PATH);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        }
        // Fallback: standard paths
        File dir = new File("/opt/sasuapps/soar/publish/upload");
        if (!dir.exists()) {
            dir = new File("C:/opt/sasuapps/soar/publish/upload");
        }
        if (!dir.exists()) {
            dir = new File(System.getProperty("java.io.tmpdir") + File.separator + "soar" + File.separator + "upload");
            dir.mkdirs();
        }
        return dir;
    }

    private void logEvent(IDfSession session,
                          String parentObjId,
                          String collectionId,
                          String fileName) throws DfException {

        IDfPersistentObject parent =
                (IDfPersistentObject) session.getObject(new DfId(parentObjId));

        String type = parent.getString("r_object_type");
        String chronicleId = parent.getString("i_chronicle_id");

        String desc = SoarUtils.getDescriptionForAddDeleteEvent(
                parentObjId, type, chronicleId
        );

        try {
            if (SoarEventConstants.SW_ITEM.equalsIgnoreCase(type)) {

                SwItemBean item = new SwItemBean(session, parentObjId);
                item.populateSelf();

                SoarEventLogHandler.logEvent(
                        session, parentObjId,
                        SoarEventConstants.SW_ITEM,
                        collectionId,
                        SoarEventConstants.UIITEMUPLOADFILE,
                        desc + " Software file uploaded: " + fileName
                );

            } else {
                SoarEventLogHandler.logEvent(
                        session, parentObjId,
                        SoarEventConstants.SW_COLLECTION,
                        collectionId,
                        SoarEventConstants.UICOLADDSWFILE,
                        desc + " Software file uploaded: " + fileName
                );
            }
        } catch (DmRepositoryException e) {
            e.printStackTrace();
        }
    }

    private void doStatus(HttpSession session, HttpServletResponse response) throws IOException {

        // Prevent caching of status response
        response.setHeader("Expires", "0");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Cache-Control", "post-check=0, pre-check=0");
        response.setHeader("Pragma", "no-cache");

        FileUploadListener.FileUploadStats fileUploadStats =
                (FileUploadListener.FileUploadStats) session.getAttribute("FILE_UPLOAD_STATS");

        if (fileUploadStats != null) {
            long bytesProcessed = fileUploadStats.getBytesRead();
            long sizeTotal = fileUploadStats.getTotalSize();

            // Guard against invalid totalSize (e.g., -1, 0, or int overflow)
            long percentComplete = 0;
            if (sizeTotal > 0) {
                percentComplete = (long) Math.floor(((double) bytesProcessed / (double) sizeTotal) * 100.0);
            }
            // Cap at 100% to prevent progress bar overflow
            if (percentComplete > 100) {
                percentComplete = 100;
            }
            if (percentComplete < 0) {
                percentComplete = 0;
            }
            // Cap displayed bytesProcessed to not exceed sizeTotal
            long displayBytesProcessed = (sizeTotal > 0 && bytesProcessed > sizeTotal) ? sizeTotal : bytesProcessed;

            long timeInSeconds = fileUploadStats.getElapsedTimeInSeconds();
            double uploadRate = bytesProcessed / (timeInSeconds + 0.00001);
            double estimatedRuntime = (sizeTotal > 0) ? sizeTotal / (uploadRate + 0.00001) : 0;

            response.getWriter().println("<b>Upload Status:</b><br/>");

            if (fileUploadStats.getBytesRead() != fileUploadStats.getTotalSize()) {
                // Still uploading - show progress bar at current percent
                response.getWriter().println(
                        "<div class=\"prog-border\"><div class=\"prog-bar\" style=\"width: "
                                + percentComplete + "%;\"></div></div>");
                response.getWriter().println(
                        "Uploaded: " + formatSize(displayBytesProcessed) + " out of " + formatSize(sizeTotal)
                                + " (" + percentComplete + "%)" + "<br/>");
                response.getWriter().println(
                        "Upload Rate: " + (long) Math.round(uploadRate / 1024) + " Kbs <br/>");
                response.getWriter().println(
                        "Runtime: " + formatTime(timeInSeconds) + " out of " + formatTime(estimatedRuntime) + "<br/> ");
                response.getWriter().println(
                        "Estimated Time Left: " + formatTime(estimatedRuntime - timeInSeconds) + "<br/>");
            } else {
                // Upload complete - show attachment progress (do NOT stop poller yet)
                response.getWriter().println(
                        "<div class=\"prog-border\"><div class=\"prog-bar\" style=\"width: 100%;\"></div></div>");
                response.getWriter().println(
                        "Uploaded: " + sizeTotal + " out of " + sizeTotal + " bytes (100%)<br/>");

                // Show attachment phase progress
                String attachPhase = (String) session.getAttribute("FILE_ATTACH_PHASE");
                String attachFileName = (String) session.getAttribute("FILE_ATTACH_FILENAME");
                Integer attachCurrent = (Integer) session.getAttribute("FILE_ATTACH_CURRENT");
                Integer attachTotal = (Integer) session.getAttribute("FILE_ATTACH_TOTAL");

                if ("complete".equals(attachPhase)) {
                    // Attachment finished - stop poller
                    int total = (attachTotal != null) ? attachTotal.intValue() : 0;
                    response.getWriter().println("<b>Attachment complete. " + total + " file(s) processed.</b><br/>");
                    response.getWriter().println("<script type=\"text/javascript\">window.parent.killUpdate('');</script>");
                } else {
                    // Still attaching - show progress
                    // Use (current-1) for completed files since current file is still in progress
                    int current = (attachCurrent != null) ? attachCurrent.intValue() : 0;
                    int total = (attachTotal != null) ? attachTotal.intValue() : 0;
                    String fname = (attachFileName != null) ? attachFileName : "";
                    int completed = current - 1; // files fully done
                    if (completed < 0) completed = 0;

                    response.getWriter().println("<br/><b>Attaching file to collection/item...</b><br/>");
                    if (total > 0) {
                        // Progress based on completed files (not current in-progress file)
                        int attachPercent = (int) Math.floor(((double) completed / (double) total) * 100.0);
                        response.getWriter().println(
                                "<div class=\"prog-border\"><div class=\"prog-bar\" style=\"width: "
                                        + attachPercent + "%;\"></div></div>");
                        response.getWriter().println(
                                "Processing file " + current + " of " + total
                                        + " (" + completed + " completed)<br/>");
                    }
                    if (!"".equals(fname)) {
                        String phaseLabel = "writing".equals(attachPhase)
                                ? "Writing file to server disk..."
                                : "Importing into repository (this may take several minutes for large files)...";
                        response.getWriter().println("File: " + fname + "<br/>");
                        response.getWriter().println("<b>" + phaseLabel + "</b><br/>");
                        // Show elapsed time so user knows server is still working
                        Long startTime = (Long) session.getAttribute("FILE_ATTACH_START_TIME");
                        if (startTime != null) {
                            long elapsedSec = (System.currentTimeMillis() - startTime.longValue()) / 1000;
                            response.getWriter().println(
                                    "Elapsed time: " + formatTime(elapsedSec) + "<br/>");
                        }
                        // Animated indicator to show server is still working
                        response.getWriter().println(
                                "<span class=\"attach-progress\">&#9632; In progress...</span><br/>");
                    }
                }
            }
        }
    }

    private boolean uploadAndAttachFilesToItem(IDfSession session,
                                               String parentObjId,
                                               String collectionId,
                                               String fileName,
                                               String localFile)
            throws DfException, IOException, DmRepositoryException {

        IDfDocument doc = (IDfDocument) session.getObject(new DfId(parentObjId));
        if (doc == null || (!doc.getTypeName().equals("sw_collection")
                && !doc.getTypeName().equals("sw_item"))) {
            logError("Failed to upload file " + fileName
                    + " to collection " + collectionId
                    + ". Invalid Parent Object Id " + parentObjId
                    + ", There is no collection/item with this object Id", session);
            return false;
        }

        logInfo("processing file: " + fileName, session);
        File file = new File(localFile);

        if (!file.exists()) {
            logError("Failed to upload file " + localFile
                    + " to parent object " + parentObjId
                    + ". File does not exist on server", session);
            return false;
        }

        MD5Checksum checksum = new MD5Checksum(localFile);
        SwFileBean swFile = new SwFileBean();

        try {
            swFile.createNew(
                    session, parentObjId, collectionId,
                    null, localFile, fileName,
                    checksum.digest(), Constants.PENDING_ZIP
            );
            logInfo("created a sw_file with real name: " + fileName, session);
        } catch (DmRepositoryException e) {
            logError("Failed to create sw_file for " + fileName + ": " + e.getMessage(), session);
            throw e;
        }

        return true;
    }

    private void sendCompleteResponse(HttpServletResponse response, String message) throws IOException {
        if (message == null) {
            response.getOutputStream().print(
                    "<html><head><script type='text/javascript'>function killUpdate() { window.parent.killUpdate(''); }</script></head><body onload='killUpdate()'></body></html>"
            );
        } else {
            response.getOutputStream().print(
                    "<html><head><script type='text/javascript'>function killUpdate() { window.parent.killUpdate('"
                            + message
                            + "'); }</script></head><body onload='killUpdate()'></body></html>"
            );
        }
    }

    private String formatTime(double timeInSeconds) {
        long seconds = (long) Math.floor(timeInSeconds);
        long minutes = (long) Math.floor(timeInSeconds / 60.0);
        long hours = (long) Math.floor(minutes / 60.0);

        if (hours != 0) {
            return hours + " hours " + (minutes % 60) + " minutes " + (seconds % 60) + " seconds";
        } else if (minutes % 60 != 0) {
            return (minutes % 60) + " minutes " + (seconds % 60) + " seconds";
        } else {
            return (seconds % 60) + " seconds";
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " bytes";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }

    private void logError(String msg, IDfSession session) {
        SessionLog.logError(msg, session, "UploadSoftwareFilesServlet");
    }

    private void logInfo(String msg, IDfSession session) {
        SessionLog.logInfo(msg, session, "UploadSoftwareFilesServlet");
    }
}

