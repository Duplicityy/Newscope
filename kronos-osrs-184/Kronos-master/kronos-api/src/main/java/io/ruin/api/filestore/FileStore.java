package io.ruin.api.filestore;

import io.ruin.api.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class FileStore {

    public final IndexFile index255;

    public final IndexFile[] files;

    public FileStore(String path) throws IOException {
        this(path, 1000000);
    }

    /** Extended242 model banks only; all ordinary archives retain their original limits. */
    public FileStore(String path, int maxModelBankBytes) throws IOException {
        if (maxModelBankBytes < 1000000 || maxModelBankBytes > 8 * 1024 * 1024)
            throw new IllegalArgumentException("Model-bank archive limit must be1,000,000..8MiB");
        path = FileUtils.get(path).getAbsolutePath() + File.separator;
        RandomAccessFile mainDataFile = new RandomAccessFile(path + "main_file_cache.dat2", "rw");
        RandomAccessFile mainDataFile255 = new RandomAccessFile(path + "main_file_cache.idx255", "rw");

        this.index255 = new IndexFile(255, mainDataFile, mainDataFile255, 500000);
        this.files = new IndexFile[index255.getArchivesCount()];

        for(int index = 0; index < files.length; index++) {
            byte[] archiveData = index255.getArchiveData(index);
            if(archiveData == null) {
                System.err.println("Failed to read archive data for index: " + index);
                continue;
            }
            RandomAccessFile indexDataFile = new RandomAccessFile(path + "main_file_cache.idx" + index, "rw");
            IndexFile indexFile = new IndexFile(index, mainDataFile, indexDataFile, 1000000, maxModelBankBytes);
            indexFile.setReferenceTable(new ReferenceTable(archiveData));
            files[index] = indexFile;
        }
    }

    public void reloadReferenceTable(){
        for(int index = 0; index < files.length; index++) {
            byte[] archiveData = index255.getArchiveData(index);
            if(archiveData == null) {
                System.err.println("Failed to read archive data for index: " + index);
                continue;
            }

            files[index].setReferenceTable(new ReferenceTable(archiveData));
        }
    }

    public IndexFile get(int index) {
        if(index < 0)
            return null;
        if(index == 255)
            return index255;
        if(index >= files.length)
            return null;
        return files[index];
    }

}
