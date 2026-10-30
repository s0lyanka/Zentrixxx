package org.telegram.messenger.zentrix.proxy;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.zentrix.ZentrixConfig;

/**
 * Manages VLESS and Hysteria 2 proxy configurations for internal Telegram bypass without requiring system VPN permissions.
 */
public class ZentrixProxyManager {

    private static final String TAG = "ZentrixProxyManager";

    public static class ProxyConfig {
        public String type; // VLESS, HYSTERIA2
        public String host;
        public int port;
        public String auth; // UUID or Password
        public String sni;
        public String publicKey;
        public String shortId;
        public String rawUri;

        public boolean isValid() {
            return !TextUtils.isEmpty(host) && port > 0;
        }
    }

    /**
     * Parses standard VLESS or Hysteria 2 subscription link.
     */
    public static ProxyConfig parseConfig(String uriString) {
        if (TextUtils.isEmpty(uriString)) return null;

        try {
            Uri uri = Uri.parse(uriString.trim());
            String scheme = uri.getScheme();
            if (scheme == null) return null;

            ProxyConfig config = new ProxyConfig();
            config.rawUri = uriString.trim();
            config.host = uri.getHost();
            config.port = uri.getPort();

            if (scheme.equalsIgnoreCase("vless")) {
                config.type = ZentrixConfig.VPN_TYPE_VLESS;
                config.auth = uri.getUserInfo();
                config.sni = uri.getQueryParameter("sni");
                config.publicKey = uri.getQueryParameter("pbk");
                config.shortId = uri.getQueryParameter("sid");
            } else if (scheme.equalsIgnoreCase("hy2") || scheme.equalsIgnoreCase("hysteria2")) {
                config.type = ZentrixConfig.VPN_TYPE_HYSTERIA2;
                config.auth = uri.getUserInfo();
                config.sni = uri.getQueryParameter("sni");
            }

            return config.isValid() ? config : null;
        } catch (Exception e) {
            FileLog.e(TAG + ": Failed to parse proxy URI", e);
            return null;
        }
    }

    /**
     * Enables or disables internal bypass proxy for Telegram traffic.
     */
    public static void setBypassEnabled(boolean enable) {
        ZentrixConfig.setVpnEnabled(enable);
        if (enable) {
            ProxyConfig config = parseConfig(ZentrixConfig.vpnConfigUrl);
            if (config != null) {
                // Route Telegram through local proxy endpoint (default 127.0.0.1:10808)
                SharedConfig.ProxyInfo proxy = SharedConfig.addProxy(new SharedConfig.ProxyInfo("127.0.0.1", 10808, "", "", ""));
                SharedConfig.currentProxy = proxy;
                SharedConfig.setProxyEnable(true);
            }
        } else {
            SharedConfig.setProxyEnable(false);
        }
    }
}