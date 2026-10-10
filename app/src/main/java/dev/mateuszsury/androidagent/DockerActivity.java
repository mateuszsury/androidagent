package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import org.json.*;

/** Native dashboard. Works without model sign-in or Termux. */
public class DockerActivity extends Screen {
    private final Handler poll = new Handler(Looper.getMainLooper());
    private TextView status, detail;
    private Button start;
    private Spinner ram;
    private LinearLayout tasks, exports;
    private String lastTasks = "", lastExports = "";
    private DockerVm vm;
    private final Runnable refresh = new Runnable() {
        public void run() {
            render(); poll.postDelayed(this, 1500);
        }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); vm = DockerVm.get(this);
        LinearLayout root = column(this); root.setBackgroundColor(BG); root.setFitsSystemWindows(true);
        LinearLayout bar = row(this);
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, "Wróć"); back.setOnClickListener(v -> finish());
        bar.addView(back); bar.addView(title(this, "Docker", 22), weight1()); root.addView(bar, full());
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column(this); body.setPadding(dp(this,22),dp(this,16),dp(this,22),dp(this,24));
        body.addView(title(this, "Miejsce na Twoje pomysły", 25));
        TextView intro = text(this, "Buduj i uruchamiaj aplikacje w osobnych środowiskach. Zachowaj wyniki i posprzątaj po zadaniu. Bez roota.", 14, MUTED);
        intro.setPadding(0,dp(this,12),0,dp(this,22)); body.addView(intro,full());
        status = title(this,"",19); body.addView(status);
        detail = text(this,"",13,MUTED); detail.setPadding(0,dp(this,6),0,dp(this,14)); body.addView(detail,full());
        ram = new Spinner(this); ram.setContentDescription("Pamięć środowiska Docker");
        ram.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"512 MB RAM","1 GB RAM","2 GB RAM"}){
            private android.view.View label(int position,boolean dropdown){TextView v=text(DockerActivity.this,getItem(position)+(dropdown?"":"   ▾"),14,TEXT);v.setBackgroundColor(CARD);v.setPadding(dp(DockerActivity.this,12),dp(DockerActivity.this,12),dp(DockerActivity.this,12),dp(DockerActivity.this,12));return v;}
            @Override public android.view.View getView(int position,android.view.View recycled,android.view.ViewGroup parent){return label(position,false);}
            @Override public android.view.View getDropDownView(int position,android.view.View recycled,android.view.ViewGroup parent){return label(position,true);}
        });
        ram.setBackground(rounded(this,CARD,12));
        ram.setSelection(state==null?1:state.getInt("ram",1)); body.addView(ram,full());
        start = button(this,"Uruchom środowisko",true);
        start.setOnClickListener(v -> {
            if (vm.alive() || "preparing".equals(vm.state) || "booting".equals(vm.state)) {
                startService(new Intent(this,DockerService.class).setAction("stop"));
            } else {
                int[] sizes={512,1024,2048};
                startForegroundService(new Intent(this,DockerService.class).putExtra("ram",sizes[ram.getSelectedItemPosition()]).putExtra("cores",1));
            }
        });
        body.addView(start,full());
        Button log=button(this,"Dziennik środowiska",false);
        log.setOnClickListener(v -> {
            TextView view=monoText(this,vm.console().isEmpty()?"Środowisko jeszcze nie pracuje.":vm.console(),11,CODE_TEXT);
            view.setTextIsSelectable(true); ScrollView s=new ScrollView(this);s.addView(view);
            new android.app.AlertDialog.Builder(this).setTitle("Dziennik Linuxa").setView(s).setPositiveButton("Zamknij",null).show();
        }); body.addView(log,full());
        TextView taskTitle=title(this,"Zadania",22);taskTitle.setPadding(0,dp(this,24),0,dp(this,12));body.addView(taskTitle);
        Button create=button(this,"+ Nowe zadanie",false);create.setOnClickListener(v -> create(false));body.addView(create,full());
        Button demo=button(this,"Wypróbuj przykładową aplikację",false);demo.setOnClickListener(v -> create(true));body.addView(demo,full());
        tasks=column(this);body.addView(tasks,full());
        TextView exportTitle=title(this,"Zachowane pliki",22);exportTitle.setPadding(0,dp(this,24),0,dp(this,12));body.addView(exportTitle);
        exports=column(this);body.addView(exports,full());
        Button reset=button(this,"Usuń wszystkie zadania i cache",false);
        reset.setOnClickListener(v -> confirmSheet(this,"Zresetować środowisko?","Usuniesz dysk roboczy, zadania i obrazy Dockera. Zachowane pliki pozostaną dostępne.","Usuń dysk",true,()->{
            try {vm.reset();lastTasks="";render();}catch(Exception e){error(e);}
        }));body.addView(reset,full());
        TextView limits=text(this,"Środowisko korzysta z pamięci i baterii telefonu. Pierwsze uruchomienie wymaga rozpakowania Linuxa; duże projekty potrzebują dodatkowego miejsca.",12,MUTED);
        limits.setPadding(0,dp(this,16),0,0);body.addView(limits,full());
        Button licenses=button(this,"Licencje i źródła",false);licenses.setOnClickListener(v->showLicenses());body.addView(licenses,full());
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    private void create(boolean demo) {
        new Thread(()->{
            try {
                JSONObject task=vm.request(new JSONObject().put("op","create").put("name",demo?"Moja aplikacja WWW":"Nowe zadanie"));
                String id=task.getString("id");
                if(demo) {
                    vm.request(new JSONObject().put("op","write").put("task",id).put("path","Dockerfile").put("content",
                            demoAsset("Dockerfile")));
                    vm.request(new JSONObject().put("op","write").put("task",id).put("path","serve.sh").put("content",demoAsset("serve.sh")));
                    vm.request(new JSONObject().put("op","write").put("task",id).put("path","index.html").put("content",
                            "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>Moja aplikacja</title><style>body{font:20px system-ui;background:#f7f7f5;color:#202020;max-width:600px;margin:15vh auto;padding:24px}button{background:#202020;color:white;border:0;border-radius:24px;padding:14px 24px;font:inherit}</style><h1>Zbudowano na telefonie.</h1><p>Ta aplikacja działa w Dockerze w androidagent.</p><button onclick='this.textContent=\"Działa!\"'>Sprawdź</button>"));
                }
                runOnUiThread(()->{if(!isDestroyed()) startActivity(new Intent(this,DockerTaskActivity.class).putExtra("task",id).putExtra("demo",demo));});
            } catch(Exception e){runOnUiThread(()->error(e));}
        },"docker-create").start();
    }
    private String demoAsset(String name) throws java.io.IOException {
        try(java.io.InputStream in=getAssets().open("docker/demo/"+name);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            return out.toString("UTF-8");
        }
    }
    private void render() {
        status.setText("ready".equals(vm.state)?"Środowisko działa":"stopped".equals(vm.state)?"Środowisko zatrzymane":"error".equals(vm.state)?"Nie udało się uruchomić":"Przygotowywanie środowiska…");
        String information=vm.detail;
        if("ready".equals(vm.state))information="Docker "+vm.guest.optString("engine_version")+" · "+vm.guest.optString("architecture")
                +"\nWolne miejsce: "+String.format(java.util.Locale.US,"%.1f GB w środowisku · %.1f GB na telefonie",vm.guest.optLong("disk_free_bytes")/1073741824.0,vm.directory.getUsableSpace()/1073741824.0);
        detail.setText(information);start.setText(vm.alive()||"preparing".equals(vm.state)||"booting".equals(vm.state)?"Zatrzymaj środowisko":"Uruchom środowisko");
        JSONArray list=vm.guest.optJSONArray("tasks");String value=list==null?"[]":list.toString();
        if(!lastTasks.equals(value)) {
            lastTasks=value;tasks.removeAllViews();
            if(list==null||list.length()==0)tasks.addView(text(this,"Tu pojawią się Twoje zadania.",13,MUTED));
            else for(int i=0;i<list.length();i++){
                JSONObject task=list.optJSONObject(i);if(task==null)continue;
                Button b=button(this,task.optString("name","Zadanie"),false);
                b.setOnClickListener(v->startActivity(new Intent(this,DockerTaskActivity.class).putExtra("task",task.optString("id"))));tasks.addView(b,full());
            }
        }
        JSONArray saved=vm.exports();String files=saved.toString();
        if(!files.equals(lastExports)){lastExports=files;exports.removeAllViews();
            if(saved.length()==0)exports.addView(text(this,"Wyeksportuj plik przed usunięciem zadania.",13,MUTED));
            for(int i=0;i<saved.length();i++){JSONObject file=saved.optJSONObject(i);if(file==null)continue;
                Button b=button(this,file.optString("name"),false);b.setOnClickListener(v->openExport(file));exports.addView(b,full());}
        }
    }
    void openExport(JSONObject file) {
        Intent view=new Intent(Intent.ACTION_VIEW,Uri.parse(file.optString("uri"))).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String name=file.optString("name");
        view.setDataAndType(Uri.parse(file.optString("uri")),name.endsWith(".apk")?"application/vnd.android.package-archive":name.endsWith(".html")?"text/html":"application/octet-stream");
        try{startActivity(view);}catch(Exception e){
            Intent share=new Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM,Uri.parse(file.optString("uri"))).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try{startActivity(Intent.createChooser(share,"Zapisz lub udostępnij plik"));}catch(Exception x){error(x);}
        }
    }
    private void showLicenses() {
        new Thread(()->{
            try(java.io.InputStream in=getAssets().open("docker/SOURCES.txt");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                final String content=new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
                runOnUiThread(()->{if(isDestroyed())return;TextView text=text(this,content,12,TEXT);text.setTextIsSelectable(true);android.text.util.Linkify.addLinks(text,android.text.util.Linkify.WEB_URLS);ScrollView view=new ScrollView(this);view.addView(text);new android.app.AlertDialog.Builder(this).setTitle("Licencje środowiska").setView(view).setPositiveButton("Zamknij",null).show();});
            }catch(Exception e){runOnUiThread(()->error(e));}
        },"docker-licenses").start();
    }
    private void error(Exception e){if(!isDestroyed())new android.app.AlertDialog.Builder(this).setTitle("Docker").setMessage(e.getMessage()).setPositiveButton("OK",null).show();}
    @Override protected void onResume(){super.onResume();poll.post(refresh);}
    @Override protected void onPause(){poll.removeCallbacks(refresh);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("ram",ram.getSelectedItemPosition());super.onSaveInstanceState(out);}
}
