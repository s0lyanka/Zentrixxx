package org.telegram.messenger.zentrix.music;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Handles music track discovery across chats/channels, local phone storage, and batch track forwarding.
 */
public class ZentrixMusicManager {

    private static final String TAG = "ZentrixMusicManager";

    public interface MusicCallback {
        void onLoaded(ArrayList<MessageObject> tracks);
    }

    public interface ForwardCallback {
        void onProgress(int forwarded, int total);
        void onComplete();
    }

    public static class LocalTrack {
        public long id;
        public String title;
        public String artist;
        public String path;
        public long duration;

        public LocalTrack(long id, String title, String artist, String path, long duration) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.path = path;
            this.duration = duration;
        }
    }

    /**
     * Loads audio tracks from any specified Telegram dialog (channel, group, or saved messages).
     */
    public static void loadTracksFromChat(int currentAccount, long dialogId, int count, MusicCallback callback) {
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            try {
                // Query media storage for audio type (MEDIA_AUDIO = 1)
                MessagesStorage.getInstance(currentAccount).loadMedia(
                        dialogId,
                        0,      // offset
                        count > 0 ? count : 100, // count
                        0,      // max_id
                        MessagesStorage.MEDIA_AUDIO,
                        0,      // classGuid
                        false   // loadFromCacheOnly
                );
            } catch (Exception e) {
                FileLog.e(TAG + ": Failed to load chat audio", e);
            }
        });
    }

    /**
     * Scans phone's internal storage for local audio tracks via MediaStore.
     */
    public static ArrayList<LocalTrack> loadLocalTracks() {
        ArrayList<LocalTrack> list = new ArrayList<>();
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return list;

        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

            try (Cursor cursor = cr.query(uri, null, selection, null, sortOrder)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                    int titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                    int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                    int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                    int durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);

                    do {
                        long id = cursor.getLong(idCol);
                        String title = cursor.getString(titleCol);
                        String artist = cursor.getString(artistCol);
                        String path = cursor.getString(dataCol);
                        long duration = cursor.getLong(durationCol);

                        list.add(new LocalTrack(id, title, artist, path, duration));
                    } while (cursor.moveToNext());
                }
            }
        } catch (Exception e) {
            FileLog.e(TAG + ": Failed to load local tracks", e);
        }

        return list;
    }

    /**
     * Batch forwards all audio files from source dialog to target dialog.
     */
    public static void forwardAllAudio(int currentAccount, ArrayList<MessageObject> audioMessages, long targetDialogId, ForwardCallback callback) {
        if (audioMessages == null || audioMessages.isEmpty()) {
            if (callback != null) callback.onComplete();
            return;
        }

        AndroidUtilities.runOnUIThread(() -> {
            SendMessagesHelper helper = SendMessagesHelper.getInstance(currentAccount);
            ArrayList<MessageObject> batch = new ArrayList<>();

            for (int i = 0; i < audioMessages.size(); i++) {
                batch.add(audioMessages.get(i));
                // Forward in batches of 50 (Telegram MTProto limit)
                if (batch.size() == 50 || i == audioMessages.size() - 1) {
                    helper.sendMessage(new ArrayList<>(batch), targetDialogId, false, false, true, 0, 0);
                    if (callback != null) {
                        callback.onProgress(i + 1, audioMessages.size());
                    }
                    batch.clear();
                }
            }

            if (callback != null) {
                callback.onComplete();
            }
        });
    }
}