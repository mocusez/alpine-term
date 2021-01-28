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

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.ArrayAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import androidx.core.app.NotificationCompat;

import alpine.term.emulator.TerminalSession;
import alpine.term.emulator.TerminalSession.SessionChangedCallback;

/**
 * A service holding a list of terminal sessions, {@link #mTerminalSessions}, showing a foreground notification while
 * running so that it is not terminated. The user interacts with the session through {@link TerminalActivity}, but this
 * service may outlive the activity when the user or the system disposes of the activity. In that case the user may
 * restart {@link TerminalActivity} later to yet again access the sessions.
 * <p/>
 * In order to keep both terminal sessions and spawned processes (who may outlive the terminal sessions) alive as long
 * as wanted by the user this service is a foreground service, {@link Service#startForeground(int, Notification)}.
 * <p/>
 * Optionally may hold a wake and a wifi lock, in which case that is shown in the notification - see
 * {@link #buildNotification()}.
 */
public class TerminalService extends Service implements SessionChangedCallback {

    private static final String INTENT_ACTION_SERVICE_STOP = "alpine.term.ACTION_STOP_SERVICE";
    private static final String INTENT_ACTION_WAKELOCK_ENABLE = "alpine.term.ACTION_ENABLE_WAKELOCK";
    private static final String INTENT_ACTION_WAKELOCK_DISABLE = "alpine.term.ACTION_DISABLE_WAKELOCK";

    private static final int NOTIFICATION_ID = 1338;
    private static final String NOTIFICATION_CHANNEL_ID = "alpine.term.NOTIFICATION_CHANNEL";

    /**
     * The terminal sessions which this service manages.
     * <p/>
     * Note that this list is observed by {@link TerminalActivity#mListViewAdapter}, so any changes must be made on the UI
     * thread and followed by a call to {@link ArrayAdapter#notifyDataSetChanged()} }.
     */
    private final List<TerminalSession> mTerminalSessions = new ArrayList<>();

    private final IBinder mBinder = new LocalBinder();

    /**
     * Note that the service may often outlive the activity, so need to clear this reference.
     */
    SessionChangedCallback mSessionChangeCallback;

    /**
     * If the user has executed the {@link #INTENT_ACTION_SERVICE_STOP} intent.
     */
    boolean mWantsToStop = false;

    /**
     * The wake lock and wifi lock are always acquired and released together.
     */
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;


    @Override
    public void onCreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.application_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Notifications from " + getString(R.string.application_name));

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }

        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public void onDestroy() {
        if (mWakeLock != null) mWakeLock.release();
        if (mWifiLock != null) mWifiLock.release();

        stopForeground(true);

        for (TerminalSession mTerminalSession : mTerminalSessions) {
            mTerminalSession.finishIfRunning();
        }
    }

    @SuppressLint({"Wakelock", "WakelockTimeout"})
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (INTENT_ACTION_SERVICE_STOP.equals(action)) {
            terminateService();
        } else if (INTENT_ACTION_WAKELOCK_ENABLE.equals(action)) {
            if (mWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Config.WAKELOCK_LOG_TAG);
                mWakeLock.acquire();

                // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, Config.WAKELOCK_LOG_TAG);
                mWifiLock.acquire();

                updateNotification();
            }
        } else if (INTENT_ACTION_WAKELOCK_DISABLE.equals(action)) {
            if (mWakeLock != null) {
                mWakeLock.release();
                mWakeLock = null;

                mWifiLock.release();
                mWifiLock = null;

                updateNotification();
            }
        } else if (action != null) {
            Log.w(Config.APP_LOG_TAG, "received an unknown action for TerminalService: " + action);
        }

        // If this service really do get killed, there is no point restarting it automatically - let the user do on next
        // start of {@link Term):
        return Service.START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onTitleChanged(changedSession);
        }
    }

    @Override
    public void onSessionFinished(final TerminalSession finishedSession) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onSessionFinished(finishedSession);
        }
    }

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onTextChanged(changedSession);
        }
    }

    @Override
    public void onClipboardText(TerminalSession session, String text) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onClipboardText(session, text);
        }
    }

    @Override
    public void onBell(TerminalSession session) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onBell(session);
        }
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
        if (mSessionChangeCallback != null) {
            mSessionChangeCallback.onColorsChanged(session);
        }
    }

    public List<TerminalSession> getSessions() {
        return mTerminalSessions;
    }

    public void terminateService() {
        mWantsToStop = true;

        if (!mTerminalSessions.isEmpty()) {
            for (TerminalSession mTerminalSession : mTerminalSessions) {
                mTerminalSession.finishIfRunning();
            }
        }

        stopSelf();
    }

    private static void addToEnvIfPresent(List<String> environment, String name) {
        String value = System.getenv(name);
        if (value != null) {
            environment.add(name + "=" + value);
        }
    }

    /**
     * Creates a terminal instance with running QEMU.
     * @return a created terminal session that can be attached to TerminalView.
     */
    public TerminalSession createQemuSession() {
        ArrayList<String> environment = new ArrayList<>();
        Context appContext = getApplicationContext();

        String execPath = appContext.getApplicationInfo().nativeLibraryDir;
        String runtimeDataPath = Config.getDataDirectory(appContext);

        String workingDirPath;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            workingDirPath = Objects.requireNonNull(appContext.getExternalFilesDir(null)).getAbsolutePath();
        } else {
            workingDirPath = appContext.getFilesDir().getAbsolutePath() + "/workdir";
            File wd = new File(workingDirPath);
            if (!wd.exists()) {
                if (!wd.mkdir()) {
                    Log.e(Config.APP_LOG_TAG, "directory " + workingDirPath + " was not created");
                }
            }
        }

        environment.add("ANDROID_ROOT=" + System.getenv("ANDROID_ROOT"));
        environment.add("ANDROID_DATA=" + System.getenv("ANDROID_DATA"));
        environment.add("APP_RUNTIME_DIR=" + runtimeDataPath);
        environment.add("LANG=en_US.UTF-8");
        environment.add("HOME=" + workingDirPath);
        environment.add("PATH=" + execPath);
        environment.add("TMPDIR=" + Config.getTemporaryDirectory(appContext));

        // Used by QEMU internal DNS.
        environment.add("CONFIG_QEMU_DNS=" + Config.QEMU_UPSTREAM_DNS);

        // Variables present on Android 10 or higher.
        addToEnvIfPresent(environment, "ANDROID_ART_ROOT");
        addToEnvIfPresent(environment, "ANDROID_I18N_ROOT");
        addToEnvIfPresent(environment, "ANDROID_RUNTIME_ROOT");
        addToEnvIfPresent(environment, "ANDROID_TZDATA_ROOT");

        ArrayList<String> processArgs = new ArrayList<>();
        processArgs.add(execPath + "/libqemu.so");

        // QEMU instance name (used by VNC).
        processArgs.addAll(Arrays.asList("-name", "Alpine Term"));

        // Path to directory with firmware & keymap files.
        processArgs.addAll(Arrays.asList("-L", runtimeDataPath + "/qemu-data"));

        // Emulate CPU with max feature set.
        processArgs.addAll(Arrays.asList("-cpu", "max"));

        // Emulate up to 4 cores.
        processArgs.addAll(Arrays.asList("-smp", "cpus=4,cores=4,threads=1,sockets=1"));

        // Determine safe values for VM RAM allocation.
        ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        if (am != null) {
            am.getMemoryInfo(memInfo);
            // 32% of host memory will be used for QEMU emulated RAM.
            int safeRam = (int) (memInfo.totalMem * 0.32 / 1048576);
            // 8% of host memory will be used for QEMU TCG buffer.
            int safeTcg = (int) (memInfo.totalMem * 0.08 / 1048576);
            processArgs.addAll(Arrays.asList("-m", safeRam + "M", "-accel", "tcg,tb-size=" + safeTcg));
        } else {
            // Fallback.
            Log.e(Config.APP_LOG_TAG, "failed to determine size of host memory");
            processArgs.addAll(Arrays.asList("-m", "256M", "-accel", "tcg,tb-size=64"));
        }

        // Balloon device for dynamic RAM allocation.
        processArgs.addAll(Arrays.asList("-device", "virtio-balloon"));

        // Do not create default devices.
        processArgs.add("-nodefaults");

        // SCSI CD-ROM(s) and HDD(s).
        String customCdromImage = workingDirPath + "/" + "cdrom.iso";
        String customHddImage = workingDirPath + "/" + "hdd.qcow2";
        if (!new File(customCdromImage).exists()) {
            customCdromImage = null;
        }
        if (!new File(customHddImage).exists()) {
            customHddImage = null;
        }
        processArgs.addAll(Arrays.asList("-drive", "file=" + runtimeDataPath + "/" + Config.CDROM_IMAGE_NAME + ",if=none,media=cdrom,index=0,id=cd0"));
        if (customCdromImage != null) processArgs.addAll(Arrays.asList("-drive", "file=" + customCdromImage + ",if=none,media=cdrom,index=1,id=cd1"));
        processArgs.addAll(Arrays.asList("-drive", "file=" + runtimeDataPath + "/" + Config.HDD_IMAGE_NAME + ",if=none,index=3,discard=unmap,detect-zeroes=unmap,cache=writeback,id=hd0"));
        if (customHddImage != null) processArgs.addAll(Arrays.asList("-drive", "file=" + customHddImage + ",if=none,index=4,discard=unmap,detect-zeroes=unmap,cache=writeback,id=hd1"));
        processArgs.addAll(Arrays.asList("-device", "virtio-scsi-pci,id=virtio-scsi-pci0"));
        processArgs.addAll(Arrays.asList("-device", "scsi-cd,bus=virtio-scsi-pci0.0,id=scsi-cd0,drive=cd0"));
        if (customCdromImage != null) processArgs.addAll(Arrays.asList("-device", "scsi-cd,bus=virtio-scsi-pci0.0,id=scsi-cd1,drive=cd1"));
        processArgs.addAll(Arrays.asList("-device", "scsi-hd,bus=virtio-scsi-pci0.0,id=scsi-hd0,drive=hd0"));
        if (customHddImage != null) processArgs.addAll(Arrays.asList("-device", "scsi-hd,bus=virtio-scsi-pci0.0,id=scsi-hd1,drive=hd1"));

        // Boot from HDD by default, but allow to open device menu.
        processArgs.addAll(Arrays.asList("-boot", "c,menu=on"));

        // Setup random number generator.
        processArgs.addAll(Arrays.asList("-object", "rng-random,filename=/dev/urandom,id=rng0"));
        processArgs.addAll(Arrays.asList("-device", "virtio-rng-pci,rng=rng0,id=virtio-rng-pci0"));

        // Networking.
        processArgs.addAll(Arrays.asList("-netdev", "user,id=vmnic0"));
        processArgs.addAll(Arrays.asList("-device", "virtio-net-pci,netdev=vmnic0,id=virtio-net-pci0"));

        // Access to shared storage.
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            processArgs.addAll(Arrays.asList("-fsdev", "local,security_model=none,id=fsdev0,multidevs=remap,path=/storage/self/primary"));
            processArgs.addAll(Arrays.asList("-device", "virtio-9p-pci,fsdev=fsdev0,mount_tag=shared_storage,id=virtio-9p-pci0"));
        }

        // We need only monitor & serial consoles.
        processArgs.add("-nographic");

        // Use graphics adapter but have VNC disabled by default.
        processArgs.addAll(Arrays.asList("-device", "VGA,id=vga-pci0,vgamem_mb=32", "-vnc", "none"));

        // Use usb tablet as pointer device as PS/2 mouse has issues with VNC.
        processArgs.addAll(Arrays.asList("-device", "qemu-xhci,id=qemu-xhci-pci0"));
        processArgs.addAll(Arrays.asList("-device", "usb-tablet,bus=qemu-xhci-pci0.0,id=usb-tablet0"));

        // Explicitly specify that only EN-US keyboard supported.
        // This option is used by VNC.
        processArgs.addAll(Arrays.asList("-k", "en-us"));

        // Disable parallel port.
        processArgs.addAll(Arrays.asList("-parallel", "none"));

        // Monitor console.
        processArgs.addAll(Arrays.asList("-chardev", "stdio,id=monitor0,mux=off,signal=off"));
        processArgs.addAll(Arrays.asList("-monitor", "chardev:monitor0"));

        // 4 serial consoles.
        for (int i=0; i<4; i++) {
            processArgs.addAll(Arrays.asList("-chardev", "socket,server,nowait,id=console" + i + ",path=" + runtimeDataPath + "/.qemu" + i));
            processArgs.addAll(Arrays.asList("-serial", "chardev:console" + i));
        }

        Log.i(Config.APP_LOG_TAG, "initiating QEMU session with following arguments: " + processArgs.toString());

        TerminalSession session = new TerminalSession(execPath + "/libqemu.so", processArgs.toArray(new String[0]), environment.toArray(new String[0]), workingDirPath, this);
        mTerminalSessions.add(session);
        updateNotification();

        return session;
    }

    /**
     * Creates terminal instance with running 'socat'. Used for connecting to QEMU sockets.
     * @param sessionNumber a number used to select a correct QEMU socket. Allowed values are 0-3.
     * @return              a created terminal session that can be attached to TerminalView.
     */
    public TerminalSession createSocatSession(int sessionNumber) {
        ArrayList<String> environment = new ArrayList<>();
        Context appContext = getApplicationContext();

        String execPath = appContext.getApplicationInfo().nativeLibraryDir;
        String runtimeDataPath = Config.getDataDirectory(appContext);

        environment.add("ANDROID_ROOT=" + System.getenv("ANDROID_ROOT"));
        environment.add("ANDROID_DATA=" + System.getenv("ANDROID_DATA"));
        environment.add("PREFIX=" + runtimeDataPath);
        environment.add("LANG=en_US.UTF-8");
        environment.add("HOME=" + runtimeDataPath);
        environment.add("PATH=" + execPath);
        environment.add("TMPDIR=" + Config.getTemporaryDirectory(appContext));

        ArrayList<String> processArgs = new ArrayList<>();
        processArgs.add(execPath + "/libsocat.so");
        processArgs.add("/dev/tty,rawer");
        processArgs.add("UNIX-CONNECT:" + runtimeDataPath + "/.qemu" + sessionNumber + ",interval=0.1,forever");

        Log.i(Config.APP_LOG_TAG, "initiating socat session with following arguments: " + processArgs.toString());

        TerminalSession session = new TerminalSession(execPath + "/libsocat.so", processArgs.toArray(new String[0]), environment.toArray(new String[0]), runtimeDataPath, this);
        mTerminalSessions.add(session);
        updateNotification();

        return session;
    }

    private Notification buildNotification() {
        Intent notifyIntent = new Intent(this, TerminalActivity.class);
        // PendingIntent#getActivity(): "Note that the activity will be started outside of the context of an existing
        // activity, so you must use the Intent.FLAG_ACTIVITY_NEW_TASK launch flag in the Intent":
        notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notifyIntent, 0);

        StringBuilder contentText = new StringBuilder();

        if (!mTerminalSessions.isEmpty()) {
            contentText.append("Virtual machine is running.");
        } else {
            contentText.append("Virtual machine is not initialized.");
        }

        final boolean wakeLockHeld = mWakeLock != null;

        if (wakeLockHeld) {
            contentText.append(" Wake lock held.");
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        builder.setContentIntent(pendingIntent);
        builder.setContentTitle(getText(R.string.application_name));
        builder.setContentText(contentText.toString());
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setPriority(NotificationCompat.PRIORITY_HIGH);
        builder.setOngoing(true);

        // No need to show a timestamp:
        builder.setShowWhen(false);

        // Background color for small notification icon:
        builder.setColor(0xFF000000);

        String newWakeAction = wakeLockHeld ? INTENT_ACTION_WAKELOCK_DISABLE : INTENT_ACTION_WAKELOCK_ENABLE;
        Intent toggleWakeLockIntent = new Intent(this, TerminalService.class).setAction(newWakeAction);
        String actionTitle = getResources().getString(wakeLockHeld ?
            R.string.notification_action_wake_unlock :
            R.string.notification_action_wake_lock);
        int actionIcon = wakeLockHeld ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_lock_lock;
        builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, 0));

        return builder.build();
    }

    /**
     * Update the shown foreground service notification after making any changes that affect it.
     */
    private void updateNotification() {
        if (mTerminalSessions.isEmpty()) {
            // Exit if we are updating after the user disabled all locks with no sessions or tasks running.
            stopSelf();
        } else {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification());
        }
    }

    /**
     * This service is only bound from inside the same process and never uses IPC.
     */
    public class LocalBinder extends Binder {
        public final TerminalService service = TerminalService.this;
    }
}
