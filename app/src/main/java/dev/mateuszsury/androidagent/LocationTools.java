package dev.mateuszsury.androidagent;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * get_location: one current fix (LocationManager.getCurrentLocation) with a timeout, falling back to
 * the last known one. Uses only the consent the user gave on the Permissions screen; it never asks.
 */
final class LocationTools implements ToolProviders.Provider {
    static boolean fineGranted(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean coarseGranted(Context c) {
        return fineGranted(c)
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static JSONObject locate(Context app, boolean wantFine, int timeoutS, int maxAgeS) throws Exception {
        if (!coarseGranted(app))
            throw new SecurityException("Location permission is off. Ask the user to allow Lokalizacja in androidagent > "
                    + "Uprawnienia. This tool never shows a permission dialog itself.");
        boolean fine = wantFine && fineGranted(app);
        LocationManager lm = app.getSystemService(LocationManager.class);
        if (lm == null) throw new IllegalStateException("This phone has no location service.");
        if (!lm.isLocationEnabled())
            throw new IllegalStateException("Location is turned off on this phone. Ask the user to turn it on in quick settings.");
        List<String> providers = new ArrayList<>();
        List<String> enabled = lm.getProviders(true);
        if (Build.VERSION.SDK_INT >= 31 && enabled.contains(LocationManager.FUSED_PROVIDER)) providers.add(LocationManager.FUSED_PROVIDER);
        if (fine && enabled.contains(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER);
        if (enabled.contains(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER);
        if (providers.isEmpty() && enabled.contains(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER);

        Location recent = lastKnown(lm, enabled);
        if (recent != null && ageSeconds(recent) <= maxAgeS && (!fine || recent.getAccuracy() <= 100))
            return describe(recent, "recent", fine, wantFine);

        AtomicReference<Location> best = new AtomicReference<>();
        CountDownLatch first = new CountDownLatch(1);
        List<CancellationSignal> signals = new ArrayList<>();
        for (String provider : providers) {
            CancellationSignal signal = new CancellationSignal();
            signals.add(signal);
            try {
                lm.getCurrentLocation(provider, signal, Runnable::run, location -> {
                    if (location == null) return;
                    synchronized (best) {
                        Location current = best.get();
                        if (current == null || location.getAccuracy() < current.getAccuracy()) best.set(location);
                    }
                    first.countDown();
                });
            } catch (RuntimeException ignored) {
                // A provider can disappear between listing and the request.
            }
        }
        long deadline = SystemClock.elapsedRealtime() + timeoutS * 1000L;
        try {
            if (!providers.isEmpty()) first.await(timeoutS, TimeUnit.SECONDS);
            // A coarse network fix often arrives first; give GPS a few more seconds for "fine".
            Location got = best.get();
            if (got != null && fine && got.getAccuracy() > 50) {
                long extra = Math.min(6000, deadline - SystemClock.elapsedRealtime());
                long until = SystemClock.elapsedRealtime() + Math.max(0, extra);
                while (SystemClock.elapsedRealtime() < until && best.get().getAccuracy() > 50) Thread.sleep(250);
            }
        } finally {
            for (CancellationSignal s : signals) s.cancel();
        }
        Location got = best.get();
        if (got != null) return describe(got, "current", fine, wantFine);
        Location last = lastKnown(lm, enabled);
        if (last != null) return describe(last, "last_known", fine, wantFine);
        throw new IllegalStateException("No location fix within " + timeoutS + " s. Android gives location to androidagent "
                + "only while it is open on screen; ask the user to keep androidagent open (and be outdoors for GPS), then retry.");
    }

    private static Location lastKnown(LocationManager lm, List<String> providers) {
        Location best = null;
        for (String p : providers) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos())) best = l;
            } catch (RuntimeException ignored) {
            }
        }
        return best;
    }

    private static long ageSeconds(Location l) {
        return Math.max(0, (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000_000L);
    }

    private static JSONObject describe(Location l, String source, boolean fine, boolean wantFine) throws JSONException {
        JSONObject o = new JSONObject().put("latitude", l.getLatitude()).put("longitude", l.getLongitude())
                .put("source", source).put("provider", String.valueOf(l.getProvider()))
                .put("precision", fine ? "fine" : "coarse").put("age_s", ageSeconds(l))
                .put("time", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(new Date(l.getTime())));
        if (l.hasAccuracy()) o.put("accuracy_m", Math.round(l.getAccuracy()));
        if (l.hasAltitude()) o.put("altitude_m", Math.round(l.getAltitude()));
        if (l.hasSpeed()) o.put("speed_mps", Math.round(l.getSpeed() * 10) / 10.0);
        if (l.hasBearing()) o.put("bearing_deg", Math.round(l.getBearing()));
        if (wantFine && !fine)
            o.put("note", "The user allowed only approximate location; this fix is coarse (about 1-3 km).");
        return o;
    }

    @Override public JSONArray list(Context app) throws JSONException {
        JSONArray t = new JSONArray();
        JSONObject accuracy = ToolProviders.prop("string", "coarse (default, about 1-3 km, network) or fine (GPS, needs precise "
                + "location allowed)").put("enum", new JSONArray().put("coarse").put("fine"));
        t.put(ToolProviders.tool("get_location", "The phone's current location (latitude, longitude, accuracy, time). "
                        + "Call only when the user's request needs it. Works while androidagent is open on screen and the user "
                        + "allowed Lokalizacja in androidagent > Uprawnienia; never shows a permission dialog.",
                new JSONObject().put("accuracy", accuracy)
                        .put("timeout_s", ToolProviders.prop("integer", "Seconds to wait for a fresh fix, 5-50, default 20"))
                        .put("max_age_s", ToolProviders.prop("integer", "Accept a cached fix this recent, default 60"))));
        return t;
    }

    @Override public boolean handles(String name) {
        return "get_location".equals(name);
    }

    @Override public JSONObject call(Context app, String caller, String name, JSONObject a) throws Exception {
        return ToolProviders.result(locate(app, "fine".equals(a.optString("accuracy", "coarse")),
                Math.max(5, Math.min(50, a.optInt("timeout_s", 20))), Math.max(0, Math.min(3600, a.optInt("max_age_s", 60)))));
    }
}
