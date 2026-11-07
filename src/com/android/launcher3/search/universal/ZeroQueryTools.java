package com.android.launcher3.search.universal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Process;
import android.provider.AlarmClock;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;

import java.util.ArrayList;
import java.util.List;

public final class ZeroQueryTools {

    private ZeroQueryTools() {}

    public static List<UniversalSearchResult> getQuickTools(Context context) {
        List<UniversalSearchResult> tools = new ArrayList<>();
        boolean isThemed = LauncherPrefs.DOCK_THEME.get(context);

        // 1. Timer
        Intent timerIntent = new Intent(AlarmClock.ACTION_SET_TIMER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (context.getPackageManager().resolveActivity(timerIntent, 0) == null) {
            timerIntent = new Intent(AlarmClock.ACTION_SHOW_ALARMS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        UniversalSearchResult timer = new UniversalSearchResult(
                UniversalSearchResult.SOURCE_QUICK_ACTION, "quick_tool_timer",
                context.getString(R.string.search_quick_tool_timer),
                context.getString(R.string.search_quick_tool_timer_desc),
                timerIntent, Process.myUserHandle(), 0);
        timer.iconRes = R.drawable.ic_search_timer;
        tools.add(timer);

        // 2. Calculator
        Intent calcIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_APP_CALCULATOR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (context.getPackageManager().resolveActivity(calcIntent, 0) == null) {
            for (String pkg : new String[]{"com.google.android.calculator", "com.android.calculator2"}) {
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    calcIntent = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    break;
                }
            }
        }
        if (context.getPackageManager().resolveActivity(calcIntent, 0) != null) {
            UniversalSearchResult calc = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_CALCULATOR, "quick_tool_calculator",
                    context.getString(R.string.search_quick_tool_calculator),
                    context.getString(R.string.search_quick_tool_calculator_desc),
                    calcIntent, Process.myUserHandle(), 0);
            calc.iconRes = R.drawable.ic_search_calculator;
            tools.add(calc);
        }

        // 3. Scan QR / Google Lens
        Intent lensIntent = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(Utilities.GSA_PACKAGE, Utilities.LENS_ACTIVITY))
                .setData(Uri.parse(Utilities.LENS_URI))
                .putExtra("LensHomescreenShortcut", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (context.getPackageManager().resolveActivity(lensIntent, 0) != null) {
            UniversalSearchResult lens = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_QUICK_ACTION, "quick_tool_lens",
                    context.getString(R.string.search_quick_tool_lens),
                    context.getString(R.string.search_quick_tool_lens_desc),
                    lensIntent, Process.myUserHandle(), 0);
            lens.iconRes = isThemed ? R.drawable.ic_lens_themed : R.drawable.ic_lens_color;
            tools.add(lens);
        }

        // 4. Flashlight Toggle
        Boolean torchState = TileToggles.state(context, "flashlight");
        if (torchState != null) {
            UniversalSearchResult flash = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_QS_TILE, "quick_tool_flashlight",
                    context.getString(R.string.search_quick_tool_flashlight),
                    context.getString(R.string.search_quick_tool_flashlight_desc),
                    null, Process.myUserHandle(), 0);
            flash.iconRes = R.drawable.ic_flashlight;
            flash.checked = Boolean.TRUE.equals(torchState);
            flash.toggle = (c, on) -> TileToggles.set(c, "flashlight", on);
            tools.add(flash);
        }

        // 5. Sound Search
        Intent musicIntent = new Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (context.getPackageManager().resolveActivity(musicIntent, 0) != null) {
            UniversalSearchResult music = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_QUICK_ACTION, "quick_tool_sound_search",
                    context.getString(R.string.search_quick_tool_sound_search),
                    context.getString(R.string.search_quick_tool_sound_search_desc),
                    musicIntent, Process.myUserHandle(), 0);
            music.iconRes = isThemed ? R.drawable.ic_music_themed : R.drawable.ic_music_color;
            tools.add(music);
        }

        return tools;
    }
}
