package com.ywwynm.everythingdone.helpers;

import com.ywwynm.everythingdone.App;
import com.ywwynm.everythingdone.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by ywwynm on 2015/9/17.
 * Convert content string into check list items and vice versa.
 */
public class CheckListHelper {

    public static final String TAG = "EverythingDone$CheckListHelper";

    public static final int SIGNAL_LENGTH   = 4;
    public static final int CHECK_STATE_NUM = 5;

    // cannot write hardcoded signal after updated to Jack compiler with Java 8
    public static final String SIGNAL = App.getApp().getString(R.string.checklist_signal);

    public static boolean isCheckListStr(String s) {
        return s.length() >= SIGNAL_LENGTH && s.substring(0, SIGNAL_LENGTH).equals(SIGNAL);
    }

    public static List<String> toCheckListItems(String s, boolean convert) {
        if (convert) {
            s = toCheckListStr(s);
        }
        String[] strs = s.split(SIGNAL);
        List<String> items = new ArrayList<>();
        items.addAll(Arrays.asList(strs).subList(1, strs.length));

        int firstFinishedIndex = -1, size = items.size();
        for (int i = 0; i < size; i++) {
            if (items.get(i).startsWith("1")) {
                firstFinishedIndex = i;
                break;
            }
        }
        if (firstFinishedIndex != -1) {
            items.add(firstFinishedIndex, "2");
            items.add(firstFinishedIndex + 1, "3");
            items.add(firstFinishedIndex + 2, "4");
        } else {
            items.add("2");
        }

        return items;
    }

    public static String toContentStr(List<String> items) {
        String checkListStr = toCheckListStr(items);
        return toContentStr(checkListStr, "", "");
    }

    public static String toContentStr(String checkListStr, String unchecked, String checked) {
        if (!checkListStr.contains(SIGNAL + 0) && !checkListStr.contains(SIGNAL + 1)) {
            return "";
        } else {
            char signal = checkListStr.charAt(SIGNAL_LENGTH);
            String result = checkListStr.substring(SIGNAL_LENGTH + 1, checkListStr.length());
            if (signal == '0') {
                result = unchecked + result;
            } else result = checked + result;

            result = result.replaceAll(SIGNAL + 0, "\n" + unchecked);
            result = result.replaceAll(SIGNAL + 1, "\n" + checked);
            result = result.replace(SIGNAL + 2, "");
            result = result.replace(SIGNAL + 3, "");
            result = result.replace(SIGNAL + 4, "");

            return result;
        }
    }

    public static String toCheckListStr(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (s.startsWith("0") || s.startsWith("1")) {
                sb.append(SIGNAL).append(s);
            }
        }
        return sb.toString();
    }

    public static String toCheckListStr(String content) {
        return SIGNAL + 0 + content.replaceAll("\n", SIGNAL + 0);
    }

    public static int getFirstFinishedItemIndex(List<String> items) {
        int size = items.size();
        for (int i = 0; i < size; i++) {
            if (items.get(i).startsWith("1")) {
                return i;
            }
        }
        return -1;
    }

    public static boolean onlyOneFinishedItem(List<String> items) {
        int count = 0;
        for (String item : items) {
            if (item.startsWith("1")) {
                count++;
                if (count > 1) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isSignalContainsStrIgnoreCase(String str) {
        if (str.isEmpty()) {
            return false;
        }
        for (int i = 0; i < 5; i++) {
            if ((SIGNAL + i).toLowerCase().contains(str.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
