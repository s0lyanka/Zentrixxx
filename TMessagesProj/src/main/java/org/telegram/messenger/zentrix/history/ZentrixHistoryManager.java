package org.telegram.messenger.zentrix.history;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.zentrix.ZentrixConfig;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Anti-Delete message persistence and Edited Message History for Zentrix mod.
 * Based on Telegram 12.4.1 / NekoX architecture.
 */
public class ZentrixHistoryManager {

    private static final String TAG = "ZentrixHistoryManager";
    private static final String DATABASE_NAME = "zentrix_history.db";
    private static final int DATABASE_VERSION = 1;

    // Dedicated asynchronous queue for background SQLite database operations
    private static final DispatchQueue historyQueue = new DispatchQueue("zentrixHistoryQueue");

    // In-memory cache for deleted message keys: "account_dialogId_messageId"
    private static final Set<String> deletedCache = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile boolean deletedCacheLoaded = false;

    // In-memory cache for edit history: "account_dialogId_messageId" -> list of edits
    private static final ConcurrentHashMap<String, ArrayList<EditHistoryEntry>> editHistoryCache = new ConcurrentHashMap<>();

    private static volatile HistoryDbHelper dbHelper;

    static {
        ensureInitialized();
    }

    private static synchronized void ensureInitialized() {
        if (dbHelper == null) {
            Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                dbHelper = new HistoryDbHelper(context);
                historyQueue.postRunnable(ZentrixHistoryManager::preloadDeletedCache);
            }
        }
    }

    private static String makeKey(int currentAccount, long dialogId, int messageId) {
        return currentAccount + "_" + dialogId + "_" + messageId;
    }

    // =========================================================================
    // 1. Anti-Delete Message Management
    // =========================================================================

    /**
     * Checks if a message has been deleted. Fast O(1) in-memory lookup.
     */
    public static boolean isDeleted(int currentAccount, long dialogId, int messageId) {
        ensureInitialized();
        String key = makeKey(currentAccount, dialogId, messageId);
        if (deletedCache.contains(key)) {
            return true;
        }
        if (!deletedCacheLoaded) {
            return checkDeletedInDb(currentAccount, dialogId, messageId);
        }
        return false;
    }

    /**
     * Marks a specific message as deleted both in the in-memory cache and persistently in SQLite.
     */
    public static void markDeleted(int currentAccount, long dialogId, int messageId) {
        ensureInitialized();
        String key = makeKey(currentAccount, dialogId, messageId);
        deletedCache.add(key);

        int now = (int) (System.currentTimeMillis() / 1000L);
        historyQueue.postRunnable(() -> insertDeletedInDb(currentAccount, dialogId, messageId, now));
    }

    /**
     * Callback invoked when a batch of messages is reported as deleted.
     * Records them persistently and notifies the UI to display anti-delete marks.
     */
    public static void onMessagesDeleted(int currentAccount, long dialogId, ArrayList<Integer> messageIds) {
        if (!ZentrixConfig.saveDeletedMessages) {
            return;
        }
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        ensureInitialized();

        int now = (int) (System.currentTimeMillis() / 1000L);
        ArrayList<Integer> copyIds = new ArrayList<>(messageIds);

        for (Integer mid : copyIds) {
            if (mid != null) {
                deletedCache.add(makeKey(currentAccount, dialogId, mid));
            }
        }

        historyQueue.postRunnable(() -> insertDeletedBatchInDb(currentAccount, dialogId, copyIds, now));

        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(currentAccount).postNotificationName(
                    NotificationCenter.updateInterfaces,
                    MessagesController.UPDATE_MASK_CHAT
            );
        });
    }

    /**
     * Intercepts delete operations originating from MessagesStorage.
     * When saveDeletedMessages is active, records the messages so they remain in storage
     * and can be rendered with deleted indicators in the UI.
     */
    public static void hookStorageDelete(int currentAccount, long dialogId, ArrayList<Integer> markAsDeletedMessages) {
        if (markAsDeletedMessages == null || markAsDeletedMessages.isEmpty()) {
            return;
        }
        if (!ZentrixConfig.saveDeletedMessages) {
            return;
        }

        // Record deleted messages into cache and DB
        onMessagesDeleted(currentAccount, dialogId, markAsDeletedMessages);

        // Clear the list to intercept MessagesStorage from purging rows from messages_v2
        try {
            markAsDeletedMessages.clear();
        } catch (Throwable t) {
            FileLog.e(TAG + ": Failed to clear markAsDeletedMessages", t);
        }
    }

    /**
     * Clears deleted status for a message.
     */
    public static void clearDeleted(int currentAccount, long dialogId, int messageId) {
        ensureInitialized();
        String key = makeKey(currentAccount, dialogId, messageId);
        deletedCache.remove(key);

        if (dbHelper != null) {
            historyQueue.postRunnable(() -> {
                try {
                    SQLiteDatabase db = dbHelper.getWritableDatabase();
                    db.delete("deleted_messages", "account = ? AND dialog_id = ? AND message_id = ?",
                            new String[]{String.valueOf(currentAccount), String.valueOf(dialogId), String.valueOf(messageId)});
                } catch (Throwable e) {
                    FileLog.e(TAG + ": clearDeleted failed", e);
                }
            });
        }
    }

    // =========================================================================
    // 2. Edited Message History Management
    // =========================================================================

    /**
     * Data structure encapsulating a message edit version.
     */
    public static class EditHistoryEntry {
        public String text;
        public int date;
        public ArrayList<TLRPC.MessageEntity> entities;

        public EditHistoryEntry() {
        }

        public EditHistoryEntry(String text, int date, ArrayList<TLRPC.MessageEntity> entities) {
            this.text = text != null ? text : "";
            this.date = date;
            this.entities = entities;
        }
    }

    /**
     * Callback invoked when an existing message is edited.
     * Records the previous content version before modification.
     */
    public static void onMessageEdited(int currentAccount, long dialogId, int messageId, String oldText, int oldDate, ArrayList<TLRPC.MessageEntity> oldEntities) {
        if (!ZentrixConfig.saveEditedMessages) {
            return;
        }
        ensureInitialized();

        final String text = oldText != null ? oldText : "";
        final int date = oldDate > 0 ? oldDate : (int) (System.currentTimeMillis() / 1000L);
        final EditHistoryEntry entry = new EditHistoryEntry(text, date, oldEntities);
        final String key = makeKey(currentAccount, dialogId, messageId);

        editHistoryCache.compute(key, (k, list) -> {
            if (list == null) {
                list = loadEditHistoryFromDb(currentAccount, dialogId, messageId);
                if (list == null) {
                    list = new ArrayList<>();
                }
            }
            boolean duplicate = false;
            for (EditHistoryEntry existing : list) {
                if (existing.date == entry.date && TextUtils.equals(existing.text, entry.text)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                list.add(entry);
            }
            return list;
        });

        historyQueue.postRunnable(() -> insertEditedInDb(currentAccount, dialogId, messageId, entry));
    }

    /**
     * Retrieves the chronological list of edit history entries for a given message.
     */
    public static ArrayList<EditHistoryEntry> getEditHistory(int currentAccount, long dialogId, int messageId) {
        ensureInitialized();
        String key = makeKey(currentAccount, dialogId, messageId);
        ArrayList<EditHistoryEntry> cached = editHistoryCache.get(key);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        ArrayList<EditHistoryEntry> fromDb = loadEditHistoryFromDb(currentAccount, dialogId, messageId);
        if (fromDb != null && !fromDb.isEmpty()) {
            editHistoryCache.put(key, fromDb);
            return new ArrayList<>(fromDb);
        }

        return new ArrayList<>();
    }

    /**
     * Checks whether an edit history exists for a message.
     */
    public static boolean hasEditHistory(int currentAccount, long dialogId, int messageId) {
        ArrayList<EditHistoryEntry> history = getEditHistory(currentAccount, dialogId, messageId);
        return history != null && !history.isEmpty();
    }

    /**
     * Clears recorded edit history for a specific message.
     */
    public static void clearEditHistory(int currentAccount, long dialogId, int messageId) {
        ensureInitialized();
        String key = makeKey(currentAccount, dialogId, messageId);
        editHistoryCache.remove(key);

        if (dbHelper != null) {
            historyQueue.postRunnable(() -> {
                try {
                    SQLiteDatabase db = dbHelper.getWritableDatabase();
                    db.delete("edited_messages", "account = ? AND dialog_id = ? AND message_id = ?",
                            new String[]{String.valueOf(currentAccount), String.valueOf(dialogId), String.valueOf(messageId)});
                } catch (Throwable e) {
                    FileLog.e(TAG + ": clearEditHistory failed", e);
                }
            });
        }
    }

    /**
     * Displays a clean, styled AlertDialog presenting the timestamped edit history.
     */
    public static void showEditHistoryDialog(Context context, int currentAccount, long dialogId, int messageId) {
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        if (context == null) {
            return;
        }

        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            final Context finalContext = context;
            AndroidUtilities.runOnUIThread(() -> showEditHistoryDialog(finalContext, currentAccount, dialogId, messageId));
            return;
        }

        ArrayList<EditHistoryEntry> history = getEditHistory(currentAccount, dialogId, messageId);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Edit History");

        if (history == null || history.isEmpty()) {
            builder.setMessage("No edit history recorded for this message.");
            builder.setPositiveButton("OK", null);
            builder.show();
            return;
        }

        ScrollView scrollView = new ScrollView(context);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(12));
        scrollView.addView(container, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        SimpleDateFormat fallbackFormat = new SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault());
        int size = history.size();

        for (int i = 0; i < size; i++) {
            EditHistoryEntry entry = history.get(i);
            if (entry == null) continue;

            if (i > 0) {
                View divider = new View(context);
                divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
                LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        AndroidUtilities.dp(1)
                );
                divParams.topMargin = AndroidUtilities.dp(12);
                divParams.bottomMargin = AndroidUtilities.dp(12);
                container.addView(divider, divParams);
            }

            LinearLayout headerLayout = new LinearLayout(context);
            headerLayout.setOrientation(LinearLayout.HORIZONTAL);

            TextView versionView = new TextView(context);
            versionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            versionView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
            versionView.setTextColor(Theme.getColor(Theme.key_dialogTextBlue2));
            if (i == 0) {
                versionView.setText("Initial Message (v1)");
            } else {
                versionView.setText("Edit #" + i + " (v" + (i + 1) + ")");
            }

            TextView dateView = new TextView(context);
            dateView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            dateView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
            String formattedDate = fallbackFormat.format(new Date(entry.date * 1000L));
            dateView.setText(" • " + formattedDate);

            headerLayout.addView(versionView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            headerLayout.addView(dateView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            container.addView(headerLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

            TextView messageView = new TextView(context);
            messageView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            messageView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            messageView.setTextIsSelectable(true);
            messageView.setText(applyEntities(entry.text, entry.entities));

            container.addView(messageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        builder.setView(scrollView);
        builder.setPositiveButton("Close", null);
        builder.setNeutralButton("Clear History", (dialog, which) -> {
            clearEditHistory(currentAccount, dialogId, messageId);
        });

        builder.show();
    }

    /**
     * Applies standard message styling entities (bold, italic, monospace, strikethrough, underline, links).
     */
    private static CharSequence applyEntities(CharSequence text, ArrayList<TLRPC.MessageEntity> entities) {
        if (TextUtils.isEmpty(text) || entities == null || entities.isEmpty()) {
            return text;
        }
        try {
            SpannableStringBuilder builder = new SpannableStringBuilder(text);
            for (TLRPC.MessageEntity entity : entities) {
                if (entity == null) continue;
                int start = entity.offset;
                int end = entity.offset + entity.length;
                if (start < 0 || end > builder.length() || start >= end) {
                    continue;
                }
                if (entity instanceof TLRPC.TL_messageEntityBold) {
                    builder.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (entity instanceof TLRPC.TL_messageEntityItalic) {
                    builder.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (entity instanceof TLRPC.TL_messageEntityCode || entity instanceof TLRPC.TL_messageEntityPre) {
                    builder.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (entity instanceof TLRPC.TL_messageEntityStrike) {
                    builder.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (entity instanceof TLRPC.TL_messageEntityUnderline) {
                    builder.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                    builder.setSpan(new URLSpan(((TLRPC.TL_messageEntityTextUrl) entity).url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (entity instanceof TLRPC.TL_messageEntityUrl) {
                    builder.setSpan(new URLSpan(builder.subSequence(start, end).toString()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            return builder;
        } catch (Throwable e) {
            FileLog.e(TAG + ": applyEntities failed", e);
            return text;
        }
    }

    // =========================================================================
    // 3. Entity Serialization Helpers
    // =========================================================================

    private static byte[] serializeEntities(ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        try {
            SerializedData data = new SerializedData();
            data.writeInt32(entities.size());
            for (int i = 0; i < entities.size(); i++) {
                entities.get(i).serializeToStream(data);
            }
            return data.toByteArray();
        } catch (Throwable e) {
            FileLog.e(TAG + ": serializeEntities failed", e);
            return null;
        }
    }

    private static ArrayList<TLRPC.MessageEntity> deserializeEntities(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            SerializedData data = new SerializedData(bytes);
            int count = data.readInt32(false);
            ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int constructor = data.readInt32(false);
                TLRPC.MessageEntity entity = TLRPC.MessageEntity.TLdeserialize(data, constructor, false);
                if (entity != null) {
                    entities.add(entity);
                }
            }
            return entities;
        } catch (Throwable e) {
            FileLog.e(TAG + ": deserializeEntities failed", e);
            return null;
        }
    }

    // =========================================================================
    // 4. SQLite Storage Implementation
    // =========================================================================

    private static class HistoryDbHelper extends SQLiteOpenHelper {

        public HistoryDbHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS deleted_messages (" +
                    "account INTEGER NOT NULL, " +
                    "dialog_id INTEGER NOT NULL, " +
                    "message_id INTEGER NOT NULL, " +
                    "date INTEGER, " +
                    "PRIMARY KEY (account, dialog_id, message_id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_del_msg ON deleted_messages (account, dialog_id, message_id)");

            db.execSQL("CREATE TABLE IF NOT EXISTS edited_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "account INTEGER NOT NULL, " +
                    "dialog_id INTEGER NOT NULL, " +
                    "message_id INTEGER NOT NULL, " +
                    "text TEXT, " +
                    "date INTEGER, " +
                    "entities BLOB)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_edit_msg ON edited_messages (account, dialog_id, message_id)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS deleted_messages");
            db.execSQL("DROP TABLE IF EXISTS edited_messages");
            onCreate(db);
        }
    }

    private static void preloadDeletedCache() {
        if (dbHelper == null) return;
        Cursor cursor = null;
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            cursor = db.rawQuery("SELECT account, dialog_id, message_id FROM deleted_messages", null);
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    int acc = cursor.getInt(0);
                    long did = cursor.getLong(1);
                    int mid = cursor.getInt(2);
                    deletedCache.add(makeKey(acc, did, mid));
                } while (cursor.moveToNext());
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": preloadDeletedCache failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            deletedCacheLoaded = true;
        }
    }

    private static boolean checkDeletedInDb(int account, long dialogId, int messageId) {
        if (dbHelper == null) return false;
        Cursor cursor = null;
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            cursor = db.rawQuery(
                    "SELECT 1 FROM deleted_messages WHERE account = ? AND dialog_id = ? AND message_id = ? LIMIT 1",
                    new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(messageId)}
            );
            return cursor != null && cursor.moveToFirst();
        } catch (Throwable e) {
            FileLog.e(TAG + ": checkDeletedInDb failed", e);
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static void insertDeletedInDb(int account, long dialogId, int messageId, int date) {
        if (dbHelper == null) return;
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("account", account);
            cv.put("dialog_id", dialogId);
            cv.put("message_id", messageId);
            cv.put("date", date);
            db.insertWithOnConflict("deleted_messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        } catch (Throwable e) {
            FileLog.e(TAG + ": insertDeletedInDb failed", e);
        }
    }

    private static void insertDeletedBatchInDb(int account, long dialogId, ArrayList<Integer> messageIds, int date) {
        if (dbHelper == null) return;
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                SQLiteStatement stmt = db.compileStatement(
                        "INSERT OR IGNORE INTO deleted_messages (account, dialog_id, message_id, date) VALUES (?, ?, ?, ?)"
                );
                for (Integer mid : messageIds) {
                    if (mid == null) continue;
                    stmt.clearBindings();
                    stmt.bindLong(1, account);
                    stmt.bindLong(2, dialogId);
                    stmt.bindLong(3, mid);
                    stmt.bindLong(4, date);
                    stmt.executeInsert();
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": insertDeletedBatchInDb failed", e);
        }
    }

    private static void insertEditedInDb(int account, long dialogId, int messageId, EditHistoryEntry entry) {
        if (dbHelper == null) return;
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("account", account);
            cv.put("dialog_id", dialogId);
            cv.put("message_id", messageId);
            cv.put("text", entry.text);
            cv.put("date", entry.date);
            byte[] entitiesBytes = serializeEntities(entry.entities);
            if (entitiesBytes != null) {
                cv.put("entities", entitiesBytes);
            } else {
                cv.putNull("entities");
            }
            db.insert("edited_messages", null, cv);
        } catch (Throwable e) {
            FileLog.e(TAG + ": insertEditedInDb failed", e);
        }
    }

    private static ArrayList<EditHistoryEntry> loadEditHistoryFromDb(int account, long dialogId, int messageId) {
        ArrayList<EditHistoryEntry> list = new ArrayList<>();
        if (dbHelper == null) return list;
        Cursor cursor = null;
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            cursor = db.query(
                    "edited_messages",
                    new String[]{"text", "date", "entities"},
                    "account = ? AND dialog_id = ? AND message_id = ?",
                    new String[]{String.valueOf(account), String.valueOf(dialogId), String.valueOf(messageId)},
                    null,
                    null,
                    "id ASC"
            );
            if (cursor != null && cursor.moveToFirst()) {
                int textCol = cursor.getColumnIndex("text");
                int dateCol = cursor.getColumnIndex("date");
                int entitiesCol = cursor.getColumnIndex("entities");
                do {
                    String text = cursor.getString(textCol);
                    int date = cursor.getInt(dateCol);
                    byte[] entitiesBytes = cursor.isNull(entitiesCol) ? null : cursor.getBlob(entitiesCol);
                    ArrayList<TLRPC.MessageEntity> entities = deserializeEntities(entitiesBytes);
                    list.add(new EditHistoryEntry(text, date, entities));
                } while (cursor.moveToNext());
            }
        } catch (Throwable e) {
            FileLog.e(TAG + ": loadEditHistoryFromDb failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return list;
    }
}
