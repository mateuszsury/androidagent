package dev.mateuszsury.androidagent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;

import static dev.mateuszsury.androidagent.ToolProviders.prop;
import static dev.mateuszsury.androidagent.ToolProviders.tool;

/** service_* MCP tools: long-running owner-approved services that outlive the agent's turn. */
final class ServiceTools implements ToolProviders.Provider {
    private static final String DEV_MODE_OFF = "Developer mode is off: background services need it. Ask the owner to "
            + "enable developer mode in androidagent settings. (Włącz tryb programisty w ustawieniach androidagent.)";

    @Override public JSONArray list(Context app) throws JSONException {
        JSONObject name = prop("string", "Service name: 1–32 characters a-z, 0-9 and '-', e.g. \"web\".");
        JSONArray out = new JSONArray();
        out.put(tool("service_start",
                "Start a named long-running background service in the embedded Linux terminal: a web server, bot, "
                        + "file watcher or anything that must keep running after your turn ends. Never use '&', nohup, "
                        + "setsid or tmux for that: everything your turn starts is killed when the turn ends. The command "
                        + "must stay in the foreground (no '&', no daemon flags); stdout+stderr go to a rotating log "
                        + "(service_logs). Bind servers to 0.0.0.0 on a port >= 1024 so other devices on the same Wi-Fi "
                        + "can connect. Requires developer mode, and the owner approves the exact command in the chat "
                        + "(replacing a stopped service with a different command asks again). Returns the state, "
                        + "listening ports, the first log lines and URLs (http://127.0.0.1:PORT on this phone, "
                        + "http://<wifi-ip>:PORT on the local network): give the URLs to the user.",
                props("name", name,
                        "command", prop("string", "Exact shell command, run with bash -c in the foreground, e.g. "
                                + "\"python3 -m http.server 8080 --bind 0.0.0.0\"."),
                        "cwd", prop("string", "Working directory in the terminal (default ~/.buddy/work)."),
                        "port", prop("integer", "TCP port the service listens on (1024-65535). Recommended: it is "
                                + "checked, exported as $PORT and used for the URLs."),
                        "autostart", prop("boolean", "Start it again whenever androidagent starts (default false)."),
                        "restart", new JSONObject().put("type", "string").put("enum", new JSONArray().put("never").put("on-failure"))
                                .put("description", "Restart after a non-zero exit with backoff, at most 5 times in "
                                        + "10 minutes (default on-failure)."),
                        "env", new JSONObject().put("type", "object").put("additionalProperties", new JSONObject().put("type", "string"))
                                .put("description", "Extra environment variables (strings). Shown to the owner for approval.")),
                "name", "command"));
        out.put(tool("service_list",
                "List background services with state, pid, uptime, restart count, listening ports and URLs, plus the "
                        + "phone's local network addresses.", new JSONObject()));
        out.put(tool("service_status",
                "One service's state, pid, uptime, restarts, last exit code/error, listening ports, whether it is "
                        + "reachable from the local network and its URLs.", props("name", name), "name"));
        out.put(tool("service_logs",
                "Tail a service's combined stdout/stderr log (at most 64 KB). Use it to debug a service that failed, "
                        + "restarts or does not answer.",
                props("name", name, "lines", prop("integer", "Lines from the end (default 100, max 2000).")), "name"));
        out.put(tool("service_stop",
                "Stop a service: SIGTERM, then SIGKILL after 5 s. It stays registered (and is not autostarted until "
                        + "started again). Requires developer mode.", props("name", name), "name"));
        out.put(tool("service_restart",
                "Stop and start a registered service with its already approved command, e.g. after editing its "
                        + "code. Requires developer mode.", props("name", name), "name"));
        out.put(tool("service_remove",
                "Stop a service and delete its definition and logs. Requires developer mode.", props("name", name), "name"));
        return out;
    }

    @Override public boolean handles(String name) {
        return name.startsWith("service_");
    }

    @Override public JSONObject call(Context app, String caller, String name, JSONObject a) throws Exception {
        ServiceManager services = ServiceManager.get(app);
        switch (name) {
            case "service_list": {
                services.tick();
                JSONArray list = new JSONArray();
                for (ServiceManager.Status st : services.list()) list.put(st.json());
                return ToolProviders.result(network(app, services).put("services", list));
            }
            case "service_status": {
                String n = a.optString("name");
                ServiceManager.validateName(n);
                services.tick();
                return ToolProviders.result(require(services, n).json().put("log_tail", tail(services, n, 20)));
            }
            case "service_logs": {
                String n = a.optString("name");
                require(services, n);
                int lines = Math.max(1, Math.min(a.optInt("lines", 100), 2000));
                String log = services.logs(n, lines);
                return ToolProviders.text(log.isEmpty() ? "(empty log · dziennik jest pusty)" : log);
            }
            case "service_start":
                return start(app, caller, services, a);
            case "service_stop": {
                devMode(app);
                String n = a.optString("name");
                require(services, n);
                services.stop(n);
                return ToolProviders.result(require(services, n).json());
            }
            case "service_restart": {
                devMode(app);
                String n = a.optString("name");
                require(services, n);
                runtime(services);
                services.restart(n);
                return settled(services, n);
            }
            case "service_remove": {
                devMode(app);
                String n = a.optString("name");
                require(services, n);
                services.remove(n);
                return ToolProviders.result(new JSONObject().put("removed", n));
            }
            default:
                throw new IllegalArgumentException("Unknown tool " + name);
        }
    }

    private static JSONObject start(Context app, String caller, ServiceManager services, JSONObject a) throws Exception {
        devMode(app);
        ServiceManager.Definition d = new ServiceManager.Definition();
        d.name = a.optString("name");
        d.command = a.optString("command");
        d.cwd = a.optString("cwd", ServiceManager.DEFAULT_CWD);
        d.port = a.optInt("port", 0);
        d.autostart = a.optBoolean("autostart", false);
        d.restart = a.optString("restart", ServiceManager.RESTART_ON_FAILURE);
        JSONObject env = a.optJSONObject("env");
        if (env != null) for (Iterator<String> it = env.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = env.get(k);
            if (!(v instanceof String)) throw new IllegalArgumentException("env values must be strings (\"" + k + "\").");
            d.env.put(k, (String) v);
        }
        d.validate();
        ServiceManager.Status existing = services.status(d.name);
        if (existing != null && existing.active()) {
            if (existing.def.sameAs(d))
                return ToolProviders.result(existing.json().put("note", "Already running with this exact definition. "
                        + "Use service_restart to restart it."));
            throw new IllegalStateException("Service \"" + d.name + "\" is already running with a different definition. "
                    + "Call service_stop first, or choose another name. (Usługa „" + d.name + "” już działa.)");
        }
        runtime(services);
        if (existing == null || !existing.def.sameAs(d)) {
            if (!ApprovedCommands.get(app).approve(caller, d.approvalText(existing != null)))
                return ToolProviders.text("DENIED. The owner did not approve this service; nothing was started.");
            services.define(d, caller);
        }
        services.start(d.name);
        return settled(services, d.name);
    }

    /** Wait briefly so the agent sees an immediate crash or the open port, not just "starting". */
    private static JSONObject settled(ServiceManager services, String name) throws Exception {
        ServiceManager.Status st = services.awaitSettled(name, 8000);
        if (st == null) throw new IllegalStateException("Service \"" + name + "\" was removed meanwhile.");
        JSONObject out = st.json().put("log_tail", tail(services, name, 20));
        if (ServiceManager.FAILED.equals(st.state) && ServiceManager.NOT_PREPARED.equals(st.error))
            throw new IllegalStateException(notPrepared());
        String hint;
        if (ServiceManager.FAILED.equals(st.state) || ServiceManager.EXITED.equals(st.state))
            hint = "The service is not running (see error and log_tail). Fix the command or code, then call "
                    + "service_restart (same command) or service_start (changed command, asks the owner again).";
        else if (st.def.port != 0 && !st.portOpen)
            hint = "The process runs but port " + st.def.port + " is not listening yet. Check service_logs in a few "
                    + "seconds; make sure it binds 0.0.0.0:" + st.def.port + ".";
        else if (st.loopbackOnly)
            hint = "It listens only on 127.0.0.1: other devices cannot connect. Bind 0.0.0.0 to share it on the "
                    + "local network.";
        else if (!st.urls.isEmpty())
            hint = "Running. Give the user these URLs: " + urls(st)
                    + (st.urls.size() == 1 ? " (no Wi-Fi/LAN address right now: reachable only on this phone)." : ".");
        else hint = "Running. It keeps running after your turn; stop it with service_stop.";
        if (!services.foregroundError().isEmpty()) hint += " Note: " + services.foregroundError();
        return ToolProviders.result(out.put("hint", hint));
    }

    private static String urls(ServiceManager.Status st) {
        StringBuilder s = new StringBuilder();
        for (String[] u : st.urls) s.append(s.length() == 0 ? "" : ", ").append(u[0]).append(" (").append(u[1]).append(')');
        return s.toString();
    }

    private static String tail(ServiceManager services, String name, int lines) {
        try {
            return services.logs(name, lines);
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONObject network(Context app, ServiceManager services) throws JSONException {
        services.refreshNetwork();
        List<NetAddresses.Address> all = services.network();
        JSONArray lan = new JSONArray();
        for (NetAddresses.Address addr : NetAddresses.lan(all)) lan.put(addr.ip);
        return new JSONObject().put("wifi_connected", NetAddresses.onWifi(app)).put("lan_ipv4", lan)
                .put("ip_addresses", NetAddresses.json(all));
    }

    private static ServiceManager.Status require(ServiceManager services, String name) {
        ServiceManager.validateName(name);
        ServiceManager.Status st = services.status(name);
        if (st == null)
            throw new IllegalArgumentException("Unknown service \"" + name + "\". Use service_list to see names. "
                    + "(Nie ma usługi „" + name + "”.)");
        return st;
    }

    private static void devMode(Context app) {
        if (!Prefs.devMode(app)) throw new IllegalStateException(DEV_MODE_OFF);
    }

    private static void runtime(ServiceManager services) {
        if (!services.runtimeReady()) throw new IllegalStateException(notPrepared());
    }

    private static String notPrepared() {
        return ServiceManager.NOT_PREPARED + ": the embedded terminal runtime is not prepared, so services cannot "
                + "start. Ask the owner to prepare it in androidagent's environment panel. "
                + "(Przygotuj środowisko w panelu „Wbudowany terminal”.)";
    }

    private static JSONObject props(Object... pairs) throws JSONException {
        JSONObject p = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) p.put((String) pairs[i], pairs[i + 1]);
        return p;
    }
}
