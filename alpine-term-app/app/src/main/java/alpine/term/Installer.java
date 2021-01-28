/*
*************************************************************************
Alpine Term - a VM-based terminal emulator.
Copyright (C) 2019-2021  Leonid Pliushch <leonid.pliushch@gmail.com>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*************************************************************************
*/
package alpine.term;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.AssetManager;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runtime data installer for data assets embedded into APK.
 */
@SuppressWarnings("WeakerAccess")
public class Installer {

    /**
     * Performs installation of runtime data if necessary.
     */
    public static void setupIfNeeded(final Activity activity, final Runnable whenDone) {
        final File PREFIX_DIR = new File(Config.getDataDirectory(activity));

        if (PREFIX_DIR.isDirectory()) {
            whenDone.run();
            return;
        }

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(activity);
        dialogBuilder.setCancelable(false);
        dialogBuilder.setView(R.layout.installer_progress);

        final AlertDialog progress = dialogBuilder.create();
        progress.show();

        new Thread() {
            @Override
            public void run() {
                try {
                    final File STAGING_PREFIX_DIR = new File(Config.getDataDirectory(activity) + ".staging");

                    if (STAGING_PREFIX_DIR.exists()) {
                        Log.i(Config.INSTALLER_LOG_TAG, "deleting directory " + STAGING_PREFIX_DIR.getAbsolutePath());
                        deleteFolder(STAGING_PREFIX_DIR);
                    }

                    final byte[] buffer = new byte[16384];
                    AssetManager assetManager = activity.getAssets();

                    Log.i(Config.INSTALLER_LOG_TAG, "extracting runtime data");

                    try (ZipInputStream zipInput = new ZipInputStream(assetManager.open("vm_data/" + Config.QEMU_DATA_PACKAGE))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            String zipEntryName = zipEntry.getName();
                            File targetFile = new File(STAGING_PREFIX_DIR, zipEntryName);

                            if (zipEntry.isDirectory()) {
                                Log.i(Config.INSTALLER_LOG_TAG, "creating directory " + targetFile.getAbsolutePath());
                                if (!targetFile.mkdirs())
                                    throw new RuntimeException("failed to create directory: " + targetFile.getAbsolutePath());
                            } else {
                                Log.i(Config.INSTALLER_LOG_TAG, "writing " + targetFile.getAbsolutePath());
                                try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                    int readBytes;
                                    while ((readBytes = zipInput.read(buffer)) != -1) {
                                        outStream.write(buffer, 0, readBytes);
                                    }
                                    outStream.flush();
                                }
                            }
                        }
                    }

                    // Extract HDD image for QEMU.
                    Log.i(Config.INSTALLER_LOG_TAG, "writing " + STAGING_PREFIX_DIR + "/" + Config.HDD_IMAGE_NAME);
                    try (InputStream inStream = assetManager.open("vm_data/" + Config.HDD_IMAGE_NAME)) {
                        try (FileOutputStream outStream = new FileOutputStream(new File(STAGING_PREFIX_DIR, Config.HDD_IMAGE_NAME))) {
                            int readBytes;
                            while ((readBytes = inStream.read(buffer)) != -1) {
                                outStream.write(buffer, 0, readBytes);
                            }
                            outStream.flush();
                        }
                    }

                    // Extract CD-ROM image for QEMU.
                    Log.i(Config.INSTALLER_LOG_TAG, "writing " + STAGING_PREFIX_DIR + "/" + Config.CDROM_IMAGE_NAME);
                    try (InputStream inStream = assetManager.open("vm_data/" + Config.CDROM_IMAGE_NAME)) {
                        try (FileOutputStream outStream = new FileOutputStream(new File(STAGING_PREFIX_DIR, Config.CDROM_IMAGE_NAME))) {
                            int readBytes;
                            while ((readBytes = inStream.read(buffer)) != -1) {
                                outStream.write(buffer, 0, readBytes);
                            }
                            outStream.flush();
                        }
                    }

                    if (!STAGING_PREFIX_DIR.renameTo(PREFIX_DIR)) {
                        throw new RuntimeException("unable to rename staging folder");
                    } else {
                        Log.i(Config.INSTALLER_LOG_TAG, "finished extracting runtime data");
                    }

                    activity.runOnUiThread(whenDone);
                } catch (final Exception e) {
                    Log.e(Config.INSTALLER_LOG_TAG, "runtime data installation failed", e);
                    activity.runOnUiThread(() -> {
                        try {
                            new AlertDialog.Builder(activity)
                                .setTitle(R.string.installer_error_title)
                                .setMessage(R.string.installer_error_body)
                                .setNegativeButton(R.string.exit_label, (dialog, which) -> {
                                    dialog.dismiss();
                                    activity.finish();
                                }).setPositiveButton(R.string.installer_error_try_again_button, (dialog, which) -> {
                                dialog.dismiss();
                                Installer.setupIfNeeded(activity, whenDone);
                            }).show();
                        } catch (WindowManager.BadTokenException e1) {
                            // Activity already dismissed - ignore.
                        }
                    });
                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    /**
     * Delete a folder and all its content or throw. Don't follow symlinks.
     */
    private static void deleteFolder(File fileOrDirectory) throws IOException {
        if (!isSymlink(fileOrDirectory) && fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();

            if (children != null) {
                for (File child : children) {
                    deleteFolder(child);
                }
            }
        }

        if (!fileOrDirectory.delete()) {
            throw new RuntimeException("unable to delete " +
                (fileOrDirectory.isDirectory() ? "directory " : "file ") + fileOrDirectory.getAbsolutePath());
        } else {
            Log.i(Config.INSTALLER_LOG_TAG, "successfully deleted " + fileOrDirectory.getAbsolutePath());
        }
    }

    /**
     * Returns true if file is a symlink and false otherwise.
     */
    private static boolean isSymlink(File file) throws IOException {
        File canon;

        if (file.getParentFile() == null) {
            canon = file;
        } else {
            File canonDir = file.getParentFile().getCanonicalFile();
            canon = new File(canonDir, file.getName());
        }

        return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
    }
}
