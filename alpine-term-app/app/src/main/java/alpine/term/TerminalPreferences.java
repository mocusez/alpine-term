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

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import alpine.term.emulator.TerminalSession;

@SuppressWarnings("WeakerAccess")
final class TerminalPreferences {

    private static final String CURRENT_SESSION_KEY = "current_session";
    private static final String SHOW_EXTRA_KEYS_KEY = "show_extra_keys";
    private static final String IGNORE_BELL = "ignore_bell";
    private static final String COLOR_SCHEME = "color_scheme";

    private boolean mShowExtraKeys;
    private boolean mIgnoreBellCharacter;
    private String mColorScheme;

    public TerminalPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        mShowExtraKeys = prefs.getBoolean(SHOW_EXTRA_KEYS_KEY, true);
        mIgnoreBellCharacter = prefs.getBoolean(IGNORE_BELL, false);
        mColorScheme = prefs.getString(COLOR_SCHEME, "Default");
    }

    public static void storeCurrentSession(Context context, TerminalSession session) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(TerminalPreferences.CURRENT_SESSION_KEY, session.mHandle).apply();
    }

    public static TerminalSession getCurrentSession(TerminalActivity context) {
        String sessionHandle = PreferenceManager.getDefaultSharedPreferences(context).getString(TerminalPreferences.CURRENT_SESSION_KEY, "");

        for (int i = 0, len = context.mTermService.getSessions().size(); i < len; i++) {
            TerminalSession session = context.mTermService.getSessions().get(i);
            if (session.mHandle.equals(sessionHandle)) return session;
        }

        return null;
    }

    public boolean isExtraKeysEnabled() {
        return mShowExtraKeys;
    }

    public boolean toggleShowExtraKeys(Context context) {
        mShowExtraKeys = !mShowExtraKeys;
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(SHOW_EXTRA_KEYS_KEY, mShowExtraKeys).apply();
        return mShowExtraKeys;
    }

    public boolean isBellIgnored() {
        return mIgnoreBellCharacter;
    }

    public void setIgnoreBellCharacter(Context context, boolean newValue) {
        mIgnoreBellCharacter = newValue;
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IGNORE_BELL, newValue).apply();
    }

    public void setColorScheme(Context context, String name) {
        mColorScheme = name;
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(COLOR_SCHEME, name).apply();
    }

    public String getColorScheme() {
        return mColorScheme;
    }
}
