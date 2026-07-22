package com.hp.cks.soar.uploads;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.hp.cks.soar.uploads.bean.FileUploadBean;

public class FileUploaderQueue  {

    private static Queue<List<FileUploadBean>> queue = new ConcurrentLinkedQueue<List<FileUploadBean>>();

    private static FileUploaderQueue fileUploaderQueue = null;

    private FileUploaderQueue() {
    }

    public static synchronized FileUploaderQueue getUploadQueue() {
        if (fileUploaderQueue == null) {
            fileUploaderQueue = new FileUploaderQueue();
        }
        return fileUploaderQueue;
    }

    public void offer(List<FileUploadBean> beanList) {
        queue.offer(beanList);
    }

    public List<FileUploadBean> poll() {
        return queue.poll();
    }

  
}
