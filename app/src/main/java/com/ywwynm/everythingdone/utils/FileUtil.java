package com.ywwynm.everythingdone.utils;

import android.annotation.SuppressLint;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Environment;

import com.ywwynm.everythingdone.Def;

import org.joda.time.DateTime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Created by ywwynm on 2016/3/20.
 * utils for operating {@link File}s
 */
public class FileUtil {

    public static final String TEMP_PATH = Def.Meta.APP_FILE_DIR + "/temp";

    public static File createTempAudioFile(String postfix) {
        File dir = new File(TEMP_PATH + "/audio_raw");
        if (!dir.exists()) {
            boolean parentCreated = dir.mkdirs();
            if (!parentCreated) {
                return null;
            }
        }

        String timeStamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        return new File(dir, timeStamp + postfix);
    }

    public static File createFile(String parentPath, String name) {
        File parent = new File(parentPath);
        if (!parent.exists()) {
            boolean parentCreated = parent.mkdirs();
            if (!parentCreated) {
                return null;
            }
        }
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

    public static String getNameWithoutPostfix(String pathName) {
        String name = new File(pathName).getName();
        int index = name.lastIndexOf(".");
        if (index == -1) {
            return name;
        } else {
            return name.substring(0, index);
        }
    }

    public static String getPostfix(String pathName) {
        int index = pathName.lastIndexOf(".");
        if (index == -1) {
            return "";
        } else {
            return pathName.substring(index + 1, pathName.length());
        }
    }

    public static String getFileSizeStr(File file) {
        final double B  = 1;
        final double KB = 1024 * B;
        final double MB = 1024 * KB;
        final double GB = 1024 * MB;

        long len = file.length();
        double size;
        String unit;
        if (len < B) {
            return "0 byte";
        } else if (len < KB) {
            size = len / B;
            unit = " B";
        } else if (len < MB) {
            size = len / KB;
            unit = " KB";
        } else if (len < GB) {
            size = len / MB;
            unit = " MB";
        } else {
            size = len / GB;
            unit = " GB";
        }

        String sizeFormat = new DecimalFormat("#.00").format(size);
        return sizeFormat + unit;
    }

    public static int[] getImageSize(String pathName) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(pathName, options);
        int[] ret = new int[2];
        ret[0] = options.outWidth;
        ret[1] = options.outHeight;
        return ret;
    }

    public static int[] getVideoSize(String pathName) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(pathName);
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int[] ret = new int[2];
            ret[0] = Integer.parseInt(widthStr);
            ret[1] = Integer.parseInt(heightStr);
            return ret;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            retriever.release();
        }
    }

    public static long getMediaDuration(String pathName) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(pathName);
            String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            return Long.parseLong(durationStr);
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        } finally {
            retriever.release();
        }
    }

    public static DateTime getImageCreateTime(String pathName) {
        try {
            ExifInterface exif = new ExifInterface(pathName);
            String datetimeStr = exif.getAttribute(ExifInterface.TAG_DATETIME);

            String[] datetime = datetimeStr.split(" ");
            String[] dates = datetime[0].split(":");
            int year = Integer.parseInt(dates[0]);
            int month = Integer.parseInt(dates[1]);
            int day = Integer.parseInt(dates[2]);

            String[] times = datetime[1].split(":");
            int hour = Integer.parseInt(times[0]);
            int minute = Integer.parseInt(times[1]);
            int second = Integer.parseInt(times[2]);

            return new DateTime(year, month, day, hour, minute, second);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static DateTime getVideoCreateTime(String pathName) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(pathName);
            String timeStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DATE);
            System.out.println(timeStr);
            // 20160417T112003.000Z
            int year   = Integer.parseInt(timeStr.substring(0, 4));
            int month  = Integer.parseInt(timeStr.substring(4, 6));
            int day    = Integer.parseInt(timeStr.substring(6, 8));
            int hour   = Integer.parseInt(timeStr.substring(9, 11));
            int minute = Integer.parseInt(timeStr.substring(11, 13));
            int second = Integer.parseInt(timeStr.substring(13, 15));
            return new DateTime(year, month, day, hour, minute, second);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            retriever.release();
        }
    }

    public static int getAudioBitrate(String pathName) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(pathName);
            int bitrate = Integer.parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_BITRATE));
            return bitrate / 1000;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        } finally {
            retriever.release();
        }
    }

    public static int getAudioSampleRate(String pathName) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(pathName);
            MediaFormat mf = extractor.getTrackFormat(0);
            return mf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        } finally {
            extractor.release();
        }
    }

    public static void copyFile(File src, File dst) throws IOException {
        BufferedInputStream bis = new BufferedInputStream(new FileInputStream(src));
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
            } else {
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

    /**
     * @return A map of all storage locations available
     * @see http://stackoverflow.com/a/15612964/3952691
     */
    @SuppressLint("SdCardPath")
    public static List<String> getAllStorageLocations() {
        List<String> ret = new ArrayList<>();

        List<String> mounts = new ArrayList<>();
        List<String> volds  = new ArrayList<>();
        mounts.add("/mnt/sdcard");
        volds.add("/mnt/sdcard");

        try {
            File mountFile = new File("/proc/mounts");
            if (mountFile.exists()) {
                Scanner scanner = new Scanner(mountFile);
                while (scanner.hasNext()) {
                    String line = scanner.nextLine();
                    if (line.startsWith("/dev/block/vold/")) {
                        String[] lineElements = line.split(" ");
                        String element = lineElements[1];

                        // don't add the default mount path
                        // it's already in the list.
                        if (!element.equals("/mnt/sdcard")) {
                            mounts.add(element);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            File voldFile = new File("/system/etc/vold.fstab");
            if (voldFile.exists()) {
                Scanner scanner = new Scanner(voldFile);
                while (scanner.hasNext()) {
                    String line = scanner.nextLine();
                    if (line.startsWith("dev_mount")) {
                        String[] lineElements = line.split(" ");
                        String element = lineElements[2];

                        if (element.contains(":"))
                            element = element.substring(0, element.indexOf(":"));
                        if (!element.equals("/mnt/sdcard"))
                            volds.add(element);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Iterator<String> itr = mounts.iterator();
        while (itr.hasNext()) {
            String mount = itr.next();
            if (!volds.contains(mount)) {
                itr.remove();
            }
        }
        volds.clear();

        List<String> mountHash = new ArrayList<>(10);

        for (String mount : mounts) {
            File root = new File(mount);
            if (root.exists() && root.isDirectory() && root.canWrite()) {
                File[] list = root.listFiles();
                String hash = "[";
                if (list != null) {
                    for (File f : list) {
                        hash += f.getName().hashCode() + ":" + f.length() + ", ";
                    }
                }
                hash += "]";
                if (!mountHash.contains(hash)) {
                    mountHash.add(hash);
                    ret.add(mount);
                }
            }
        }

        mounts.clear();

        ret.add(Environment.getExternalStorageDirectory().getAbsolutePath());
        ret.add("/sdcard2/");
        ret.add("/sdcard3/");
        ret.add("/sdcard4/");
        ret.add("/sdcard5/");
        ret.add("/sdcard6/");
        ret.add("/storage/");

        return ret;
    }

    private static boolean isInArray(String str, String... arr) {
        for (String s : arr) {
            if (s.equals(str)) return true;
        }
        return false;
    }
}
