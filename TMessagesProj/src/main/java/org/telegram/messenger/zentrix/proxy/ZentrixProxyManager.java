package org.telegram.messenger.zentrix.proxy;

import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.zentrix.ZentrixConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.util.HashMap;
import java.util.Map;

public class ZentrixProxyManager {

    private static final String TAG = "ZentrixProxyManager";

    public static class ProxyConfig {
        public String protocol; // vless or hysteria2
        public String server;
        public int port;
        public String uuid;     // password / uuid
        public Map<String, String> params = new HashMap<>();

        @Override
        public String toString() {
            return protocol + "://" + server + ":" + port;
        }
    }

    public static ProxyConfig parseConfig(String uriString) {
        if (TextUtils.isEmpty(uriString)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(uriString.trim());
            ProxyConfig config = new ProxyConfig();
            config.protocol = uri.getScheme();
            config.server = uri.getHost();
            config.port = uri.getPort() > 0 ? uri.getPort() : 443;
            config.uuid = uri.getUserInfo();

            if (uri.getQuery() != null) {
                for (String param : uri.getQuery().split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2) {
                        config.params.put(pair[0], pair[1]);
                    }
                }
            }
            return config;
        } catch (Exception e) {
            FileLog.e(TAG + ": Failed to parse proxy URI " + uriString, e);
            return null;
        }
    }

    public static void setBypassEnabled(boolean enable) {
        ZentrixConfig.setVpnEnabled(enable);
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("proxy_enabled", enable);

        if (enable) {
            SharedConfig.ProxyInfo proxy = SharedConfig.addProxy(new SharedConfig.ProxyInfo("127.0.0.1", 10808, "", "", ""));
            SharedConfig.currentProxy = proxy;
            editor.putString("proxy_ip", "127.0.0.1");
            editor.putInt("proxy_port", 10808);
            editor.putString("proxy_user", "");
            editor.putString("proxy_pass", "");
            editor.putString("proxy_secret", "");
            editor.apply();
            ConnectionsManager.setProxySettings(true, "127.0.0.1", 10808, "", "", "");
        } else {
            editor.apply();
            ConnectionsManager.setProxySettings(false, "", 1080, "", "", "");
        }
    }
}
