package com.hp.cks.soar.uploads;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.Logger;

import com.hp.cks.soar.uploads.bean.FileUploadBean;
import com.hp.cks.soar.utils.SoarLogger;

public class SoftwareFileUploader implements Runnable {

    public static boolean UPLOADER_STARTED = false;

    public static Logger logger = SoarLogger.getUploadLogger(FileUploadServlet.class, "/mnt/soar_logs/upload_logs/FileUpload");

    static ExecutorService executor = Executors.newFixedThreadPool(6);

    private static SoftwareFileUploader fileUploader = null;

    private SoftwareFileUploader() {
    }

    public static synchronized SoftwareFileUploader getFileUploader() {
        if (fileUploader == null) {
            fileUploader = new SoftwareFileUploader();
        }
        return fileUploader;
    }

    public void run() {
        if (!UPLOADER_STARTED) {
            UPLOADER_STARTED = true;
            FileUploaderQueue uploadQueue = FileUploaderQueue.getUploadQueue();
            while (true) {
                List<FileUploadBean> next = uploadQueue.poll();
                if (next != null) {
                    FileUploaderThread worker = new FileUploaderThread(next);
                    executor.execute(worker);
                } else {
                    try {
                        Thread.sleep(5 * 60 * 1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    
}
