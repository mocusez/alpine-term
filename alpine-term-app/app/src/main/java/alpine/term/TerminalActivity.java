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

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import alpine.term.emulator.TerminalColors;
import alpine.term.emulator.TerminalSession;
import alpine.term.emulator.TerminalSession.SessionChangedCallback;
import alpine.term.emulator.TextStyle;
import alpine.term.terminal_view.TerminalView;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TerminalActivity extends Activity implements ServiceConnection {

    private static final int CONTEXTMENU_VNC_VIEWER = 0;
    private static final int CONTEXTMENU_SHOW_HELP = 1;
    private static final int CONTEXTMENU_SELECT_URL_ID = 2;
    private static final int CONTEXTMENU_SHARE_TRANSCRIPT_ID = 3;
    private static final int CONTEXTMENU_PASTE_ID = 4;
    private static final int CONTEXTMENU_RESET_TERMINAL_ID = 5;
    private static final int CONTEXTMENU_CONSOLE_STYLE = 6;
    private static final int CONTEXTMENU_TOGGLE_IGNORE_BELL = 7;

    private final int MAX_FONTSIZE = 256;
    private int MIN_FONTSIZE;
    private static int currentFontSize = -1;

    /**
     * The main view of the activity showing the terminal. Initialized in onCreate().
     */
    TerminalView mTerminalView;

    ExtraKeysView mExtraKeysView;

    TerminalPreferences mSettings;

    /**
     * The connection to the {@link TerminalService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TerminalService mTermService;

    /**
     * Initialized in {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    ArrayAdapter<TerminalSession> mListViewAdapter;

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    private Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        mSettings = new TerminalPreferences(this);

        setContentView(R.layout.drawer_layout);
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setOnKeyListener(new InputDispatcher(this));

        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics());
        int defaultFontSize = Math.round(7.5f * dipInPixels);

        // Make it divisible by 2 since that is the minimal adjustment step:
        if (defaultFontSize % 2 == 1) defaultFontSize--;

        if (TerminalActivity.currentFontSize == -1) {
            TerminalActivity.currentFontSize = defaultFontSize;
        }

        // This is a bit arbitrary and sub-optimal. We want to give a sensible default for minimum
        // font size to prevent invisible text due to zoom be mistake:
        MIN_FONTSIZE = (int) (4f * dipInPixels);

        TerminalActivity.currentFontSize = Math.max(MIN_FONTSIZE, Math.min(TerminalActivity.currentFontSize, MAX_FONTSIZE));
        mTerminalView.setTextSize(TerminalActivity.currentFontSize);

        mTerminalView.setKeepScreenOn(true);
        mTerminalView.requestFocus();
        mTerminalView.setTypeface(Typeface.createFromAsset(getAssets(), "console_font.ttf"));
        reloadTerminalStyling();

        final ViewPager viewPager = findViewById(R.id.viewpager);
        if (mSettings.isExtraKeysEnabled()) viewPager.setVisibility(View.VISIBLE);

        viewPager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return 2;
            }

            @Override
            public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                return view == object;
            }

            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup collection, int position) {
                LayoutInflater inflater = LayoutInflater.from(TerminalActivity.this);
                View layout;

                if (position == 0) {
                    layout = mExtraKeysView = (ExtraKeysView) inflater.inflate(R.layout.extra_keys_main, collection, false);
                } else {
                    layout = inflater.inflate(R.layout.extra_keys_right, collection, false);
                    final EditText editText = layout.findViewById(R.id.text_input);

                    editText.setOnEditorActionListener((v, actionId, event) -> {
                        TerminalSession session = mTerminalView.getCurrentSession();

                        if (session != null) {
                            if (session.isRunning()) {
                                String textToSend = editText.getText().toString();

                                if (textToSend.length() == 0) {
                                    textToSend = "\r";
                                }

                                session.write(textToSend);
                            }

                            editText.setText("");
                        }

                        return true;
                    });
                }

                collection.addView(layout);

                return layout;
            }

            @Override
            public void destroyItem(@NonNull ViewGroup collection, int position, @NonNull Object view) {
                collection.removeView((View) view);
            }
        });

        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (position == 0) {
                    mTerminalView.requestFocus();
                } else {
                    final EditText editText = viewPager.findViewById(R.id.text_input);

                    if (editText != null) {
                        editText.requestFocus();
                    }
                }
            }
        });

        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
            getDrawer().closeDrawers();
        });
        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleShowExtraKeys();
            return true;
        });

        registerForContextMenu(mTerminalView);

        boolean hasStoragePermission = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // On Android 11 we need to deal with MANAGE_EXTERNAL_STORAGE permission to overcome
            // the scoped storage restrictions.
            // Ref: https://developer.android.com/about/versions/11/privacy/storage#all-files-access
            // Ref: https://developer.android.com/training/data-storage/manage-all-files
            if (Environment.isExternalStorageManager()) {
                hasStoragePermission = true;
            }
        } else {
            // Otherwise use a regular permission WRITE_EXTERNAL_STORAGE.
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
                hasStoragePermission = true;
            }
        }

        // Ensure that application can manage storage.
        if (!hasStoragePermission) {
            startActivity(new Intent(this, StoragePermissionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }

        Intent serviceIntent = new Intent(this, TerminalService.class);
        // Start the service and make it run regardless of who is bound to it:
        startService(serviceIntent);
        if (!bindService(serviceIntent, this, 0)) {
            throw new RuntimeException("bindService() failed");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mIsVisible = true;

        if (mTermService != null) {
            // The service has connected, but data may have changed since we were last in the foreground.
            switchToSession(getStoredCurrentSessionOrLast());
            mListViewAdapter.notifyDataSetChanged();
        }

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal:
        mTerminalView.onScreenUpdated();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mIsVisible = false;
        TerminalSession currentSession = mTerminalView.getCurrentSession();
        if (currentSession != null) TerminalPreferences.storeCurrentSession(this, currentSession);
        getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mTermService != null) {
            // Do not leave service with references to activity.
            mTermService.mSessionChangeCallback = null;
            mTermService = null;
            unbindService(this);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = mTerminalView.getCurrentSession();

        if (currentSession == null) {
            return;
        }

        PackageManager pm = getPackageManager();

        try {
            PackageInfo info = pm.getPackageInfo("com.realvnc.viewer.android", PackageManager.GET_META_DATA);

            if (info.packageName.equals("com.realvnc.viewer.android")) {
                menu.add(Menu.NONE, CONTEXTMENU_VNC_VIEWER, Menu.NONE, R.string.menu_vnc_viewer);
            }
        } catch (Exception e) {
            Log.i(Config.APP_LOG_TAG, "VNC viewer is not installed");
        }

        menu.add(Menu.NONE, CONTEXTMENU_SHOW_HELP, Menu.NONE, R.string.menu_show_help);
        menu.add(Menu.NONE, CONTEXTMENU_SELECT_URL_ID, Menu.NONE, R.string.menu_select_url);
        menu.add(Menu.NONE, CONTEXTMENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.menu_share_transcript);
        menu.add(Menu.NONE, CONTEXTMENU_RESET_TERMINAL_ID, Menu.NONE, R.string.menu_reset_terminal);
        menu.add(Menu.NONE, CONTEXTMENU_CONSOLE_STYLE, Menu.NONE, R.string.menu_console_style);
        menu.add(Menu.NONE, CONTEXTMENU_TOGGLE_IGNORE_BELL, Menu.NONE, R.string.menu_toggle_ignore_bell).setCheckable(true).setChecked(mSettings.isBellIgnored());
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = mTerminalView.getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXTMENU_VNC_VIEWER: {
                    int vncPort = -1;

                    for (int i=0; i<32; i++) {
                        try (ServerSocket sock = new ServerSocket(5900 + i)) {
                            sock.setReuseAddress(true);
                            vncPort = sock.getLocalPort();
                            break;
                        } catch (Exception e) {
                            Log.w(Config.APP_LOG_TAG, "cannot acquire port " + (5900 + i) + " for VNC", e);
                        }
                    }

                    if (vncPort == -1) {
                        showToast(getResources().getString(R.string.open_vnc_config_failure), true);
                        Log.e(Config.APP_LOG_TAG, "failed to found a suitable port for VNC server");
                    } else {
                        int vncDisplay = vncPort - 5900;

                        mTermService.getSessions().get(0).write("change vnc 127.0.0.1:" + vncDisplay + "\n");

                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("vnc://127.0.0.1:" + vncPort));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                        try {
                            startActivity(intent);
                        } catch (Exception e) {
                            showToast(getResources().getString(R.string.open_vnc_intent_failure), true);
                            Log.e(Config.APP_LOG_TAG, "failed to start intent", e);
                        }
                    }
                }
                return true;
            case CONTEXTMENU_SHOW_HELP:
                startActivity(new Intent(this, HelpActivity.class));
                return true;
            case CONTEXTMENU_CONSOLE_STYLE:
                terminalStylingDialog();
                return true;
            case CONTEXTMENU_SELECT_URL_ID:
                showUrlSelection();
                return true;
            case CONTEXTMENU_SHARE_TRANSCRIPT_ID:
                if (session != null) {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    String transcriptText = session.getEmulator().getScreen().getTranscriptTextWithoutJoinedLines().trim();
                    // See https://github.com/termux/termux-app/issues/1166.
                    final int MAX_LENGTH = 100_000;
                    if (transcriptText.length() > MAX_LENGTH) {
                        int cutOffIndex = transcriptText.length() - MAX_LENGTH;
                        int nextNewlineIndex = transcriptText.indexOf('\n', cutOffIndex);
                        if (nextNewlineIndex != -1 && nextNewlineIndex != transcriptText.length() - 1) {
                            cutOffIndex = nextNewlineIndex + 1;
                        }
                        transcriptText = transcriptText.substring(cutOffIndex).trim();
                    }
                    intent.putExtra(Intent.EXTRA_TEXT, transcriptText);
                    intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_transcript_file_name));
                    startActivity(Intent.createChooser(intent, getString(R.string.share_transcript_chooser_title)));
                }
                return true;
            case CONTEXTMENU_PASTE_ID:
                doPaste();
                return true;
            case CONTEXTMENU_RESET_TERMINAL_ID: {
                if (session != null) {
                    session.reset(true);
                    showToast(getResources().getString(R.string.reset_toast_notification), true);
                }
                return true;
            }
            case CONTEXTMENU_TOGGLE_IGNORE_BELL: {
                if (mSettings.isBellIgnored()) {
                    mSettings.setIgnoreBellCharacter(this, false);
                } else {
                    mSettings.setIgnoreBellCharacter(this, true);
                }
                return true;
            }

            default:
                return super.onContextItemSelected(item);
        }
    }

    /**
     * Hook system menu to show context menu instead.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        mTermService = ((TerminalService.LocalBinder) service).service;

        mTermService.mSessionChangeCallback = new SessionChangedCallback() {
            @Override
            public void onTextChanged(TerminalSession changedSession) {
                if (!mIsVisible) return;
                if (mTerminalView.getCurrentSession() == changedSession) mTerminalView.onScreenUpdated();
            }

            @Override
            public void onTitleChanged(TerminalSession updatedSession) {
                if (!mIsVisible) return;
                if (updatedSession != mTerminalView.getCurrentSession()) {
                    // Only show toast for other sessions than the current one, since the user
                    // probably consciously caused the title change to change in the current session
                    // and don't want an annoying toast for that.
                    showToast(toToastTitle(updatedSession), false);
                }
                mListViewAdapter.notifyDataSetChanged();
            }

            @Override
            public void onSessionFinished(final TerminalSession finishedSession) {
                // Needed for resetting font size on next application launch
                // otherwise it will be reset only after force-closing.
                TerminalActivity.currentFontSize = -1;

                // Do not terminate service in debug builds.
                // Useful for getting information about crash of qemu/socat binaries.
                if (!BuildConfig.DEBUG) {
                    if (mTermService.mWantsToStop) {
                        // The service wants to stop as soon as possible.
                        finish();
                        return;
                    }
                    mTermService.terminateService();
                }
            }

            @Override
            public void onClipboardText(TerminalSession session, String text) {
                if (!mIsVisible) return;
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"}, new ClipData.Item(text)));
            }

            @Override
            public void onBell(TerminalSession session) {
                if (!mIsVisible || mSettings.isBellIgnored()) {
                    return;
                }

                Bell.getInstance(TerminalActivity.this).doBell();
            }

            @Override
            public void onColorsChanged(TerminalSession changedSession) {
                if (mTerminalView.getCurrentSession() == changedSession) updateBackgroundColor();
            }
        };

        ListView listView = findViewById(R.id.left_drawer_list);
        mListViewAdapter = new ArrayAdapter<TerminalSession>(getApplicationContext(), R.layout.line_in_drawer, mTermService.getSessions()) {
            final StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
            final StyleSpan italicSpan = new StyleSpan(Typeface.ITALIC);

            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View row = convertView;
                if (row == null) {
                    LayoutInflater inflater = getLayoutInflater();
                    row = inflater.inflate(R.layout.line_in_drawer, parent, false);
                }

                TerminalSession sessionAtRow = getItem(position);
                boolean sessionRunning = sessionAtRow.isRunning();

                TextView firstLineView = row.findViewById(R.id.row_line);

                String name = sessionAtRow.mSessionName;
                String sessionTitle = sessionAtRow.getTitle();

                String numberPart = "[" + (position + 1) + "] ";
                String sessionNamePart = (TextUtils.isEmpty(name) ? "" : name);
                String sessionTitlePart = (TextUtils.isEmpty(sessionTitle) ? "" : ((sessionNamePart.isEmpty() ? "" : "\n") + sessionTitle));

                String text = numberPart + sessionNamePart + sessionTitlePart;
                SpannableString styledText = new SpannableString(text);
                styledText.setSpan(boldSpan, 0, numberPart.length() + sessionNamePart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styledText.setSpan(italicSpan, numberPart.length() + sessionNamePart.length(), text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                firstLineView.setText(styledText);

                if (sessionRunning) {
                    firstLineView.setPaintFlags(firstLineView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                } else {
                    firstLineView.setPaintFlags(firstLineView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                }
                int color = sessionRunning || sessionAtRow.getExitStatus() == 0 ? Color.BLACK : Color.RED;
                firstLineView.setTextColor(color);
                return row;
            }
        };

        listView.setAdapter(mListViewAdapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            TerminalSession clickedSession = mListViewAdapter.getItem(position);
            switchToSession(clickedSession);
            getDrawer().closeDrawers();
        });

        if (mTermService.getSessions().isEmpty()) {
            if (mIsVisible) {
                Installer.setupIfNeeded(TerminalActivity.this, () -> {
                    if (mTermService == null) return; // Activity might have been destroyed.
                    try {
                        TerminalSession session;

                        session = mTermService.createQemuSession();
                        session.mSessionName = "QEMU";
                        mTerminalView.attachSession(session);

                        for (int i = 0; i < 4; i++) {
                            session = mTermService.createSocatSession(i);
                            session.mSessionName = String.format(Locale.US, "/dev/ttyS%d", i);
                            mTerminalView.attachSession(session);
                        }

                        switchToSession(mTermService.getSessions().get(1));
                        showToast(getResources().getString(R.string.startup_tips_toast), true);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finish();
            }
        } else {
            switchToSession(getStoredCurrentSessionOrLast());
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // Respect being stopped from the TerminalService notification action.
        finish();
    }

    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        }
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }

    private void updateBackgroundColor() {
        TerminalSession session = mTerminalView.getCurrentSession();
        if (session != null && session.getEmulator() != null) {
            getWindow().getDecorView().setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        }
    }

    /**
     * Reload terminal color scheme and background.
     */
    private void reloadTerminalStyling() {
        String fileName = mSettings.getColorScheme();

        Properties props = new Properties();

        if (!fileName.equals("Default")) {
            try (InputStream in = getAssets().open("color_schemes/" + fileName)) {
                props.load(in);
            } catch (IOException e) {
                Log.e(Config.APP_LOG_TAG, "failed to load color scheme file '" + fileName + "' from assets", e);
            }
        }

        try {
            TerminalColors.COLOR_SCHEME.updateWith(props);
        } catch (Exception e) {
            Log.e(Config.APP_LOG_TAG, "failed to update terminal color scheme", e);
        }

        if (mTermService != null) {
            for (TerminalSession session : mTermService.getSessions()) {
                if (session != null && session.getEmulator() != null) {
                    session.getEmulator().mColors.reset();
                }
            }
        }

        updateBackgroundColor();
        mTerminalView.invalidate();
    }

    /**
     * Open dialog with spinner for selecting terminal color scheme. The file name of a picked
     * color scheme will be saved in shared preferences and loaded from assets by {@link #reloadTerminalStyling()}.
     */
    private void terminalStylingDialog() {
        ArrayAdapter<String> colorsAdapter = new ArrayAdapter<>(this, R.layout.styling_dialog_item);

        List<String> fileNames = new ArrayList<>();
        fileNames.add("Default");

        try {
            fileNames.addAll(Arrays.asList(Objects.requireNonNull(getAssets().list("color_schemes"))));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        List<String> colorSchemes = new ArrayList<>();

        for (String s : fileNames) {
            String name = s.replace('-', ' ');
            int dotIndex = name.lastIndexOf('.');
            if (dotIndex != -1) name = name.substring(0, dotIndex);

            boolean lastWhitespace = true;
            char[] chars = name.toCharArray();

            for (int i = 0; i < chars.length; i++) {
                if (Character.isLetter(chars[i])) {
                    if (lastWhitespace) {
                        chars[i] = Character.toUpperCase(chars[i]);
                    }

                    lastWhitespace = false;
                } else {
                    lastWhitespace = Character.isWhitespace(chars[i]);
                }
            }

            colorSchemes.add(new String(chars));
        }

        colorsAdapter.addAll(colorSchemes);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setSingleChoiceItems(colorsAdapter, fileNames.indexOf(mSettings.getColorScheme()), (dialogInterface, i) -> {
            String current = fileNames.get(i);

            if (current != null) {
                showToast(getResources().getString(R.string.style_toast_theme_applied) + "\n" + colorSchemes.get(i), true);
                mSettings.setColorScheme(this, current);
            }

            reloadTerminalStyling();
            dialogInterface.dismiss();
        });

        builder.create().show();
    }

    /**
     * The current session as stored or the last one if that does not exist.
     */
    private TerminalSession getStoredCurrentSessionOrLast() {
        TerminalSession stored = TerminalPreferences.getCurrentSession(this);

        if (stored != null) {
            return stored;
        }

        List<TerminalSession> sessions = mTermService.getSessions();

        return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
    }

    /**
     * Try switching to session and note about it, but do nothing if already displaying the session.
     */
    public void switchToSession(TerminalSession session) {
        if (mTerminalView.attachSession(session)) {
            if (mIsVisible) {
                final int indexOfSession = mTermService.getSessions().indexOf(session);
                mListViewAdapter.notifyDataSetChanged();

                final ListView lv = findViewById(R.id.left_drawer_list);
                lv.setItemChecked(indexOfSession, true);
                lv.smoothScrollToPosition(indexOfSession);

                showToast(toToastTitle(session), false);
            }

            updateBackgroundColor();
        }
    }

    /**
     * Switch to next or previous session,
     */
    public void switchToSession(boolean forward) {
        TerminalSession currentSession = mTerminalView.getCurrentSession();
        int index = mTermService.getSessions().indexOf(currentSession);
        if (forward) {
            if (++index >= mTermService.getSessions().size()) index = 0;
        } else {
            if (--index < 0) index = mTermService.getSessions().size() - 1;
        }
        switchToSession(mTermService.getSessions().get(index));
    }

    /**
     * Change terminal font size.
     */
    public void changeFontSize(boolean increase) {
        TerminalActivity.currentFontSize += (increase ? 1 : -1) * 2;
        TerminalActivity.currentFontSize = Math.max(MIN_FONTSIZE, Math.min(TerminalActivity.currentFontSize, MAX_FONTSIZE));
        mTerminalView.setTextSize(TerminalActivity.currentFontSize);
    }

    /**
     * Toggle extra keys layout.
     */
    public void toggleShowExtraKeys() {
        final ViewPager viewPager = findViewById(R.id.viewpager);
        final boolean showNow = mSettings.toggleShowExtraKeys(TerminalActivity.this);

        viewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);

        if (showNow && viewPager.getCurrentItem() == 1) {
            // Focus the text input view if just revealed.
            findViewById(R.id.text_input).requestFocus();
        }
    }

    /**
     * Paste text from clipboard.
     */
    public void doPaste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        if (clipboard != null) {
            ClipData clipData = clipboard.getPrimaryClip();

            if (clipData == null) {
                return;
            }

            CharSequence paste = clipData.getItemAt(0).coerceToText(this);
            if (!TextUtils.isEmpty(paste)) {
                TerminalSession currentSession = mTerminalView.getCurrentSession();

                if (currentSession != null) {
                    currentSession.getEmulator().paste(paste.toString());
                }
            }
        }
    }

    /**
     * Extract URLs from the current transcript and show them in dialog.
     */
    public void showUrlSelection() {
        TerminalSession currentSession = mTerminalView.getCurrentSession();

        if (currentSession == null) {
            return;
        }

        String text = currentSession.getEmulator().getScreen().getTranscriptText();
        LinkedHashSet<CharSequence> urlSet = extractUrls(text);

        if (urlSet.isEmpty()) {
            showToast(getResources().getString(R.string.select_url_toast_no_found), true);
            return;
        }

        final CharSequence[] urls = urlSet.toArray(new CharSequence[0]);
        Collections.reverse(Arrays.asList(urls)); // Latest first.

        // Click to copy url to clipboard:
        final AlertDialog dialog = new AlertDialog.Builder(TerminalActivity.this).setItems(urls, (di, which) -> {
            String url = (String) urls[which];
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"}, new ClipData.Item(url)));
                showToast(getResources().getString(R.string.select_url_toast_copied_to_clipboard), true);
            }
        }).setTitle(R.string.select_url_dialog_title).create();

        // Long press to open URL:
        dialog.setOnShowListener(di -> {
            ListView lv = dialog.getListView(); // this is a ListView with your "buds" in it
            lv.setOnItemLongClickListener((parent, view, position, id) -> {
                dialog.dismiss();
                String url = (String) urls[position];

                // Disable handling of 'file://' urls since this may
                // produce android.os.FileUriExposedException.
                if (!url.startsWith("file://")) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    try {
                        startActivity(i, null);
                    } catch (ActivityNotFoundException e) {
                        // If no applications match, Android displays a system message.
                        startActivity(Intent.createChooser(i, null));
                    }
                } else {
                    showToast(getResources().getString(R.string.select_url_toast_cannot_open), false);
                }

                return true;
            });
        });

        dialog.show();
    }

    /**
     * Extract URLs from the given text.
     */
    @SuppressWarnings("StringBufferReplaceableByString")
    private static LinkedHashSet<CharSequence> extractUrls(String text) {
        StringBuilder regex_sb = new StringBuilder();

        regex_sb.append("(");                       // Begin first matching group.
        regex_sb.append("(?:");                     // Begin scheme group.
        regex_sb.append("dav|");                    // The DAV proto.
        regex_sb.append("dict|");                   // The DICT proto.
        regex_sb.append("dns|");                    // The DNS proto.
        regex_sb.append("file|");                   // File path.
        regex_sb.append("finger|");                 // The Finger proto.
        regex_sb.append("ftp(?:s?)|");              // The FTP proto.
        regex_sb.append("git|");                    // The Git proto.
        regex_sb.append("gopher|");                 // The Gopher proto.
        regex_sb.append("http(?:s?)|");             // The HTTP proto.
        regex_sb.append("imap(?:s?)|");             // The IMAP proto.
        regex_sb.append("irc(?:[6s]?)|");           // The IRC proto.
        regex_sb.append("ip[fn]s|");                // The IPFS proto.
        regex_sb.append("ldap(?:s?)|");             // The LDAP proto.
        regex_sb.append("pop3(?:s?)|");             // The POP3 proto.
        regex_sb.append("redis(?:s?)|");            // The Redis proto.
        regex_sb.append("rsync|");                  // The Rsync proto.
        regex_sb.append("rtsp(?:[su]?)|");          // The RTSP proto.
        regex_sb.append("sftp|");                   // The SFTP proto.
        regex_sb.append("smb(?:s?)|");              // The SAMBA proto.
        regex_sb.append("smtp(?:s?)|");             // The SMTP proto.
        regex_sb.append("svn(?:(?:\\+ssh)?)|");     // The Subversion proto.
        regex_sb.append("tcp|");                    // The TCP proto.
        regex_sb.append("telnet|");                 // The Telnet proto.
        regex_sb.append("tftp|");                   // The TFTP proto.
        regex_sb.append("udp|");                    // The UDP proto.
        regex_sb.append("vnc|");                    // The VNC proto.
        regex_sb.append("ws(?:s?)");                // The Websocket proto.
        regex_sb.append(")://");                    // End scheme group.
        regex_sb.append(")");                       // End first matching group.

        // Begin second matching group.
        regex_sb.append("(");

        // User name and/or password in format 'user:pass@'.
        regex_sb.append("(?:\\S+(?::\\S*)?@)?");

        // Begin host group.
        regex_sb.append("(?:");

        // IP address (from http://www.regular-expressions.info/examples.html).
        regex_sb.append("(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|");

        // Host name or domain.
        regex_sb.append("(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)(?:(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*(?:\\.(?:[a-z\\u00a1-\\uffff]{2,})))?|");

        // Just path. Used in case of 'file://' scheme.
        regex_sb.append("/(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)");

        // End host group.
        regex_sb.append(")");

        // Port number.
        regex_sb.append("(?::\\d{1,5})?");

        // Resource path with optional query string.
        regex_sb.append("(?:/[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?");

        // Fragment.
        regex_sb.append("(?:#[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?");

        // End second matching group.
        regex_sb.append(")");

        final Pattern urlPattern = Pattern.compile(
            regex_sb.toString(),
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

        LinkedHashSet<CharSequence> urlSet = new LinkedHashSet<>();
        Matcher matcher = urlPattern.matcher(text);

        while (matcher.find()) {
            int matchStart = matcher.start(1);
            int matchEnd = matcher.end();
            String url = text.substring(matchStart, matchEnd);
            urlSet.add(url);
        }

        return urlSet;
    }

    /**
     * Show a toast and dismiss the last one if still visible.
     */
    private void showToast(String text, boolean longDuration) {
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TerminalActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);

        // Broken on API level 30.
        //TextView toastText = mLastToast.getView().findViewById(android.R.id.message);
        //if (toastText != null) {
        //    toastText.setGravity(Gravity.CENTER);
        //}

        mLastToast.show();
    }

    /**
     * Create a message with session name ready for showing in toast.
     */
    private String toToastTitle(TerminalSession session) {
        final int indexOfSession = mTermService.getSessions().indexOf(session);

        StringBuilder toastTitle = new StringBuilder("[" + (indexOfSession + 1) + "]");

        if (!TextUtils.isEmpty(session.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName);
        }

        String title = session.getTitle();

        if (!TextUtils.isEmpty(title)) {
            // Space to "[${NR}] or newline after session name:
            toastTitle.append(session.mSessionName == null ? " " : "\n");
            toastTitle.append(title);
        }

        return toastTitle.toString();
    }
}
