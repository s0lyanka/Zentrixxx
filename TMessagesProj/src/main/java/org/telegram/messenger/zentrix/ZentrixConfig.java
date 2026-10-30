package org.telegram.messenger.zentrix;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Persistent SharedPreferences-backed configuration class managing all Zentrix mod toggles.
 * Based on Telegram 12.4.1 / NekoX architecture.
 */
public class ZentrixConfig {

    private static final String TAG = "ZentrixConfig";
    private static final String PREFS_NAME = "zentrix_config";
    private static final Object sync = new Object();
    private static volatile boolean configLoaded = false;

    // ==========================================
    // 1. Ghost Mode Settings
    // ==========================================
    public static boolean isGhostMode = false;
    public static boolean hideRead = false;
    public static boolean hideStories = false;
    public static boolean hideTyping = false;
    public static boolean useScheduledSending = false;

    // Keys
    private static final String KEY_IS_GHOST_MODE = "is_ghost_mode";
    private static final String KEY_HIDE_READ = "hide_read";
    private static final String KEY_HIDE_STORIES = "hide_stories";
    private static final String KEY_HIDE_TYPING = "hide_typing";
    private static final String KEY_USE_SCHEDULED_SENDING = "use_scheduled_sending";

    // ==========================================
    // 2. Anti-Delete & Spy Settings
    // ==========================================
    public static boolean saveDeletedMessages = false;
    public static boolean saveEditedMessages = false;
    public static boolean saveReadTimestamps = false;

    // Keys
    private static final String KEY_SAVE_DELETED_MESSAGES = "save_deleted_messages";
    private static final String KEY_SAVE_EDITED_MESSAGES = "save_edited_messages";
    private static final String KEY_SAVE_READ_TIMESTAMPS = "save_read_timestamps";

    // ==========================================
    // 3. UI Customization Settings
    // ==========================================
    public static boolean useClassicDrawer = true;
    public static boolean useSquareStyle = false;
    public static boolean smoothScroll = true;

    // 8dp radius for avatars & cards in square mode
    public static final float SQUARE_STYLE_CORNER_RADIUS_DP = 8.0f;

    // Keys
    private static final String KEY_USE_CLASSIC_DRAWER = "use_classic_drawer";
    private static final String KEY_USE_SQUARE_STYLE = "use_square_style";
    private static final String KEY_SMOOTH_SCROLL = "smooth_scroll";

    // ==========================================
    // 4. Bot Mode Settings
    // ==========================================
    public static boolean isBotMode = false;
    public static String botToken = "";

    // Keys
    private static final String KEY_IS_BOT_MODE = "is_bot_mode";
    private static final String KEY_BOT_TOKEN = "bot_token";

    // ==========================================
    // 5. Music Player Settings
    // ==========================================
    public static final int SOURCE_FILTER_CHATS = 0;
    public static final int SOURCE_FILTER_LOCAL = 1;
    public static final int SOURCE_FILTER_SAVED = 2;

    public static boolean autoPlayNext = true;
    public static int sourceFilter = SOURCE_FILTER_CHATS;

    // Keys
    private static final String KEY_AUTO_PLAY_NEXT = "auto_play_next";
    private static final String KEY_SOURCE_FILTER = "source_filter";

    // ==========================================
    // 6. VPN Settings
    // ==========================================
    public static final String VPN_TYPE_VLESS = "VLESS";
    public static final String VPN_TYPE_HYSTERIA2 = "HYSTERIA2";

    public static boolean vpnEnabled = false;
    public static String vpnType = VPN_TYPE_VLESS;
    public static String vpnConfigUrl = "";

    // Keys
    private static final String KEY_VPN_ENABLED = "vpn_enabled";
    private static final String KEY_VPN_TYPE = "vpn_type";
    private static final String KEY_VPN_CONFIG_URL = "vpn_config_url";

    static {
        loadConfig();
    }

    private static SharedPreferences getPreferences() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Loads all persistent settings from SharedPreferences.
     */
    public static void loadConfig() {
        synchronized (sync) {
            if (configLoaded) {
                return;
            }
            SharedPreferences preferences = getPreferences();
            if (preferences == null) {
                return;
            }

            try {
                // Ghost Mode
                isGhostMode = preferences.getBoolean(KEY_IS_GHOST_MODE, false);
                hideRead = preferences.getBoolean(KEY_HIDE_READ, false);
                hideStories = preferences.getBoolean(KEY_HIDE_STORIES, false);
                hideTyping = preferences.getBoolean(KEY_HIDE_TYPING, false);
                useScheduledSending = preferences.getBoolean(KEY_USE_SCHEDULED_SENDING, false);

                // Anti-Delete & Spy
                saveDeletedMessages = preferences.getBoolean(KEY_SAVE_DELETED_MESSAGES, false);
                saveEditedMessages = preferences.getBoolean(KEY_SAVE_EDITED_MESSAGES, false);
                saveReadTimestamps = preferences.getBoolean(KEY_SAVE_READ_TIMESTAMPS, false);

                // UI Customization
                useClassicDrawer = preferences.getBoolean(KEY_USE_CLASSIC_DRAWER, true);
                useSquareStyle = preferences.getBoolean(KEY_USE_SQUARE_STYLE, false);
                smoothScroll = preferences.getBoolean(KEY_SMOOTH_SCROLL, true);

                // Bot Mode
                isBotMode = preferences.getBoolean(KEY_IS_BOT_MODE, false);
                botToken = preferences.getString(KEY_BOT_TOKEN, "");

                // Music Player
                autoPlayNext = preferences.getBoolean(KEY_AUTO_PLAY_NEXT, true);
                sourceFilter = preferences.getInt(KEY_SOURCE_FILTER, SOURCE_FILTER_CHATS);

                // VPN
                vpnEnabled = preferences.getBoolean(KEY_VPN_ENABLED, false);
                vpnType = preferences.getString(KEY_VPN_TYPE, VPN_TYPE_VLESS);
                vpnConfigUrl = preferences.getString(KEY_VPN_CONFIG_URL, "");

                configLoaded = true;
            } catch (Exception e) {
                FileLog.e(TAG + ": loadConfig failed", e);
            }
        }
    }

    /**
     * Persists all current configurations into SharedPreferences asynchronously.
     */
    public static void saveConfig() {
        synchronized (sync) {
            SharedPreferences preferences = getPreferences();
            if (preferences == null) {
                return;
            }

            try {
                SharedPreferences.Editor editor = preferences.edit();

                // Ghost Mode
                editor.putBoolean(KEY_IS_GHOST_MODE, isGhostMode);
                editor.putBoolean(KEY_HIDE_READ, hideRead);
                editor.putBoolean(KEY_HIDE_STORIES, hideStories);
                editor.putBoolean(KEY_HIDE_TYPING, hideTyping);
                editor.putBoolean(KEY_USE_SCHEDULED_SENDING, useScheduledSending);

                // Anti-Delete & Spy
                editor.putBoolean(KEY_SAVE_DELETED_MESSAGES, saveDeletedMessages);
                editor.putBoolean(KEY_SAVE_EDITED_MESSAGES, saveEditedMessages);
                editor.putBoolean(KEY_SAVE_READ_TIMESTAMPS, saveReadTimestamps);

                // UI Customization
                editor.putBoolean(KEY_USE_CLASSIC_DRAWER, useClassicDrawer);
                editor.putBoolean(KEY_USE_SQUARE_STYLE, useSquareStyle);
                editor.putBoolean(KEY_SMOOTH_SCROLL, smoothScroll);

                // Bot Mode
                editor.putBoolean(KEY_IS_BOT_MODE, isBotMode);
                editor.putString(KEY_BOT_TOKEN, botToken != null ? botToken : "");

                // Music Player
                editor.putBoolean(KEY_AUTO_PLAY_NEXT, autoPlayNext);
                editor.putInt(KEY_SOURCE_FILTER, sourceFilter);

                // VPN
                editor.putBoolean(KEY_VPN_ENABLED, vpnEnabled);
                editor.putString(KEY_VPN_TYPE, vpnType != null ? vpnType : VPN_TYPE_VLESS);
                editor.putString(KEY_VPN_CONFIG_URL, vpnConfigUrl != null ? vpnConfigUrl : "");

                editor.apply();
            } catch (Exception e) {
                FileLog.e(TAG + ": saveConfig failed", e);
            }
        }
    }

    // ==========================================
    // Setters with Immediate Persistence
    // ==========================================

    public static void setGhostMode(boolean value) {
        isGhostMode = value;
        putPrefBoolean(KEY_IS_GHOST_MODE, value);
    }

    public static void setHideRead(boolean value) {
        hideRead = value;
        putPrefBoolean(KEY_HIDE_READ, value);
    }

    public static void setHideStories(boolean value) {
        hideStories = value;
        putPrefBoolean(KEY_HIDE_STORIES, value);
    }

    public static void setHideTyping(boolean value) {
        hideTyping = value;
        putPrefBoolean(KEY_HIDE_TYPING, value);
    }

    public static void setUseScheduledSending(boolean value) {
        useScheduledSending = value;
        putPrefBoolean(KEY_USE_SCHEDULED_SENDING, value);
    }

    public static void setSaveDeletedMessages(boolean value) {
        saveDeletedMessages = value;
        putPrefBoolean(KEY_SAVE_DELETED_MESSAGES, value);
    }

    public static void setSaveEditedMessages(boolean value) {
        saveEditedMessages = value;
        putPrefBoolean(KEY_SAVE_EDITED_MESSAGES, value);
    }

    public static void setSaveReadTimestamps(boolean value) {
        saveReadTimestamps = value;
        putPrefBoolean(KEY_SAVE_READ_TIMESTAMPS, value);
    }

    public static void setUseClassicDrawer(boolean value) {
        useClassicDrawer = value;
        putPrefBoolean(KEY_USE_CLASSIC_DRAWER, value);
    }

    public static void setUseSquareStyle(boolean value) {
        useSquareStyle = value;
        putPrefBoolean(KEY_USE_SQUARE_STYLE, value);
    }

    public static void setSmoothScroll(boolean value) {
        smoothScroll = value;
        putPrefBoolean(KEY_SMOOTH_SCROLL, value);
    }

    public static void setBotMode(boolean isBot, String token) {
        isBotMode = isBot;
        botToken = token != null ? token : "";
        synchronized (sync) {
            SharedPreferences preferences = getPreferences();
            if (preferences != null) {
                preferences.edit()
                        .putBoolean(KEY_IS_BOT_MODE, isBotMode)
                        .putString(KEY_BOT_TOKEN, botToken)
                        .apply();
            }
        }
    }

    public static void setAutoPlayNext(boolean value) {
        autoPlayNext = value;
        putPrefBoolean(KEY_AUTO_PLAY_NEXT, value);
    }

    public static void setSourceFilter(int filter) {
        sourceFilter = filter;
        putPrefInt(KEY_SOURCE_FILTER, filter);
    }

    public static void setVpnEnabled(boolean value) {
        vpnEnabled = value;
        putPrefBoolean(KEY_VPN_ENABLED, value);
    }

    public static void setVpnType(String type) {
        vpnType = type != null ? type : VPN_TYPE_VLESS;
        putPrefString(KEY_VPN_TYPE, vpnType);
    }

    public static void setVpnConfigUrl(String url) {
        vpnConfigUrl = url != null ? url : "";
        putPrefString(KEY_VPN_CONFIG_URL, vpnConfigUrl);
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    /**
     * Returns the corner radius in pixels for avatars & cards.
     * When useSquareStyle is active, returns 8dp in pixels.
     * Otherwise returns 0 (indicating regular circular / pill shape).
     */
    public static int getCornerRadiusPx() {
        if (useSquareStyle) {
            return AndroidUtilities.dp(SQUARE_STYLE_CORNER_RADIUS_DP);
        }
        return 0;
    }

    /**
     * Checks if Ghost Mode or any stealth features are enabled.
     */
    public static boolean isGhostFeatureActive() {
        return isGhostMode || hideRead || hideStories || hideTyping || useScheduledSending;
    }

    /**
     * Resets all Zentrix settings to factory defaults.
     */
    public static void resetToDefaults() {
        synchronized (sync) {
            isGhostMode = false;
            hideRead = false;
            hideStories = false;
            hideTyping = false;
            useScheduledSending = false;

            saveDeletedMessages = false;
            saveEditedMessages = false;
            saveReadTimestamps = false;

            useClassicDrawer = true;
            useSquareStyle = false;
            smoothScroll = true;

            isBotMode = false;
            botToken = "";

            autoPlayNext = true;
            sourceFilter = SOURCE_FILTER_CHATS;

            vpnEnabled = false;
            vpnType = VPN_TYPE_VLESS;
            vpnConfigUrl = "";

            SharedPreferences preferences = getPreferences();
            if (preferences != null) {
                preferences.edit().clear().apply();
            }
        }
    }

    private static void putPrefBoolean(String key, boolean val) {
        synchronized (sync) {
            SharedPreferences preferences = getPreferences();
            if (preferences != null) {
                preferences.edit().putBoolean(key, val).apply();
            }
        }
    }

    private static void putPrefInt(String key, int val) {
        synchronized (sync) {
            SharedPreferences preferences = getPreferences();
            if (preferences != null) {
                preferences.edit().putInt(key, val).apply();
            }
        }
    }

    private static void putPrefString(String key, String val) {
        synchronized (sync) {
            SharedPreferences preferences = getPreferences();
            if (preferences != null) {
                preferences.edit().putString(key, val).apply();
            }
        }
    }
}
