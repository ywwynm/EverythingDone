package com.ywwynm.everythingdone.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Created by ywwynm on 2016/3/20.
 * utils for operating {@link File}s
 */
public class FileUtil {

    public static File createFile(String parentPath, String name) {
        File parent = new File(parentPath);
        return new File(parent, name);
    }

    public static boolean deleteFile(String pathName) {
        File file = new File(pathName);
        return deleteFile(file);
    }

    public static boolean deleteFile(File file) {
        if (file.isDirectory()) {
            return deleteDirectory(file);
        } else {
            return file.delete();
        }
    }

    public static boolean deleteDirectory(String pathName) {
        File dir = new File(pathName);
        return deleteDirectory(dir);
    }

    public static boolean deleteDirectory(File dir) {
        if (!dir.isDirectory()) {
            return false;
        }

        File[] files = dir.listFiles();
        for (File file : files) {
            boolean deleted = deleteFile(file);
            if (!deleted) return false;
        }
        return dir.delete();
    }

    public static String getPostfix(String pathName) {
        int index = pathName.lastIndexOf(".");
        if (index == -1) {
            return "";
        } else {
            return pathName.substring(index + 1, pathName.length());
        }
    }

    public static void copyFile(File src, File dst) throws IOException {
        BufferedInputStream bis  = new BufferedInputStream(new FileInputStream(src));
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst));

        byte[] b = new byte[4096];
        int len;
        while ((len = bis.read(b)) != -1) {
            bos.write(b, 0, len);
        }
        bos.flush();

        bis.close();
        bos.close();
    }

    public static void copyDirectory(String sourceDir, String targetDir) throws IOException {
        File[] files = new File(sourceDir).listFiles();
        for (File file : files) {
            if (file.isFile()) {
                File parent = new File(targetDir);
                if (!parent.exists()) {
                    parent.mkdirs();
                }
                File targetFile = new File(parent.getAbsolutePath(), file.getName());
                copyFile(file, targetFile);
            } else  {
                String dir1 = sourceDir + "/" + file.getName();
                String dir2 = targetDir + "/" + file.getName();
                copyDirectory(dir1, dir2);
            }
        }
    }

    public static boolean zipDirectory(File src, File dst, String... exclude) {
        ZipOutputStream zout = null;
        try {
            zout = new ZipOutputStream(new FileOutputStream(dst));
            File[] files = src.listFiles();
            for (File file : files) {
                if (!isInArray(file.getAbsolutePath(), exclude)) {
                    // 递归压缩，更新curPaths
                    zipFileOrDirectory(zout, file, "", exclude);
                }
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (zout != null) {
                try {
                    zout.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void zipFileOrDirectory(ZipOutputStream zout, File src, String curPath, String... exclude)
            throws IOException {
        //从文件中读取字节的输入流
        FileInputStream in = null;
        try {
            if (!src.isDirectory()) { // zip a file
                if (isInArray(src.getAbsolutePath(), exclude)) {
                    return;
                }
                byte[] buffer = new byte[4096];
                int bytes;
                in = new FileInputStream(src);
                //实例代表一个条目内的ZIP归档
                ZipEntry entry = new ZipEntry(curPath + src.getName());
                //条目的信息写入底层流
                zout.putNextEntry(entry);
                while ((bytes = in.read(buffer)) != -1) {
                    zout.write(buffer, 0, bytes);
                }
                zout.closeEntry();
            } else { // zip a directory
                File[] entries = src.listFiles();
                for (File entry : entries) {
                    if (!isInArray(entry.getAbsolutePath(), exclude)) {
                        // 递归压缩，更新curPaths
                        zipFileOrDirectory(zout, entry, curPath + src.getName() + File.separator, exclude);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    public static boolean unzip(String zipFileName, String outputDirectory) {
        String separator = File.separator;
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(zipFileName);
            Enumeration entries = zipFile.entries();
            ZipEntry zipEntry;
            File dst = new File(outputDirectory);
            dst.mkdirs();

            while (entries.hasMoreElements()) {
                zipEntry = (ZipEntry) entries.nextElement();
                String entryName = zipEntry.getName();
                InputStream in = null;
                FileOutputStream out = null;
                try {
                    if (zipEntry.isDirectory()) {
                        String name = zipEntry.getName();
                        name = name.substring(0, name.length() - 1);
                        File f = new File(outputDirectory + separator + name);
                        f.mkdirs();
                    } else {
                        int index = entryName.lastIndexOf("\\");
                        if (index != -1) {
                            File df = new File(outputDirectory + separator
                                    + entryName.substring(0, index));
                            df.mkdirs();
                        }
                        index = entryName.lastIndexOf("/");
                        if (index != -1) {
                            File df = new File(outputDirectory + separator
                                    + entryName.substring(0, index));
                            df.mkdirs();
                        }
                        File f = new File(outputDirectory + separator + zipEntry.getName());
                        in = zipFile.getInputStream(zipEntry);
                        out = new FileOutputStream(f);
                        int c;
                        byte[] bytes = new byte[1024];
                        while ((c = in.read(bytes)) != -1) {
                            out.write(bytes, 0, c);
                        }
                        out.flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    return false;
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    if (out != null) {
                        try {
                            out.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static boolean isInArray(String str, String... arr) {
        for (String s : arr) {
            if (s.equals(str)) return true;
        }
        return false;
    }
}
