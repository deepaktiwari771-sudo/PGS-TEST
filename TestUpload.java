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
import com.hp.cks.soar.actions.MultiFileUploadAction;
import com.hp.cks.soar.uploads.utils.ProcessUploads;
import com.hp.cks.soar.uploads.utils.UploadResources;
import org.apache.struts2.interceptor.ServletRequestAware;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
/**
 * <p>
 * Created by IntelliJ IDEA.
 * </p>
 * <p>
 * Package: com.hp.cks.soar.uploads
 * </p>
 * <p>
 * Version: 1.01
 * </p>
 * <p>
 * Updated On: 3:24:11 PM
 * </p>
 * <p>
 * Date: Jun 2, 2005
 * </p>
 * <p>
 * Time: 3:24:11 PM
 * </p>
 * <p>
 * Description:
 * </p>
 * <p>
 * Author: Khalid Ali
 * </p>
 */
public class TestUpload extends HttpServlet implements ServletRequestAware {

    Logger logger = LogManager.getLogger(MultiFileUploadAction.class);

    /**
     * Load the init properties, such as we want to load the log4j.properties
     * file when the app is loaded . /WEB-INF/log4j.properties .
     *
     * @param servletConfig
     * @throws javax.servlet.ServletException
     */
    public void init(ServletConfig servletConfig) throws ServletException {
        super.init(servletConfig);
    }

    /**
     * @param request
     * @param response
     * @throws ServletException
     * @throws java.io.IOException
     */
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doWork(request, response);
    }

    /**
     * @param request
     * @param response
     * @throws ServletException
     * @throws IOException
     */
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        doWork(request, response);
    }

    /**
     * @param request
     * @param response
     * @throws ServletException
     * @throws IOException
     */
    public void doWork(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        processRequest(request);
    }

    /**
     * @param request
     * @throws ServletException
     * @throws IOException
     */
    public synchronized void processRequest(HttpServletRequest request) throws ServletException, IOException {
        if ((request.getContentType() != null) && (request.getContentType().toLowerCase().startsWith("multipart"))) {
            try {
                boolean isMultipart = ServletFileUpload.isMultipartContent(request);
                boolean uploadCompleted = false;
                ProcessUploads processUploads = new ProcessUploads();
                if (isMultipart) {
                    processUploads.resetUploadedFilesList(request.getSession());
                }
                ServletFileUpload diskFileUploads = new ServletFileUpload();
                List listOfFiles = (List) diskFileUploads.parseRequest(request);
                List uploadableFilesList = new ArrayList();
                Iterator i = listOfFiles.iterator();
                while (i.hasNext()) {
                    FileItem fileItem = (FileItem) i.next();
                    if (!fileItem.isFormField() && null != fileItem.getName()) {
                        uploadableFilesList.add(fileItem);
                    }
                }
                uploadCompleted = processUploads.initUploads(request, uploadableFilesList, UploadResources.getRootContext());
            } catch (Exception ioe) {
                ioe.printStackTrace();
            }
        }
    }

   
    private javax.servlet.http.HttpServletRequest request;

    @Override()
    public void setServletRequest(javax.servlet.http.HttpServletRequest request) {
        this.request = request;
    }
}
