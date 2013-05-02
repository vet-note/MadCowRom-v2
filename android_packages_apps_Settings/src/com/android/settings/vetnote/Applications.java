package com.android.settings.vetnote;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.ExtendedPropertiesUtils;
import android.widget.Toast;

import com.android.settings.R;

public class Applications {

    protected static final String PREFS_NAME = "custom_dpi_groups_preference";
    protected static final String PROPERTY_AUTO_BACKUP = "auto_backup";

    public static class VetnoteAppInfo {

        public String name = "";
        public String pack = "";
        public Drawable icon;
        public ApplicationInfo info;
        public int dpi;
    }

    private static class AppComparator implements Comparator<VetnoteAppInfo> {

        public int compare(VetnoteAppInfo a1, VetnoteAppInfo a2) {
            return a1.name.compareTo(a2.name);
        }
    }

    private static final String BACKUP = "/data/data/com.android.settings/files/properties.back";

    private static final String APPEND_CMD = "echo \"%s=%s\" >> /system/etc/vetnote/properties.conf";
    private static final String REPLACE_CMD = "busybox sed -i \"/%s/ c %<s=%s\" /system/etc/vetnote/properties.conf";
    private static final String PROP_EXISTS_CMD = "grep -q %s /system/etc/vetnote/properties.conf";
    private static final String REMOUNT_CMD = "busybox mount -o %s,remount -t yaffs2 /dev/block/mtdblock1 /system";

    private static final CMDProcessor cmd = new CMDProcessor();

    private static List<VetnoteAppInfo> appList = new ArrayList<VetnoteAppInfo>();
    private static int mLastDpi = 0;

    public static void addApplication(Context mContext, String packageName) {
        addApplication(mContext, findAppInfo(mContext, packageName), mLastDpi);
    }

    public static void addApplication(Context mContext, VetnoteAppInfo app,
            int dpi) {

        if (!mount("rw")) {
            throw new RuntimeException("Could not remount /system rw");
        }
        try {
            if (propExists(app.pack + ".dpi")) {
                cmd.su.runWaitFor(String.format(REPLACE_CMD, app.pack + ".dpi",
                        String.valueOf(dpi)));
            } else {
                cmd.su.runWaitFor(String.format(APPEND_CMD, app.pack + ".dpi",
                        String.valueOf(dpi)));
            }
            if (app.pack.equals("com.android.systemui")) {
                Utils.restartUI();
            } else {
                try {
                    IActivityManager am = ActivityManagerNative.getDefault();
                    am.forceStopPackage(app.pack, UserHandle.myUserId());
                } catch (android.os.RemoteException ex) {
                    // ignore
                }
            }
        } finally {
            mount("ro");
        }
        checkAutoBackup(mContext);
    }

    public static void addSystem(Context mContext, int dpi) {

        if (!mount("rw")) {
            throw new RuntimeException("Could not remount /system rw");
        }
        try {
            if (propExists("android.dpi")) {
                cmd.su.runWaitFor(String.format(REPLACE_CMD, "android.dpi",
                        String.valueOf(dpi)));
            } else {
                cmd.su.runWaitFor(String.format(APPEND_CMD, "android.dpi",
                        String.valueOf(dpi)));
            }
            if (propExists("com.android.systemui.dpi")) {
                cmd.su.runWaitFor(String.format(REPLACE_CMD,
                        "com.android.systemui.dpi", String.valueOf(dpi)));
            } else {
                cmd.su.runWaitFor(String.format(APPEND_CMD,
                        "com.android.systemui.dpi", String.valueOf(dpi)));
            }
            ExtendedPropertiesUtils.refreshProperties();
            Utils.restartUI();
        } finally {
            mount("ro");
        }
        checkAutoBackup(mContext);
    }

    public static void addSystemLayout(Context mContext, String layout) {

        if (!mount("rw")) {
            throw new RuntimeException("Could not remount /system rw");
        }
        try {
            if (propExists("android.layout")) {
                cmd.su.runWaitFor(String.format(REPLACE_CMD, "android.layout",
                        layout));
            } else {
                cmd.su.runWaitFor(String.format(APPEND_CMD, "android.layout",
                        layout));
            }
            if (propExists("com.android.systemui.layout")) {
                cmd.su.runWaitFor(String.format(REPLACE_CMD,
                        "com.android.systemui.layout", layout));
            } else {
                cmd.su.runWaitFor(String.format(APPEND_CMD,
                        "com.android.systemui.layout", layout));
            }
            ExtendedPropertiesUtils.refreshProperties();
            Utils.restartUI();
        } finally {
            mount("ro");
        }
        checkAutoBackup(mContext);
    }

    public static void addProperty(Context mContext, String property,
            int value, boolean restartui) {

        if (!mount("rw")) {
            throw new RuntimeException("Could not remount /system rw");
        }
        try {
            if (propExists("property")) {
                cmd.su.runWaitFor(String.format(REPLACE_CMD, property, value));
            } else {
                cmd.su.runWaitFor(String.format(APPEND_CMD, property, value));
            }
            ExtendedPropertiesUtils.refreshProperties();
            if (restartui) {
                Utils.restartUI();
            }
        } finally {
            mount("ro");
        }
        checkAutoBackup(mContext);
    }

    public static void removeApplication(Context mContext, String packageName) {
        if (!mount("rw")) {
            throw new RuntimeException("Could not remount /system rw");
        }
        try {
            if (propExists(packageName)) {
                cmd.su.runWaitFor(String.format(REPLACE_CMD, packageName
                        + ".dpi", "0"));
            }
            if (packageName.equals("com.android.systemui")) {
                Utils.restartUI();
            } else {
                try {
                    IActivityManager am = ActivityManagerNative.getDefault();
                    am.forceStopPackage(packageName, UserHandle.myUserId());
                } catch (android.os.RemoteException ex) {
                    // ignore
                }
            }
        } finally {
            mount("ro");
        }
        checkAutoBackup(mContext);
    }

    public static boolean isPartOfSystem(String packageName) {
        return packageName.startsWith(ExtendedPropertiesUtils.VETNOTE_PREFIX) ||
                packageName.startsWith("com.android.systemui.statusbar.") ||
                packageName.startsWith("com.android.systemui.navbar.") ||
                packageName.endsWith(".layout");
    }

    public static VetnoteAppInfo[] getApplicationList(Context mContext, int dpi) {

        mLastDpi = dpi;

        Properties properties = null;

        try {
            properties = new Properties();
            properties.load(new FileInputStream(
                    "/system/etc/vetnote/properties.conf"));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        String sdpi = String.valueOf(dpi);

        List<VetnoteAppInfo> items = new ArrayList<VetnoteAppInfo>();

        Iterator it = properties.keySet().iterator();
        while (it.hasNext()) {
            String packageName = (String) it.next();
            String currentdpi = properties.getProperty(packageName);
            if (packageName.endsWith(".dpi") && sdpi.equals(currentdpi)) {
                VetnoteAppInfo bAppInfo = findAppInfo(mContext, packageName);

                if (bAppInfo == null) {
                    removeApplication(
                            mContext,
                            packageName.substring(0,
                                    packageName.lastIndexOf(".dpi")));
                } else {
                    items.add(bAppInfo);
                }
            }
        }

        Collections.sort(items, new AppComparator());

        return items.toArray(new VetnoteAppInfo[items.size()]);
    }

    public static VetnoteAppInfo[] getApplicationList(Context mContext) {

        Properties properties = null;

        try {
            properties = new Properties();
            properties.load(new FileInputStream(
                    "/system/etc/vetnote/properties.conf"));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        PackageManager pm = mContext.getPackageManager();

        List<ApplicationInfo> mPackageList = pm.getInstalledApplications(0);
        VetnoteAppInfo[] items = new VetnoteAppInfo[mPackageList == null ? 0
                : mPackageList.size()];

        appList.clear();

        for (int i = 0; mPackageList != null && i < mPackageList.size(); i++) {
            ApplicationInfo app = mPackageList.get(i);
            items[i] = new VetnoteAppInfo();
            items[i].name = (String) pm.getApplicationLabel(app);
            items[i].icon = pm.getApplicationIcon(app);
            items[i].pack = app.packageName;
            items[i].info = app;
            items[i].dpi = properties.getProperty(app.packageName) == null ? 0
                    : Integer.parseInt(properties.getProperty(app.packageName));
            appList.add(items[i]);
        }
        Arrays.sort(items, new AppComparator());
        return items;
    }

    public static void backup(Context mContext) {
        Utils.execute(new String[] { "cd /data/data/com.android.settings",
                "mkdir files", "chmod 777 files",
                "cp /system/etc/vetnote/properties.conf " + BACKUP,
                "chmod 644 " + BACKUP }, 0);
        Toast.makeText(mContext, R.string.dpi_groups_backup_done,
                Toast.LENGTH_SHORT).show();
    }

    public static void restore(Context mContext) {
        Utils.execute(new String[] { Utils.MOUNT_SYSTEM_RW,
                "cp " + BACKUP + " /system/etc/vetnote/properties.conf",
                "chmod 644 /system/etc/vetnote/properties.conf",
                Utils.MOUNT_SYSTEM_RO }, 0);
        Toast.makeText(mContext, R.string.dpi_groups_restore_done,
                Toast.LENGTH_SHORT).show();
    }

    public static boolean backupExists() {
        return new File(BACKUP).exists();
    }

    private static void checkAutoBackup(Context mContext) {
        boolean isAutoBackup = mContext.getSharedPreferences(
                PREFS_NAME, 0).getBoolean(
                PROPERTY_AUTO_BACKUP, false);
        if (isAutoBackup) {
            backup(mContext);
        }
    }

    private static boolean mount(String read_value) {
        return cmd.su.runWaitFor(String.format(REMOUNT_CMD, read_value))
                .success();
    }

    private static boolean propExists(String prop) {
        return cmd.su.runWaitFor(String.format(PROP_EXISTS_CMD, prop))
                .success();
    }

    private static VetnoteAppInfo findAppInfo(Context mContext,
            String packageName) {
        if (packageName.endsWith(".dpi")) {
            packageName = packageName.substring(0,
                    packageName.lastIndexOf(".dpi"));
        }
        if (appList.size() == 0) {
            getApplicationList(mContext);
        }
        for (int i = 0; i < appList.size(); i++) {
            VetnoteAppInfo app = appList.get(i);
            if (app.pack.equals(packageName))
                return app;
        }
        return null;
    }
}
