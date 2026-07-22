package com.hp.cks.soar.uploads;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.logging.log4j.Logger;

import com.documentum.fc.client.IDfDocument;
import com.documentum.fc.client.IDfSession;
import com.documentum.fc.common.DfException;
import com.documentum.fc.common.DfId;
import com.hp.cks.soar.beans.MD5Checksum;
import com.hp.cks.soar.beans.SwFileBean;
import com.hp.cks.soar.modules.Constants;
import com.hp.cks.soar.modules.DmRepositoryException;
import com.hp.cks.soar.modules.DocbaseUtils;
import com.hp.cks.soar.modules.MailSender;
import com.hp.cks.soar.modules.SoarMessagesUtil;
import com.hp.cks.soar.modules.SoarUtils;
import com.hp.cks.soar.uploads.bean.FileUploadBean;
import com.hp.cks.soar.utils.SoarLogger;
import com.opensymphony.xwork2.ActionSupport;

public class FileUploaderThread implements Runnable {

    private static Logger logger = SoftwareFileUploader.logger;

    private IDfSession docbaseSession;

    private List<FileUploadBean> uploadBeans;

    private String errorMessage = "Unknown error occured";

    public FileUploaderThread(List<FileUploadBean> uploadBeans) {
        this.uploadBeans = uploadBeans;
    }

    public void run() {
        long timeTaken = 0;
        String collectionId = null;
        String parentObjectId = null;
        String userName = null;
        String fileName = null;
        String userEmailAddress = null;
        boolean uploadSuccess = false;
        long startTime = System.currentTimeMillis();
        try {
            FileUploadBean uploadBean = null;
            for (int i = 0; i < uploadBeans.size(); i++) {
                uploadBean = uploadBeans.get(i);
                try {
                    if (docbaseSession == null) {
                        docbaseSession = DocbaseUtils.getUserSession(uploadBean.getUserName());
                        collectionId = uploadBean.getCollectionId();
                        parentObjectId = uploadBean.getParentObjectId();
                        userName = uploadBean.getUserName();
                        fileName = uploadBean.getFileName();
                        userEmailAddress = uploadBean.getUserAddress();
                    }
                    if (docbaseSession != null) {
                        uploadSuccess = uploadAndAttachFilesToItem(docbaseSession, uploadBean);
                        if (uploadSuccess) {
                            uploadBean.setFileUploadMessage("File Uploaded Successfully");
                            uploadBean.setFileUploadStatus(true);
                        } else {
                            uploadBean.setFileUploadMessage(errorMessage);
                            uploadBean.setFileUploadStatus(false);
                        }
                        timeTaken = (System.currentTimeMillis() - startTime) / 1000;
                    } else {
                        errorMessage = "Docbase Connection failed for user " + uploadBean.getUserName();
                        uploadBean.setFileUploadMessage(errorMessage);
                        uploadBean.setFileUploadStatus(false);
                        logger.error(errorMessage);
                    }
                } catch (Exception e) {
                    logger.error("Error while uploading file ", e);
                    errorMessage = SoarUtils.getStackTrace(e);
                    uploadBean.setFileUploadMessage(errorMessage);
                    uploadBean.setFileUploadStatus(false);
                } finally {
                    new File(uploadBean.getServerFile()).delete();
                }
            }
        } catch (Exception e) {
            logger.error("Error while uploading file ", e);
            errorMessage = SoarUtils.getStackTrace(e);
        } finally {
            DocbaseUtils.disconnectFromDocbase(docbaseSession);
            sendEmailToUser(userEmailAddress, fileName, timeTaken + " secs", collectionId);
        }
    }

    private void sendEmailToUser(String userAddress, String fileName, String timeTaken, String collectionId) {
        String subject = "";
        String msgBody = "";
        subject = "File upload status for " + collectionId;
        for (int i = 0; i < uploadBeans.size(); i++) {
            FileUploadBean bean = uploadBeans.get(i);
            if (bean.getFileUploadStatus()) {
                msgBody = "Successfully uploaded the file " + fileName + " to collection " + collectionId;
                msgBody += "\nTotal Upload Time :" + timeTaken;
            } else {
                msgBody = "Failed to upload file " + fileName + " to collection " + collectionId;
                msgBody += "\n Reason is :\n" + errorMessage;
                msgBody += "\n\n";
            }
        }
        String content = SoarMessagesUtil.getUserMessage(msgBody);
        MailSender.send(new String[] { userAddress }, MailSender.getSupportEmailIds(), subject, content, false);
    }

    private boolean uploadAndAttachFilesToItem(IDfSession session, FileUploadBean uploadBean) throws DmRepositoryException, IOException, DfException {
        IDfDocument document = (IDfDocument) session.getObject(new DfId(uploadBean.getParentObjectId()));
        if (document != null && (document.getTypeName().equals("sw_collection") || document.getTypeName().equals("sw_item"))) {
            String fileName = uploadBean.getFileName();
            return attachSoftwareFiles(session, uploadBean);
        }
        errorMessage = "Failed to upload file " + uploadBean.getFileName() + " to collection " + uploadBean.getCollectionId() + ". Invalid Parent Object Id " + uploadBean.getParentObjectId() + ", There is no collection/item with this object Id ";
        logger.error(errorMessage);
        return false;
    }

    private boolean attachSoftwareFiles(IDfSession session, FileUploadBean uploadBean) throws DmRepositoryException {
        String parentObjectId = uploadBean.getParentObjectId();
        String collectionId = uploadBean.getCollectionId();
        String realName = uploadBean.getFileName();
        String serverFile = uploadBean.getServerFile();
        File file = new File(serverFile);
        if (!file.exists()) {
            errorMessage = "Failed to upload file " + serverFile + " to parent object " + parentObjectId + ". File does not exist on server";
            logger.error(errorMessage);
            return false;
        }
        //MD5Checksum md5Checksum = new MD5Checksum(session);
        MD5Checksum md5Checksum = new MD5Checksum(serverFile);
        //md5Checksum.setFilename(serverFile);
        String checksum = md5Checksum.digest();
        SwFileBean swFile = new SwFileBean();
        swFile.createNew(session, parentObjectId, collectionId, null, serverFile, realName, checksum, Constants.PENDING_ZIP);
        return true;
    }

   
}
