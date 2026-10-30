package org.telegram.messenger.zentrix.ghost;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.zentrix.ZentrixConfig;
import org.telegram.tgnet.TLObject;

public class ZentrixGhostFilter {

    private static final String TAG = "ZentrixGhostFilter";

    public static class Bypass extends TLObject {
        private final TLObject wrapped;

        public Bypass(TLObject wrapped) {
            this.wrapped = wrapped;
        }

        public TLObject getWrappedObject() {
            return wrapped;
        }

        public static Bypass wrap(TLObject obj) {
            return new Bypass(obj);
        }
    }

    public static boolean shouldDrop(int currentAccount, TLObject request) {
        if (request == null) {
            return false;
        }

        String name = request.getClass().getName();

        if (name.contains("readHistory") || name.contains("readDiscussion") || name.contains("ReadHistory")) {
            if (ZentrixConfig.hideRead || ZentrixConfig.isGhostMode) {
                FileLog.d(TAG + ": Dropped " + name + " in Ghost Mode");
                return true;
            }
        }

        if (name.contains("readStories") || name.contains("ReadStories")) {
            if (ZentrixConfig.hideStories || ZentrixConfig.isGhostMode) {
                FileLog.d(TAG + ": Dropped " + name + " in Ghost Mode");
                return true;
            }
        }

        if (name.contains("setTyping") || name.contains("SetTyping")) {
            if (ZentrixConfig.hideTyping || ZentrixConfig.isGhostMode) {
                FileLog.d(TAG + ": Dropped " + name + " in Ghost Mode");
                return true;
            }
        }

        if (name.contains("updateStatus") || name.contains("UpdateStatus")) {
            try {
                java.lang.reflect.Field f = request.getClass().getField("offline");
                if (f.getBoolean(request)) {
                    return false;
                }
            } catch (Throwable ignored) {}

            if (ZentrixConfig.isGhostMode) {
                FileLog.d(TAG + ": Dropped updateStatus online in Ghost Mode");
                return true;
            }
        }

        return false;
    }
}
