package com.hp.cks.soar.uploads;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import com.documentum.fc.client.IDfDocument;
import com.documentum.fc.client.IDfSession;
import com.documentum.fc.common.DfException;
import com.documentum.fc.common.DfId;
import com.hp.cks.soar.beans.MD5Checksum;
import com.hp.cks.soar.beans.SwFileBean;
import com.hp.cks.soar.modules.Constants;
import com.hp.cks.soar.modules.DmRepositoryException;
import com.hp.cks.soar.modules.SessionLog;
import com.hp.cks.soar.modules.SoarSessionHelper;
import com.hp.cks.soar.utils.SoarFileUtils;
import com.missiondata.fileupload.MonitoredDiskFileItemFactory;
import org.apache.struts2.interceptor.ServletRequestAware;

public class FileUploadServlet extends HttpServlet implements ServletRequestAware {

    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // If status check, delegate to doPost
        if ("status".equals(request.getParameter("c"))) {
            doPost(request, response);
            return;
        }
        
        // Show welcome/status page for direct browser access
        response.setContentType("text/html");
        response.getWriter().println("<html><head><title>File Upload Servlet</title></head><body>");
        response.getWriter().println("<h1>File Upload Servlet - Service Active</h1>");
        response.getWriter().println("<p>The servlet is working correctly!</p>");
        response.getWriter().println("<h2>Purpose:</h2>");
        response.getWriter().println("<p>Handles multipart file uploads for SOAR software collections and items.</p>");
        response.getWriter().println("<h2>Supported Operations:</h2>");
        response.getWriter().println("<ul>");
        response.getWriter().println("<li><b>File Upload</b> - POST multipart/form-data with files</li>");
        response.getWriter().println("<li><b>Status Check</b> - GET request with parameter <code>c=status</code></li>");
        response.getWriter().println("</ul>");
        response.getWriter().println("<h3>Required Parameters (for upload):</h3>");
        response.getWriter().println("<ul>");
        response.getWriter().println("<li><b>parentObjId</b> - Parent collection or item object ID</li>");
        response.getWriter().println("<li><b>collectionId</b> - Collection ID</li>");
        response.getWriter().println("<li><b>parentType</b> - Type of parent (collection/item)</li>");
        response.getWriter().println("</ul>");
        response.getWriter().println("<h3>Status Check Example:</h3>");
        response.getWriter().println("<pre>http://localhost:8080/soar/Upload?c=status</pre>");
        response.getWriter().println("<p><b>Note:</b> This servlet is typically used by upload forms in the SOAR application, not directly accessed.</p>");
        response.getWriter().println("</body></html>");
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession();
        if ("status".equals(request.getParameter("c"))) {
            doStatus(session, response);
        } else {
            doFileUpload(session, request, response);
        }
    }

    private void doFileUpload(HttpSession session, HttpServletRequest request, HttpServletResponse response) throws IOException {
        IDfSession docbaseSession = null;
        HttpSession httpSession = request.getSession();
        String collectionId = request.getParameter("collectionId");
        String parentObjId = request.getParameter("parentObjId");
        String parentType = request.getParameter("parentType");
        int defaultInactiveInterval = httpSession.getMaxInactiveInterval();
        try {
            httpSession.setMaxInactiveInterval(-1);
            docbaseSession = SoarSessionHelper.getLockedSession(httpSession, "Begining of " + this.getClass().getName());
            FileUploadListener listener = new FileUploadListener(request.getContentLengthLong());
            session.setAttribute("FILE_UPLOAD_STATS", listener.getFileUploadStats());
            FileItemFactory factory = new MonitoredDiskFileItemFactory(listener);
            ServletFileUpload upload = new ServletFileUpload(factory);
            List items = upload.parseRequest(request);
            boolean hasError = false;
            for (Iterator i = items.iterator(); i.hasNext(); ) {
                FileItem fileItem = (FileItem) i.next();
                if (!fileItem.isFormField()) {
                    String myFullFileName = fileItem.getName(), myFileName = "", slashType = (myFullFileName.lastIndexOf("\\") > 0) ? "\\" : "/";
                    int startIndex = myFullFileName.lastIndexOf(slashType);
                    myFileName = myFullFileName.substring(startIndex + 1, myFullFileName.length());
                    File uploadedFile = new File(Constants.FILE_UPLOAD_PATH, myFileName);
                    if (fileItem.getSize() < (10 * 1024 * 1024)) {
                        fileItem.write(uploadedFile);
                    } else {
                        SoarFileUtils.writeToFile(fileItem.getInputStream(), uploadedFile);
                    }
                    uploadAndAttachFilesToItem(docbaseSession, parentObjId, collectionId, myFileName, uploadedFile.getAbsolutePath());
                    fileItem.delete();
                    uploadedFile.delete();
                }
            }
            if (!hasError) {
                sendCompleteResponse(response, null);
            } else {
                sendCompleteResponse(response, "Could not process uploaded file. Please see log for details.");
            }
        } catch (Exception e) {
            sendCompleteResponse(response, e.getMessage());
        }
    }

    private void doStatus(HttpSession session, HttpServletResponse response) throws IOException {
        // Make sure the status response is not cached by the browser
        response.addHeader("Expires", "0");
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.addHeader("Cache-Control", "post-check=0, pre-check=0");
        response.addHeader("Pragma", "no-cache");
        FileUploadListener.FileUploadStats fileUploadStats = (FileUploadListener.FileUploadStats) session.getAttribute("FILE_UPLOAD_STATS");
        if (fileUploadStats != null) {
            long bytesProcessed = fileUploadStats.getBytesRead();
            long sizeTotal = fileUploadStats.getTotalSize();
            long percentComplete = 0;
            if (sizeTotal > 0) {
                percentComplete = (long) Math.floor(((double) bytesProcessed / (double) sizeTotal) * 100.0);
            }
            if (percentComplete > 100) percentComplete = 100;
            if (percentComplete < 0) percentComplete = 0;
            long displayBytesProcessed = (sizeTotal > 0 && bytesProcessed > sizeTotal) ? sizeTotal : bytesProcessed;
            long timeInSeconds = fileUploadStats.getElapsedTimeInSeconds();
            double uploadRate = bytesProcessed / (timeInSeconds + 0.00001);
            double estimatedRuntime = (sizeTotal > 0) ? sizeTotal / (uploadRate + 0.00001) : 0;
            response.getWriter().println("<b>Upload Status:</b><br/>");
            if (fileUploadStats.getBytesRead() != fileUploadStats.getTotalSize()) {
                response.getWriter().println("<div class=\"prog-border\"><div class=\"prog-bar\" style=\"width: " + percentComplete + "%;\"></div></div>");
                response.getWriter().println("Uploaded: " + formatSize(displayBytesProcessed) + " out of " + formatSize(sizeTotal) + " (" + percentComplete + "%)<br/>");
                response.getWriter().println("Upload Rate: " + Math.round(uploadRate / 1024) + " Kbs <br/>");
                response.getWriter().println("Runtime: " + formatTime(timeInSeconds) + " out of " + formatTime(estimatedRuntime) + " " + formatTime(estimatedRuntime - timeInSeconds) + " remaining <br/>");
            } else {
                response.getWriter().println("Uploaded: " + bytesProcessed + " out of " + sizeTotal + " bytes<br/>");
                response.getWriter().println("Complete.<br/>");
            }
        }
        if (fileUploadStats != null && fileUploadStats.getBytesRead() == fileUploadStats.getTotalSize()) {
            response.getWriter().println("<b>Upload complete. Attaching the file to collection/item</b>");
        }
    }

    private void sendCompleteResponse(HttpServletResponse response, String message) throws IOException {
        if (message == null) {
            response.getOutputStream().print("<html><head><script type='text/javascript'>function killUpdate() { window.parent.killUpdate(''); }</script></head><body onload='killUpdate()'></body></html>");
        } else {
            response.getOutputStream().print("<html><head><script type='text/javascript'>function killUpdate() { window.parent.killUpdate('" + message + "'); }</script></head><body onload='killUpdate()'></body></html>");
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

    private boolean uploadAndAttachFilesToItem(IDfSession session, String parentObjId, String collectionId, String fileName, String localFile) throws DmRepositoryException, IOException, DfException {
        IDfDocument document = (IDfDocument) session.getObject(new DfId(parentObjId));
        if (document != null && (document.getTypeName().equals("sw_collection") || document.getTypeName().equals("sw_item"))) {
            logInfo("Uploading file " + fileName, session);
            return attachSoftwareFiles(session, parentObjId, collectionId, fileName, localFile);
        }
        logError("Failed to upload file " + fileName + " to collection " + collectionId + ". Invalid Parent Object Id " + parentObjId + ", There is no collection/item with this object Id ", session);
        return false;
    }

    private boolean attachSoftwareFiles(IDfSession session, String parentObjectId, String collectionId, String fileName, String localFile) throws DmRepositoryException {
        logInfo("processing file :" + fileName, session);
        File file = new File(localFile);
        if (!file.exists()) {
            logError("Failed to upload file " + localFile + " to parent object " + parentObjectId + ". File does not exist on server", session);
            return false;
        }
        //MD5Checksum md5Checksum = new MD5Checksum(session);
        MD5Checksum md5Checksum = new MD5Checksum(localFile);
        //md5Checksum.setFilename(localFile);
        String checksum = md5Checksum.digest();
        SwFileBean swFile = new SwFileBean();
        swFile.createNew(session, parentObjectId, collectionId, null, localFile, fileName, checksum, Constants.PENDING_ZIP);
        logInfo("created a sw_file with real name :" + fileName, session);
        return true;
    }

    private void logError(String msg, IDfSession session) {
        SessionLog.logError(msg, session, "UploadSoftwareFilesAction");
    }

    private void logInfo(String msg, IDfSession session) {
        SessionLog.logInfo(msg, session, "UploadSoftwareFilesAction");
    }

  
    private javax.servlet.http.HttpServletRequest request;

    @Override()
    public void setServletRequest(javax.servlet.http.HttpServletRequest request) {
        this.request = request;
    }
}
