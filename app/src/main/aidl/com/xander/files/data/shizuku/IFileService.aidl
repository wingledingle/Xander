package com.xander.files.data.shizuku;

interface IFileService {
    void destroy() = 16777114;
    /** Streams one "isDir|size|mtime|mode|name" line per child into the returned pipe. */
    ParcelFileDescriptor list(String path) = 1;
    String stat(String path) = 2;
    ParcelFileDescriptor openRead(String path) = 3;
    ParcelFileDescriptor openWrite(String path) = 4;
    boolean mkdir(String path) = 5;
    boolean rename(String from, String to) = 6;
    boolean delete(String path) = 7;
    boolean createFile(String path) = 8;
    boolean exists(String path) = 9;
    /** Streams matching absolute paths (as stat lines) under root whose name contains needle. */
    ParcelFileDescriptor find(String root, String needle, boolean includeHidden) = 10;
    /** Runs a shell command as the shell user; returns stdout+stderr. */
    String exec(String command) = 11;
}
