package org.telegram.messenger.zentrix.bot;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zentrix.ZentrixConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

/**
 * Handles authentication via Telegram Bot Token using MTProto RPC auth.importBotAuthorization.
 * Based on Telegram 12.4.1 / NekoX architecture.
 */
public class BotAuthHelper {

    private static final String TAG = "BotAuthHelper";

    /**
     * Callback interface for bot authentication results.
     */
    public interface BotAuthCallback {
        void onSuccess(TLRPC.User user);
        void onError(int errorCode, String errorMessage);
    }

    /**
     * Authenticates into Telegram using a Bot Token via official MTProto RPC.
     *
     * @param currentAccount Account slot index (e.g. UserConfig.selectedAccount)
     * @param token          Bot token issued by @BotFather (e.g. 123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ)
     * @param callback       Callback to receive auth events on the UI thread
     */
    public static void loginWithBotToken(final int currentAccount, final String token, final BotAuthCallback callback) {
        if (TextUtils.isEmpty(token)) {
            deliverError(callback, -1, getLocalizedError("BotTokenEmpty", "Bot token cannot be empty."));
            return;
        }

        final String sanitizedToken = token.trim();
        if (!sanitizedToken.contains(":") || sanitizedToken.length() < 15) {
            deliverError(callback, -1, getLocalizedError("BotTokenInvalidFormat", "Invalid Bot Token format. Expected: 123456789:AA..."));
            return;
        }

        // Construct MTProto RPC request: auth.importBotAuthorization#67a3ff2c
        TLRPC.TL_auth_importBotAuthorization req = new TLRPC.TL_auth_importBotAuthorization();
        req.flags = 0;
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        req.bot_auth_token = sanitizedToken;

        // Dispatch MTProto request without login session
        int flags = ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            AndroidUtilities.runOnUIThread(() -> {
                if (error != null) {
                    FileLog.e(TAG + ": Bot login RPC failed code=" + error.code + " text=" + error.text);
                    String localizedMsg = parseRpcError(error);
                    if (callback != null) {
                        callback.onError(error.code, localizedMsg);
                    }
                    return;
                }

                if (response instanceof TLRPC.TL_auth_authorization) {
                    TLRPC.TL_auth_authorization auth = (TLRPC.TL_auth_authorization) response;
                    TLRPC.User botUser = auth.user;

                    if (botUser == null) {
                        FileLog.e(TAG + ": TL_auth_authorization returned null user");
                        if (callback != null) {
                            callback.onError(-2, getLocalizedError("BotUserEmpty", "Failed to retrieve bot profile from Telegram."));
                        }
                        return;
                    }

                    FileLog.d(TAG + ": Bot login succeeded for @" + botUser.username + " (id=" + botUser.id + ")");

                    // 1. Configure UserConfig for current account
                    UserConfig userConfig = UserConfig.getInstance(currentAccount);
                    userConfig.clearConfig();
                    userConfig.setCurrentUser(botUser);
                    userConfig.saveConfig(true);

                    // 2. Persist Bot Mode in ZentrixConfig
                    ZentrixConfig.setBotMode(true, sanitizedToken);

                    // 3. Cache bot user in Local Database and Controller
                    ArrayList<TLRPC.User> users = new ArrayList<>();
                    users.add(botUser);
                    MessagesStorage.getInstance(currentAccount).putUsersAndChats(users, null, true, true);
                    MessagesController.getInstance(currentAccount).putUser(botUser, false);

                    // 4. Update MTProto Connection State
                    ConnectionsManager connectionsManager = ConnectionsManager.getInstance(currentAccount);
                    connectionsManager.updateDcSettings();
                    connectionsManager.checkConnection();

                    // 5. Notify UI components
                    NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
                    nc.postNotificationName(NotificationCenter.mainUserInfoChanged);
                    nc.postNotificationName(NotificationCenter.didUpdateConnectionState);
                    nc.postNotificationName(NotificationCenter.dialogsNeedReload);

                    // 6. Deliver success callback
                    if (callback != null) {
                        callback.onSuccess(botUser);
                    }

                    // 7. Launch main UI interface via LaunchActivity
                    openMainActivity(currentAccount);

                } else {
                    FileLog.e(TAG + ": Unexpected MTProto response: " + (response != null ? response.getClass().getName() : "null"));
                    if (callback != null) {
                        callback.onError(-3, getLocalizedError("UnknownAuthResponse", "Unexpected authorization response."));
                    }
                }
            });
        }, flags);
    }

    /**
     * Resets navigation stack and opens LaunchActivity with the authenticated account.
     */
    private static void openMainActivity(int account) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(context, LaunchActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("currentAccount", account);
            context.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(TAG + ": Failed to start LaunchActivity", e);
        }
    }

    /**
     * Translates MTProto RPC error strings into user-friendly localized messages.
     */
    private static String parseRpcError(TLRPC.TL_error error) {
        if (error == null || error.text == null) {
            return getLocalizedError("UnknownError", "An unexpected network error occurred.");
        }

        String errText = error.text.toUpperCase();

        if (errText.contains("BOT_TOKEN_INVALID")) {
            return getLocalizedError("BotTokenInvalid", "The bot token is invalid. Please verify the token from @BotFather.");
        } else if (errText.contains("BOT_TOKEN_EXPIRED")) {
            return getLocalizedError("BotTokenExpired", "The bot token has expired or was revoked.");
        } else if (errText.contains("API_ID_INVALID") || errText.contains("API_ID_PUBLISHED_FLOOD")) {
            return getLocalizedError("ApiCredentialsError", "Telegram API credentials are invalid or restricted.");
        } else if (errText.startsWith("FLOOD_WAIT")) {
            String seconds = errText.replaceAll("[^0-9]", "");
            return getLocalizedError("FloodWait", "Too many attempts. Please wait " + (seconds.isEmpty() ? "a few" : seconds) + " seconds.");
        } else if (errText.contains("CONNECTION_NOT_INITED") || errText.contains("NETWORK_ERROR")) {
            return getLocalizedError("NetworkError", "Network connection failed. Please check your internet connection.");
        }

        return error.text;
    }

    private static String getLocalizedError(String key, String fallback) {
        try {
            return LocaleController.getString(key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static void deliverError(final BotAuthCallback callback, final int code, final String msg) {
        AndroidUtilities.runOnUIThread(() -> {
            if (callback != null) {
                callback.onError(code, msg);
            }
        });
    }
}
