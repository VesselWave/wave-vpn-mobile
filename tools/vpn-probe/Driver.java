// SPDX-License-Identifier: GPL-3.0-or-later
package me.vwave.vpnprobe.driver;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Separate process/package: inspecting another app must not stop the traffic probe. */
public class Driver extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    static String string(CharSequence value) { return value == null ? "" : value.toString(); }

    void walk(AccessibilityNodeInfo node, int parent, ArrayList<AccessibilityNodeInfo> refs,
              JSONArray rows) throws Exception {
        if (node == null || refs.size() >= 2000) return;
        int index = refs.size(); refs.add(node);
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        // Never return editable values, including an unmasked username or password.
        String label = node.isEditable() ? string(node.getHintText()) : string(node.getText());
        JSONObject row = new JSONObject().put("index", index).put("parent", parent)
            .put("label", label).put("description", node.isEditable() ? string(node.getHintText()) : string(node.getContentDescription()))
            .put("id", string(node.getViewIdResourceName())).put("package", string(node.getPackageName()))
            .put("editable", node.isEditable()).put("password", node.isPassword())
            .put("enabled", node.isEnabled()).put("checked", node.isChecked())
            .put("scrollable", node.isScrollable()).put("checkable", node.isCheckable()).put("clickable", node.isClickable()).put("visible", node.isVisibleToUser())
            .put("bounds", new JSONArray(new int[]{bounds.left, bounds.top, bounds.right, bounds.bottom}));
        rows.put(row);
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), index, refs, rows);
    }

    boolean matches(JSONObject row, JSONObject selector) throws Exception {
        if (!row.getBoolean("visible") || !row.getBoolean("enabled")) return false;
        if (selector.has("text") && !row.getString("label").equals(selector.getString("text")) && !row.getString("description").equals(selector.getString("text"))) return false;
        for (String key : new String[]{"label", "description", "id", "package"})
            if (selector.has(key) && !row.getString(key).equals(selector.getString(key))) return false;
        for (String key : new String[]{"editable", "password", "scrollable"})
            if (selector.has(key) && row.getBoolean(key) != selector.getBoolean(key)) return false;
        return !selector.has("prefix") || row.getString("label").startsWith(selector.getString("prefix"));
    }

    @Override public void onStart() {
        Bundle result = new Bundle(); File commandFile = new File(getTargetContext().getFilesDir(), "command.json");
        try {
            JSONObject command = new JSONObject(new String(Files.readAllBytes(commandFile.toPath()), StandardCharsets.UTF_8));
            Files.delete(commandFile.toPath()); // Secrets are not instrumentation arguments or retained commands.
            String action = command.getString("action");
            JSONObject response = new JSONObject().put("wall_ms", System.currentTimeMillis())
                .put("uptime_ms", SystemClock.elapsedRealtime());
            if (!action.equals("clock")) {
                UiAutomation ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
                if (action.equals("back")) {
                    if (!ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) throw new Exception("back failed");
                } else {
                    AccessibilityNodeInfo root = null;
                    for (int i = 0; i < 30 && root == null; i++) { root = ui.getRootInActiveWindow(); if (root == null) SystemClock.sleep(100); }
                    if (root == null) throw new Exception("no active accessibility root");
                    ArrayList<AccessibilityNodeInfo> refs = new ArrayList<>(); JSONArray rows = new JSONArray();
                    walk(root, -1, refs, rows);
                    if (action.equals("snapshot")) response.put("nodes", rows);
                    else {
                        JSONObject selector = command.getJSONObject("selector");
                        int ordinal = selector.optInt("ordinal", 0); boolean done = false;
                        ArrayList<AccessibilityNodeInfo> candidates = new ArrayList<>();
                        ArrayList<Integer> distances = new ArrayList<>();
                        for (int i = 0; i < rows.length(); i++) {
                            if (!matches(rows.getJSONObject(i), selector)) continue;
                            AccessibilityNodeInfo node = refs.get(i); int distance = 0;
                            if (action.equals("tap")) {
                                while (node != null && !node.isClickable()) { node = node.getParent(); distance++; }
                                if (node == null || !node.isEnabled()) continue;
                            }
                            int at = candidates.size();
                            // A button beats a matching heading whose distant root happens to be clickable.
                            while (at > 0 && distances.get(at - 1) > distance) at--;
                            candidates.add(at, node); distances.add(at, distance);
                        }
                        if (ordinal < candidates.size()) {
                            AccessibilityNodeInfo node = candidates.get(ordinal);
                            if (action.equals("tap")) done = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            else if (action.equals("fill")) {
                                Bundle value = new Bundle();
                                value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command.getString("value"));
                                done = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value);
                            } else if (action.equals("scroll")) done = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                            else throw new Exception("unknown action");
                        }
                        if (!done) throw new Exception("selector/action did not succeed");
                    }
                }
            }
            result.putString("result", Base64.encodeToString(response.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            finish(-1, result);
        } catch (Exception error) {
            commandFile.delete();
            // Exception messages and selectors may contain editable data. Return only a fixed diagnostic.
            result.putString("error", "UI command failed; no result is valid"); finish(0, result);
        }
    }
}
