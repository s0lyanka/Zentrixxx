package org.telegram.messenger.zentrix.ghost;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.zentrix.ZentrixConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * Intercepts MTProto packets before transmission to implement Ghost Mode features.
 */
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

        // 1. Hide read receipts for messages
        if (request instanceof TLRPC.TL_messages_readHistory 
                || request instanceof TLRPC.TL_messages_readDiscussion
                || request instanceof TLRPC.TL_channels_readHistory) {
            if (ZentrixConfig.hideRead || ZentrixConfig.isGhostMode) {
                FileLog.d(TAG + ": Dropped readHistory packet in Ghost Mode");
                return true;
            }
        }

        // 2. Hide story read receipts
        if (request.getClass().getSimpleName().contains("readStories")
                || request instanceof TLRPC.TL_stories_readStories) {
            if (ZentrixConfig.hideStories || ZentrixConfig.isGhostMode) {
                FileLog.d(TAG + ": Dropped readStories packet in Ghost Mode");
                return true;
            }
        }

        // 3. Hide typing / upload progress
        if (request instanceof TLRPC.TL_messages_setTyping) {
            if (ZentrixConfig.hideTyping || ZentrixConfig.isGhostMode) {
                FileLog.d(TAG + ": Dropped setTyping packet in Ghost Mode");
                return true;
            }
        }

        // 4. Hide online status packets
        if (request instanceof TLRPC.TL_account_updateStatus) {
            TLRPC.TL_account_updateStatus statusReq = (TLRPC.TL_account_updateStatus) request;
            if (statusReq.offline) {
                return false;
            }
            if (ZentrixConfig.isGhostMode) {
                FileLog.d(TAG + ": Dropped updateStatus(online) packet in Ghost Mode");
                return true;
            }
        }

        return false;
    }
}