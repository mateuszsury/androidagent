package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;
import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Foreground grants, native UART/ADB/ESP and document browser. Docker is optional. */
public class UsbActivity extends Screen {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ThreadPoolExecutor operations = new java.util.concurrent.ThreadPoolExecutor(
        2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(8), runnable -> new Thread(runnable, "usb-operation"));
    private LinearLayout devices,sessions,roots;private TextView state;private String signature="",artifactSession="";private String artifactAction="import";
    private final Runnable refresh=new Runnable(){public void run(){render();handler.postDelayed(this,1500);}};
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);if(saved!=null){artifactSession=saved.getString("artifactSession","");artifactAction=saved.getString("artifactAction","import");}
        LinearLayout root=column(this);root.setBackgroundColor(BG);root.setFitsSystemWindows(true);LinearLayout bar=row(this);ImageButton back=iconButton(this,R.drawable.ms_arrow_back,"Wróć");back.setOnClickListener(v->finish());bar.addView(back);bar.addView(title(this,"Urządzenia USB",22),weight1());root.addView(bar,full());ScrollView scroll=new ScrollView(this);LinearLayout body=column(this);body.setPadding(dp(this,20),dp(this,16),dp(this,20),dp(this,24));
        body.addView(text(this,"UART, ESP, drugi Android i pliki z nośników. Wybierz urządzenie i przydziel dostęp rozmowie. Docker jest opcjonalny.",14,MUTED),full());state=text(this,"",13,MUTED);state.setPadding(0,dp(this,16),0,dp(this,12));body.addView(state,full());
        Button enable=button(this,"Włącz dostęp agentów",true);enable.setOnClickListener(v->{try{McpHost.get(this).requireReady();McpHost.get(this).allowControl();startForegroundService(new Intent(this,AgentService.class));offerNotifications();signature="";}catch(Exception e){error(e);}});body.addView(enable,full());
        Button stop=button(this,"Stop · odbierz cały dostęp",false);stop.setOnClickListener(v->{McpHost.get(this).emergencyStop();stopService(new Intent(this,AgentService.class));signature="";render();});body.addView(stop,spaced());
        body.addView(section("Podłączone urządzenia"));devices=column(this);body.addView(devices,full());body.addView(section("Otwarte połączenia"));sessions=column(this);body.addView(sessions,full());
        body.addView(section("Katalogi i nośniki"));Button select=button(this,"+ Wybierz katalog",false);select.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),710));body.addView(select,full());roots=column(this);body.addView(roots,full());
        body.addView(text(this,"Przy otwarciu adaptera linie sterujące mogą zmienić stan. Flashowanie wymaga wskazania układu i firmware'u. Android na drugim telefonie musi zaakceptować klucz ADB. Nośniki otwieramy przez systemowy wybór katalogu.",12,MUTED),full());
        Button licenses=button(this,"Licencje USB",false);licenses.setOnClickListener(v->new Thread(()->{try{StringBuilder notices=new StringBuilder("USB: przypięte źródła i licencje\n\n");for(String name:new String[]{"sources.json","usb-serial-MIT.txt","esp-flasher-Apache-2.0.txt"})try(InputStream in=getAssets().open("usb/"+name)){notices.append(new String(UsbArtifacts.read(in,65536),java.nio.charset.StandardCharsets.UTF_8)).append("\n\n");}notices.append("Licencje libusb/usbredir: panel Docker → Licencje i źródła.");runOnUiThread(()->message("Licencje USB",notices.toString()));}catch(Exception e){runOnUiThread(()->error(e));}},"usb-licenses").start());body.addView(licenses,spaced());
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    private TextView section(String label) {
        TextView heading = title(this, label, 20);
        heading.setPadding(0, dp(this, 20), 0, dp(this, 8));
        heading.setAccessibilityHeading(true);
        return heading;
    }
    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(this, 8);
        return params;
    }
    private volatile boolean rendering;
    private boolean resumed;
    private void render(){if(!resumed||isDestroyed())return;updateState();if(rendering)return;rendering=true;new Thread(()->{try{UsbSessions hub=UsbSessions.get(this);JSONArray d=hub.list(),s=hub.sessions(null),r=UsbStorage.get(this).roots("panel");runOnUiThread(()->{rendering=false;if(resumed&&!isDestroyed())draw(d,s,r);});}catch(Exception e){runOnUiThread(()->{rendering=false;if(!isDestroyed())state.setText("USB: "+e.getMessage());});}},"usb-refresh").start();}
    private void updateState() {
        McpHost host = McpHost.get(this);
        state.setText(host.stopping() ? "Zatrzymywanie operacji i odbieranie dostępu…"
            : host.controlRevoked() != null ? "Dostęp agentów zatrzymany"
            : host.running() ? "Dostęp agentów aktywny · Codex i Claude Code" : "Dostęp agentów wyłączony");
    }
    private void draw(JSONArray d,JSONArray s,JSONArray r){try{UsbSessions hub=UsbSessions.get(this);updateState();if(s.length()>0)offerNotifications();String sig=d.toString()+s.toString()+r.toString();if(sig.equals(signature))return;signature=sig;devices.removeAllViews();sessions.removeAllViews();roots.removeAllViews();
        if(d.length()==0)devices.addView(text(this,"Brak urządzeń USB. Podłącz urządzenie kablem OTG.",14,MUTED));
        for(int i=0;i<d.length();i++){JSONObject o=d.getJSONObject(i);Button b=button(this,o.optString("name","Urządzenie USB")+String.format(Locale.US," · %04x:%04x",o.getInt("vid"),o.getInt("pid"))+(o.optBoolean("permission")?"":" · wymaga zgody"),false);b.setOnClickListener(v->{if(!o.optBoolean("permission")){background(()->{hub.permission(this,o.optString("id"));return null;});return;}new AlertDialog.Builder(this).setTitle("Wybierz profil").setItems(new String[]{"Terminal UART / ESP","Drugi Android · ADB","USB do Dockera"},(dialog,which)->{String p=new String[]{"serial","adb","docker"}[which];if(p.equals("serial"))form("Port adaptera",new String[]{"Numer portu (od 0)"},new String[]{"0"},values->open(o.optString("id"),p,Integer.parseInt(values[0])));else open(o.optString("id"),p,0);}).show();});devices.addView(b,spaced());}
        if(s.length()==0)sessions.addView(text(this,"Nie otwarto żadnego połączenia.",14,MUTED));for(int i=0;i<s.length();i++){JSONObject o=s.getJSONObject(i);Button b=button(this,o.optString("profile")+" · "+ownerLabel(o.optString("owner"))+" · RX "+o.optLong("rx")+" / TX "+o.optLong("tx"),false);b.setOnClickListener(v->sessionMenu(o));sessions.addView(b,spaced());}
        if(r.length()==0)roots.addView(text(this,"Wybierz katalog pendrive'a lub dysku przez Androida.",14,MUTED));for(int i=0;i<r.length();i++){JSONObject o=r.getJSONObject(i);Button b=button(this,o.optString("name")+" · "+(o.optBoolean("write")?"zapis":"odczyt")+" · "+ownerLabel(o.optString("owner")),false);b.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(o.optString("name")).setItems(new String[]{"Przeglądaj pliki","Przydziel rozmowie","Odbierz dostęp rozmowie","Usuń katalog z aplikacji"},(x,which)->{if(which==0)browse(o.optString("document"));else if(which==1)chooseOwner(owner->new AlertDialog.Builder(this).setTitle("Zakres dostępu").setItems(new String[]{"Tylko odczyt","Odczyt i zapis"},(z,w)->{background(()->{UsbStorage.get(this).assign(o.optString("root"),owner,w==1);return null;});}).show());else if(which==2){background(()->{UsbStorage.get(this).revokeOwner(o.optString("owner"));return null;});}else{background(()->{UsbStorage.get(this).remove(o.optString("root"));return null;});}}).show());roots.addView(b,spaced());}
    }catch(Exception e){state.setText("USB: "+e.getMessage());}}
    private String ownerLabel(String owner){if(owner.equals("panel"))return "panel";for(Sessions.Session s:ChatHub.get(this).sessions.all)if((owner.equals("chat:"+s.id)||owner.equals("pending:"+s.id)))return s.title+(owner.startsWith("pending:")?" · następne zadanie":"");return "rozmowa";}
    private void open(String d,String p,int port){background(()->UsbSessions.get(this).open(d,p,"panel",port).json());}
    private void sessionMenu(JSONObject o){String id=o.optString("id"),owner=o.optString("owner"),profile=o.optString("profile");List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        if(profile.equals("serial")){labels.add("Terminal i logi");actions.add(()->terminal(id,owner));}
        if(owner.equals("panel")){
            labels.add("Przydziel rozmowie");actions.add(()->chooseOwner(value->{background(()->{UsbSessions.get(this).assign(id,value);return null;});}));
            if(profile.equals("serial")){labels.add("Parametry UART");actions.add(()->form("Parametry UART",new String[]{"Baud rate","Data bits","Stop bits (1 / 2 / 3 = 1.5)","Parity (0–4)"},new String[]{"115200","8","1","0"},v->call("usb_serial_configure",new JSONObject().put("session",id).put("baud",Integer.parseInt(v[0])).put("bits",Integer.parseInt(v[1])).put("stop",Integer.parseInt(v[2])).put("parity",Integer.parseInt(v[3])))));
                labels.add("Linie DTR / RTS / break");actions.add(()->new AlertDialog.Builder(this).setTitle("Linie sterujące").setMultiChoiceItems(new String[]{"DTR","RTS","Break"},new boolean[3],new android.content.DialogInterface.OnMultiChoiceClickListener(){final boolean[] checked=new boolean[3];public void onClick(DialogInterface dialog,int which,boolean value){checked[which]=value;try{call("usb_serial_control",new JSONObject().put("session",id).put("dtr",checked[0]).put("rts",checked[1]).put("break",checked[2]));}catch(Exception e){error(e);}}}).setPositiveButton("Zamknij",null).show());
                labels.add("Zapisz log");actions.add(()->simple("usb_serial_save",id));labels.add("Identyfikuj ESP");actions.add(()->espReset(id,"esp_info",null));labels.add("Wgraj firmware ESP");actions.add(()->pickArtifact(id,"flash"));
            }
            if(profile.equals("adb")){labels.add("Połącz ADB / zaakceptuj klucz");actions.add(()->{try{call("adb_status",new JSONObject().put("session",id).put("connect",true));}catch(Exception e){error(e);}});labels.add("Komenda na drugim telefonie");actions.add(()->form("Shell · drugi Android",new String[]{"Komenda"},new String[]{"getprop ro.product.model"},v->call("adb_exec",new JSONObject().put("session",id).put("command",v[0]))));labels.add("Logcat");actions.add(()->simple("adb_logcat",id));labels.add("Pobierz plik");actions.add(()->form("Pobierz plik",new String[]{"Ścieżka na drugim Androidzie"},new String[]{"/sdcard/Download/example.txt"},v->call("adb_pull",new JSONObject().put("session",id).put("path",v[0]))));labels.add("Wyślij plik");actions.add(()->pickArtifact(id,"push"));labels.add("Zainstaluj APK");actions.add(()->pickArtifact(id,"install"));}
            if(profile.equals("serial")||profile.equals("docker")){labels.add("Przydziel zadaniu Docker");actions.add(()->chooseTask(task->{try{call(profile.equals("serial")?"usb_docker_serial":"usb_docker_attach",new JSONObject().put("session",id).put("task",task));}catch(Exception e){error(e);}}));}
        }
        labels.add("Zamknij i odbierz dostęp");actions.add(()->{background(()->{UsbSessions.get(this).close(owner,id);return null;});});
        new AlertDialog.Builder(this).setTitle("Połączenie "+profile).setItems(labels.toArray(new String[0]),(d,which)->actions.get(which).run()).show();
    }
    interface Choice{void accept(String value);}interface Values{void accept(String[] values)throws Exception;}interface Work{JSONObject run()throws Exception;}
    private void chooseOwner(Choice choice){List<String> names=new ArrayList<>(),ids=new ArrayList<>();for(Sessions.Session s:ChatHub.get(this).sessions.all){names.add(s.title+" · "+s.backend+(s.running?" · aktywne":" · następne zadanie"));ids.add((s.running?"chat:":"pending:")+s.id);}if(ids.isEmpty()){message("Przydział","Najpierw utwórz rozmowę. Możesz przydzielić urządzenie aktywnej odpowiedzi albo jednorazowo następnemu zadaniu.");return;}new AlertDialog.Builder(this).setTitle("Przydziel rozmowie").setItems(names.toArray(new String[0]),(d,w)->choice.accept(ids.get(w))).show();}
    private void chooseTask(Choice choice){JSONArray tasks=DockerVm.get(this).guest.optJSONArray("tasks");if(tasks==null||tasks.length()==0){message("Docker","Uruchom środowisko i utwórz zadanie w panelu Docker.");return;}String[] names=new String[tasks.length()];for(int i=0;i<names.length;i++)names[i]=tasks.optJSONObject(i).optString("name");new AlertDialog.Builder(this).setTitle("Zadanie Docker").setItems(names,(d,w)->choice.accept(tasks.optJSONObject(w).optString("id"))).show();}
    private void form(String title,String[] labels,String[] defaults,Values done){LinearLayout content=column(this);content.setPadding(dp(this,20),0,dp(this,20),0);EditText[] input=new EditText[labels.length];for(int i=0;i<labels.length;i++){content.addView(text(this,labels[i],13,MUTED));input[i]=new EditText(this);input[i].setTextColor(TEXT);input[i].setSingleLine();input[i].setText(defaults[i]);content.addView(input[i],full());}ScrollView fields=new ScrollView(this);fields.setFillViewport(true);fields.addView(content);AlertDialog d=new AlertDialog.Builder(this).setTitle(title).setView(fields).setNegativeButton("Anuluj",null).setPositiveButton("Wykonaj",null).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(button->{String[] values=new String[input.length];for(int i=0;i<values.length;i++)values[i]=input[i].getText().toString();try{done.accept(values);d.dismiss();}catch(Exception e){error(e);}}));d.show();}
    private void terminal(String id,String owner){background(()->{SerialTransport port=UsbSessions.get(this).owned(owner,id).serial;runOnUiThread(()->terminalView(id,owner,port));return null;});}
    AlertDialog terminalView(String id,String owner,SerialTransport port){if(isDestroyed()||isFinishing())return null;LinearLayout content=column(this);TextView log=monoText(this,"",12,CODE_TEXT);log.setTextIsSelectable(true);log.setBackgroundColor(CODE);log.setPadding(dp(this,12),dp(this,12),dp(this,12),dp(this,12));ScrollView scroll=new ScrollView(this);scroll.addView(log);content.addView(scroll,new LinearLayout.LayoutParams(-1,dp(this,260)));EditText command=new EditText(this);command.setTextColor(TEXT);command.setHint("Tekst lub hex");content.addView(command,full());Spinner format=new Spinner(this);format.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Tekst + LF","Tekst + CRLF","Tekst bez końca linii","Hex"}));content.addView(format);Button send=button(this,"Wyślij",true);send.setEnabled(owner.equals("panel"));send.setOnClickListener(v->{try{int f=format.getSelectedItemPosition();String value=command.getText().toString()+(f==0?"\n":f==1?"\r\n":"");call("usb_serial_write",new JSONObject().put("session",id).put("data",value).put("encoding",f==3?"hex":"utf8"));command.setText("");}catch(Exception e){error(e);}});content.addView(send);ScrollView terminalScroll=new ScrollView(this);terminalScroll.setFillViewport(true);terminalScroll.addView(content);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Terminal UART").setView(terminalScroll).setPositiveButton("Zamknij",null).create();Runnable poll=new Runnable(){long end=-1;public void run(){if(!dialog.isShowing()||isDestroyed())return;try{send.setEnabled(owner.equals("panel")&&!port.closed&&!port.exclusive&&!port.bridged);long latest=port.received.end();if(latest!=end){UsbBuffer.Slice s=port.received.read(Math.max(0,latest-65536),65536);log.setText(UsbTools.encode(s.bytes,"utf8"));end=latest;}}catch(Exception e){log.setText(e.getMessage());}if(!port.closed)handler.postDelayed(this,500);}};dialog.setOnShowListener(v->handler.post(poll));dialog.setOnDismissListener(v->handler.removeCallbacks(poll));dialog.show();return dialog;}
    private void browse(String document) { browse(document, 0); }
    private void browse(String document, int offset) {
        background(() -> {
            JSONArray entries = UsbStorage.get(this).list("panel", document, offset, 200);
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                List<String> names = new ArrayList<>();
                List<Runnable> actions = new ArrayList<>();
                if (offset > 0) {
                    names.add("← Poprzednie pliki");
                    actions.add(() -> browse(document, Math.max(0, offset - 200)));
                }
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    names.add(entry.optString("name"));
                    actions.add(() -> documentMenu(entry));
                }
                if (entries.length() == 200) {
                    names.add("Następne pliki →");
                    actions.add(() -> browse(document, offset + 200));
                }
                names.add("+ Nowy plik");
                actions.add(() -> form("Nowy plik", new String[]{"Nazwa"}, new String[]{"plik.txt"},
                    v -> call("usb_storage_create", new JSONObject().put("parent", document)
                        .put("name", v[0]).put("mime", "text/plain"))));
                new AlertDialog.Builder(this).setTitle("Pliki · strona " + (offset / 200 + 1))
                    .setItems(names.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                    .setNegativeButton("Zamknij", null).show();
            });
            return null;
        });
    }
    private void documentMenu(JSONObject entry) {
        String id = entry.optString("document");
        if (entry.optString("mime").equals(android.provider.DocumentsContract.Document.MIME_TYPE_DIR)) {
            browse(id);
            return;
        }
        new AlertDialog.Builder(this).setTitle(entry.optString("name"))
            .setItems(new String[]{"Odczytaj tekst", "Importuj plik", "Nadpisz tekst", "Usuń plik"}, (dialog, action) -> {
                try {
                    if (action < 2) call(action == 0 ? "usb_storage_read" : "usb_storage_import",
                        new JSONObject().put("document", id));
                    else if (action == 2) form("Nadpisz plik", new String[]{"Tekst"}, new String[]{""},
                        v -> call("usb_storage_write", new JSONObject().put("document", id).put("data", v[0])));
                    else confirmSheet(this, "Usunąć plik?", entry.optString("name"), "Usuń", true, () -> {
                        try { call("usb_storage_delete", new JSONObject().put("document", id)); }
                        catch (Exception e) { error(e); }
                    });
                } catch (Exception e) { error(e); }
            }).show();
    }
    private void pickArtifact(String session,String action){artifactSession=session;artifactAction=action;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(action.equals("install")?"application/vnd.android.package-archive":"application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),711);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==710){background(()->{UsbStorage.get(this).add(uri,data.getFlags());return null;});}else if(request==711){final String session=artifactSession,action=artifactAction;new Thread(()->{String name="firmware.bin";try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst()&&cursor.getString(0)!=null)name=cursor.getString(0);}catch(Exception ignored){}final String filename=name;try(InputStream in=getContentResolver().openInputStream(uri)){JSONObject artifact=UsbArtifacts.save(this,in,filename,"panel",null,512L*1024*1024);runOnUiThread(()->{if(isDestroyed())return;if(action.equals("flash"))espReset(session,"esp_flash",artifact);else if(action.equals("install")){try{call("adb_install",new JSONObject().put("session",session).put("artifact",artifact.getString("id")));}catch(Exception e){error(e);}}else if(action.equals("push"))form("Wyślij plik",new String[]{"Ścieżka na drugim Androidzie"},new String[]{"/sdcard/Download/"+filename},v->call("adb_push",new JSONObject().put("session",session).put("artifact",artifact.getString("id")).put("path",v[0])));});}catch(Exception e){runOnUiThread(()->error(e));}},"usb-import").start();}}
    private void espReset(String id,String operation,JSONObject artifact){new AlertDialog.Builder(this).setTitle("Bootloader ESP").setItems(new String[]{"Ręczny BOOT / RESET","Automatyczny reset przez DTR / RTS"},(d,w)->{try{if(artifact==null)call(operation,new JSONObject().put("session",id).put("auto_reset",w==1));else form("Mapa firmware'u · "+artifact.optString("sha256"),new String[]{"Numer układu z identyfikacji ESP","Offset (np. 0x1000)"},new String[]{"0","0x1000"},v->call(operation,new JSONObject().put("session",id).put("auto_reset",w==1).put("expected_chip",Integer.parseInt(v[0])).put("segments",new JSONArray().put(new JSONObject().put("artifact",artifact.getString("id")).put("offset",Long.decode(v[1]))))));}catch(Exception e){error(e);}}).show();}
    private void simple(String n,String session){try{call(n,new JSONObject().put("session",session));}catch(Exception e){error(e);}}
    private void call(String n,JSONObject a){background(()->{JSONObject r=UsbTools.call(this,"panel",n,a).getJSONObject("structuredContent");if(r.has("kind")&&r.has("id")){runOnUiThread(()->watchJob(r.optString("id")));return null;}return r;});}
    private void watchJob(String id){if(isDestroyed()||isFinishing())return;TextView status=monoText(this,"Oczekiwanie…",12,TEXT);status.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(status);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Operacja USB").setView(scroll).setNegativeButton("Anuluj operację",(d,w)->{try{UsbJobs.get(this).status("panel",id,true);}catch(Exception e){error(e);}}).setPositiveButton("Zamknij",null).create();Runnable poll=new Runnable(){public void run(){if(!dialog.isShowing())return;try{JSONObject j=UsbJobs.get(UsbActivity.this).status("panel",id,false);status.setText(j.toString(2));if(j.optString("state").equals("queued")||j.optString("state").equals("running"))handler.postDelayed(this,500);}catch(Exception e){status.setText(e.getMessage());}}};dialog.setOnShowListener(v->handler.post(poll));dialog.setOnDismissListener(v->handler.removeCallbacks(poll));dialog.show();}
    private void background(Work work) {
        try {
            operations.execute(() -> {
                try {
                    JSONObject value = work.run();
                    runOnUiThread(() -> {
                        signature = "";
                        if (!isDestroyed()) {
                            render();
                            if (value != null) message("Wynik", value.toString());
                        }
                    });
                } catch (Exception e) { runOnUiThread(() -> error(e)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            message("USB", "Trwa zbyt wiele operacji. Poczekaj na ich zakończenie.");
        }
    }
    private void message(String title,String body){if(isDestroyed()||isFinishing())return;TextView value=monoText(this,body,12,TEXT);value.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(value);new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Zamknij",null).show();}
    private void error(Exception e){message("USB",String.valueOf(e.getMessage()));}
    @Override protected void onResume(){super.onResume();resumed=true;handler.post(refresh);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);operations.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("artifactSession",artifactSession);out.putString("artifactAction",artifactAction);super.onSaveInstanceState(out);}
}
