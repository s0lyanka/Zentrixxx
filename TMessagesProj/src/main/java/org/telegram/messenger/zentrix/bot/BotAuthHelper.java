package org.telegram.messenger.zentrix.bot;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zentrix.ZentrixConfig;
import org.telegram.tgnet.AbstractSerializedData;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

public class BotAuthHelper {

    private static final String TAG = "BotAuthHelper";

    public interface BotAuthCallback {
        void onSuccess(TLRPC.User user);
        void onError(int errorCode, String errorMessage);
    }

    public static class TL_auth_importBotAuthorization extends TLObject {
        public static final int constructor = 0x67a3ff2c;
        public int flags;
        public int api_id;
        public String api_hash;
        public String bot_auth_token;

        @Override
        public void serializeToStream(AbstractSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeInt32(api_id);
            stream.writeString(api_hash);
            stream.writeString(bot_auth_token);
        }

        @Override
        public TLObject deserializeResponse(AbstractSerializedData stream, int constructor, boolean exception) {
            return TLRPC.auth_Authorization.TLdeserialize(stream, constructor, exception);
        }
    }

    public static void loginWithBotToken(final int currentAccount, final String token, final BotAuthCallback callback) {
        if (TextUtils.isEmpty(token)) {
            deliverError(callback, -1, "Bot token cannot be empty.");
            return;
        }

        final String sanitizedToken = token.trim();
        if (!sanitizedToken.contains(":") || sanitizedToken.length() < 15) {
            deliverError(callback, -1, "Invalid Bot Token format. Expected: 123456789:AA...");
            return;
        }

        TL_auth_importBotAuthorization req = new TL_auth_importBotAuthorization();
        req.flags = 0;
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        req.bot_auth_token = sanitizedToken;

        int flags = ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            AndroidUtilities.runOnUIThread(() -> {
                if (error != null) {
                    FileLog.e(TAG + ": Bot login RPC failed code=" + error.code + " text=" + error.text);
                    if (callback != null) {
                        callback.onError(error.code, error.text != null ? error.text : "Authorization failed");
                    }
                    return;
                }

                if (response instanceof TLRPC.TL_auth_authorization) {
                    TLRPC.TL_auth_authorization auth = (TLRPC.TL_auth_authorization) response;
                    TLRPC.User botUser = auth.user;

                    if (botUser == null) {
                        if (callback != null) {
                            callback.onError(-2, "Failed to retrieve bot profile.");
                        }
                        return;
                    }

                    UserConfig userConfig = UserConfig.getInstance(currentAccount);
                    userConfig.clearConfig();
                    userConfig.setCurrentUser(botUser);
                    userConfig.saveConfig(true);

                    ZentrixConfig.setBotMode(true, sanitizedToken);

                    ArrayList<TLRPC.User> users = new ArrayList<>();
                    users.add(botUser);
                    MessagesStorage.getInstance(currentAccount).putUsersAndChats(users, null, true, true);
                    MessagesController.getInstance(currentAccount).putUser(botUser, false);

                    ConnectionsManager connectionsManager = ConnectionsManager.getInstance(currentAccount);
                    connectionsManager.updateDcSettings();
                    connectionsManager.checkConnection();

                    NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
                    nc.postNotificationName(NotificationCenter.mainUserInfoChanged);
                    nc.postNotificationName(NotificationCenter.didUpdateConnectionState);
                    nc.postNotificationName(NotificationCenter.dialogsNeedReload);

                    if (callback != null) {
                        callback.onSuccess(botUser);
                    }

                    openMainActivity(currentAccount);
                } else {
                    if (callback != null) {
                        callback.onError(-3, "Unexpected authorization response.");
                    }
                }
            });
        }, flags);
    }

    private static void openMainActivity(int account) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return;
        try {
            Intent intent = new Intent(context, LaunchActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("currentAccount", account);
            context.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(TAG + ": Failed to start LaunchActivity", e);
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
