package dev.mateuszsury.androidagent;

import android.content.Intent;
import android.os.*;
import android.widget.*;
import static dev.mateuszsury.androidagent.Ui.*;

/** Installation is automatic; account authentication stays with the device owner. */
public final class RuntimeActivity extends Screen {
    private EmbeddedRuntime runtime;
    private TextView state,details;
    private ProgressBar progressBar;
    private Button prepare,cancel,terminal,files;
    private final Button[] installs=new Button[2],logins=new Button[2];
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){render();handler.postDelayed(this,750);}};
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);runtime=EmbeddedRuntime.get(this);
        LinearLayout root=column(this);root.setBackgroundColor(BG);root.setFitsSystemWindows(true);
        LinearLayout bar=row(this);ImageButton back=iconButton(this,R.drawable.ms_arrow_back,"Wróć");back.setOnClickListener(v->finish());bar.addView(back);bar.addView(title(this,"Środowisko i konta",22),weight1());root.addView(bar,full());
        ScrollView scroll=new ScrollView(this);LinearLayout body=column(this);body.setPadding(dp(this,22),dp(this,16),dp(this,22),dp(this,24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body.addView(title(this,"Narzędzia na Twoim telefonie",25));
        TextView intro=text(this,"Terminal, Python, Node.js, Git i most narzędzi są w tej aplikacji. Środowisko przygotowuje się automatycznie. Oficjalni klienci pobierają się osobno przez internet; logujesz się własnym kontem.",14,MUTED);intro.setPadding(0,dp(this,12),0,dp(this,20));body.addView(intro,full());
        this.state=title(this,"",18);body.addView(this.state,full());progressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progressBar.setMax(100);body.addView(progressBar,full());
        details=text(this,"",13,MUTED);details.setPadding(0,dp(this,8),0,dp(this,12));body.addView(details,full());
        prepare=button(this,"Przygotuj wybranych agentów",true);prepare.setOnClickListener(v->{try{runtime.prepare();}catch(Exception e){error(e);}render();});body.addView(prepare,full());
        cancel=button(this,"Anuluj przygotowanie",false);cancel.setOnClickListener(v->{runtime.cancelSetup();render();});body.addView(cancel,full());
        String[] backends={"codex","claude"},names={"Codex","Claude Code"};
        for(int i=0;i<backends.length;i++){
            final int index=i;String backend=backends[i];TextView title=title(this,names[i],21);title.setPadding(0,dp(this,22),0,dp(this,8));body.addView(title,full());
            body.addView(text(this,i==0?"Oficjalny klient OpenAI 0.162.1 · pobranie około 150–158 MB.":"Oficjalny klient Anthropic 2.1.296 · pobranie około 108 MB.",13,MUTED),full());
            installs[i]=button(this,"Zainstaluj "+names[i],false);installs[i].setOnClickListener(v->{try{runtime.installClient(backend);}catch(Exception e){error(e);}render();});body.addView(installs[i],full());
            logins[i]=button(this,"Zaloguj "+names[i],false);logins[i].setOnClickListener(v->{try{TerminalActivity.openLogin(this,backend);}catch(Exception e){error(e);}});body.addView(logins[i],full());
        }
        terminal=button(this,"Otwórz terminal",false);terminal.setOnClickListener(v->TerminalActivity.openShell(this));body.addView(terminal,full());
        files=button(this,"Pliki i projekty",false);files.setOnClickListener(v->startActivity(new Intent(this,RuntimeFilesActivity.class)));body.addView(files,full());
        Button permissions=button(this,"Uprawnienia telefonu",false);permissions.setOnClickListener(v->PermissionGuide.open(this));body.addView(permissions,full());
        Button log=button(this,"Dziennik przygotowania",false);log.setOnClickListener(v->{TextView text=monoText(this,runtime.setupLog(),11,TEXT);text.setTextIsSelectable(true);ScrollView container=new ScrollView(this);container.addView(text);new android.app.AlertDialog.Builder(this).setTitle("Przygotowanie środowiska").setView(container).setPositiveButton("Zamknij",null).show();});body.addView(log,full());
        Button sources=button(this,"Źródła i licencje środowiska",false);sources.setOnClickListener(v->showSources());body.addView(sources,full());
        body.addView(text(this,"Automatyczna konfiguracja nie loguje Cię do konta. Zgody systemowe udzielasz w ustawieniach Androida. Terminal działa z uprawnieniami androidagent; nie ma roota.",12,MUTED),full());setContentView(root);
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacksAndMessages(null);handler.post(refresh);if(!runtime.ready()&&!runtime.busy()&&runtime.error().isEmpty())runtime.bootstrap();else if(runtime.ready())withToolHost(()->runtime.refreshAccounts());}
    @Override protected void onPause(){handler.removeCallbacksAndMessages(null);super.onPause();}
    private void render(){
        state.setText(runtime.status());details.setText(runtime.error().isEmpty()?(runtime.busy()?"Możesz wrócić do rozmów. Przygotowanie trwa w tle.":"Terminal: "+(runtime.ready()?"gotowy":"przygotowanie wymagane")):runtime.error());details.setTextColor(runtime.error().isEmpty()?MUTED:ERR);
        progressBar.setProgress(runtime.progress());progressBar.setVisibility(runtime.busy()?android.view.View.VISIBLE:android.view.View.GONE);prepare.setEnabled(!runtime.busy());prepare.setText(runtime.error().isEmpty()?"Przygotuj wybranych agentów":"Ponów przygotowanie");cancel.setVisibility(runtime.busy()?android.view.View.VISIBLE:android.view.View.GONE);
        String[] backends={"codex","claude"};for(int i=0;i<2;i++){boolean installed=runtime.agentInstalled(backends[i]);installs[i].setEnabled(runtime.ready()&&!runtime.busy()&&!installed);installs[i].setText(installed?"Klient zainstalowany":"Zainstaluj "+(i==0?"Codex":"Claude Code"));logins[i].setEnabled(installed&&!runtime.busy());logins[i].setText(SetupState.agentReady(this,backends[i])?"Otwórz logowanie · konto połączone":"Zaloguj "+(i==0?"Codex":"Claude Code"));}terminal.setEnabled(runtime.ready());files.setEnabled(runtime.ready());
    }
    private void error(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}

    private void showSources() {
        try (java.io.InputStream input=getAssets().open("terminal/SOURCES.txt")) {
            java.io.ByteArrayOutputStream content=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[8192];int count;
            while ((count=input.read(buffer))!=-1) {
                if (content.size()+count>256*1024) throw new java.io.IOException("Dokument źródeł przekracza limit");
                content.write(buffer,0,count);
            }
            TextView text=text(this,new String(content.toByteArray(),java.nio.charset.StandardCharsets.UTF_8),13,TEXT);
            text.setPadding(dp(this,20),dp(this,16),dp(this,20),dp(this,16));
            text.setTextIsSelectable(true);
            android.text.util.Linkify.addLinks(text,android.text.util.Linkify.WEB_URLS);
            ScrollView scroll=new ScrollView(this);scroll.addView(text);
            new android.app.AlertDialog.Builder(this).setTitle("Źródła i licencje").setView(scroll)
                    .setPositiveButton("Zamknij",null).show();
        } catch (Exception exception) { error(exception); }
    }
}
