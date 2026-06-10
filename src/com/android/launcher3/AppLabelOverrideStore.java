package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.UserHandle;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Persists custom labels for launcher app icons.
 */
public final class AppLabelOverrideStore {

    public static final Uri APP_LABEL_OVERRIDES_URI =
            Uri.parse("content://com.android.launcher3.app_label_overrides");

    private static final Object sLock = new Object();
    private static Map<String, String> sOverrides;

    private AppLabelOverrideStore() {}

    @Nullable
    public static String getOverride(Context context, ComponentName componentName,
            UserHandle user) {
        synchronized (sLock) {
            if (componentName == null) {
                return null;
            }
            return getOverrides(context).get(makeKey(componentName, user));
        }
    }

    public static void setOverride(Context context, ComponentName componentName, UserHandle user,
            @Nullable CharSequence title) {
        synchronized (sLock) {
            if (componentName == null) {
                return;
            }
            Map<String, String> overrides = new HashMap<>(getOverrides(context));
            String key = makeKey(componentName, user);
            if (TextUtils.isEmpty(title)) {
                overrides.remove(key);
            } else {
                overrides.put(key, title.toString());
            }
            sOverrides = overrides;
            LauncherPrefs.get(context).putSync(LauncherPrefs.APP_LABEL_OVERRIDES.to(
                    serialize(overrides)));
            context.getContentResolver().notifyChange(APP_LABEL_OVERRIDES_URI, null);
        }
    }

    private static Map<String, String> getOverrides(Context context) {
        if (sOverrides != null) {
            return sOverrides;
        }
        Map<String, String> overrides = new HashMap<>();
        String raw = LauncherPrefs.APP_LABEL_OVERRIDES.get(context);
        if (!TextUtils.isEmpty(raw)) {
            try {
                JSONObject json = new JSONObject(raw);
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = json.optString(key, null);
                    if (value != null) {
                        overrides.put(key, value);
                    }
                }
            } catch (JSONException e) {
                overrides.clear();
            }
        }
        sOverrides = overrides;
        return overrides;
    }

    private static String serialize(Map<String, String> overrides) {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            try {
                json.put(entry.getKey(), entry.getValue());
            } catch (JSONException e) {
                throw new IllegalStateException("Unable to persist app label overrides", e);
            }
        }
        return json.toString();
    }

    private static String makeKey(ComponentName componentName, UserHandle user) {
        return componentName.flattenToShortString() + "#" + user.getIdentifier();
    }
}
