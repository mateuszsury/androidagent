package dev.mateuszsury.androidagent;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.text.util.Linkify;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.Process;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.mateuszsury.androidagent.Ui.*;

/** Streaming command terminal with stdin, including the clients' official device login. */
public final class TerminalActivity extends Screen {
    private static final Map<String,Session> sessions=new HashMap<>();
    static void stopAll(){synchronized(sessions){for(Session session:sessions.values())session.stop();}}
    private Session session;private TextView output;private EditText input;private Button send,stop;private ScrollView scroll;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){render();handler.postDelayed(this,350);}};
    static void openShell(Context c){open(c,"shell",null,null);}
    static void openLogin(Context c,String backend){open(c,"login",backend,null);}
    static void openHelper(Context c,String...args){open(c,"helper",null,args);}
    private static void open(Context c,String mode,String backend,String[]args){Intent i=new Intent(c,TerminalActivity.class).putExtra("mode",mode);if(backend!=null)i.putExtra("backend",backend);if(args!=null)i.putExtra("args",args);if(!(c instanceof android.app.Activity))i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(i);}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);String id=state==null?getIntent().getStringExtra("terminal_session"):state.getString("terminal_session");
        synchronized(sessions){session=id==null?null:sessions.get(id);if(session==null){session=new Session(getApplicationContext(),getIntent());sessions.put(session.id,session);getIntent().putExtra("terminal_session",session.id);}}
        LinearLayout root=column(this);root.setBackgroundColor(BG);root.setFitsSystemWindows(true);LinearLayout bar=row(this);ImageButton back=iconButton(this,R.drawable.ms_arrow_back,"Wróć");back.setOnClickListener(v->finish());bar.addView(back);bar.addView(title(this,"Wbudowany terminal",21),weight1());root.addView(bar,full());
        String instructions="login".equals(getIntent().getStringExtra("mode"))?"Zaloguj się na oficjalnej stronie otwartej z linku. Jeśli klient poprosi o kod, wpisz go poniżej.":"Wpisz komendę lub odpowiedź dla klienta. Stop kończy bieżącą sesję terminala.";
        TextView intro=text(this,instructions,12,MUTED);intro.setPadding(dp(this,16),dp(this,8),dp(this,16),dp(this,8));root.addView(intro,full());
        scroll=new ScrollView(this);scroll.setBackgroundColor(CODE);output=monoText(this,"Uruchamianie…",12,CODE_TEXT);output.setBackgroundColor(CODE);output.setPadding(dp(this,14),dp(this,14),dp(this,14),dp(this,14));output.setTextIsSelectable(true);scroll.addView(output);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        input=new EditText(this);input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setHint("Komenda lub odpowiedź dla klienta");input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);root.addView(input,full());
        LinearLayout actions=row(this);send=button(this,"Wyślij",true);send.setOnClickListener(v->send());input.setOnEditorActionListener((v,a,event)->{if(a==android.view.inputmethod.EditorInfo.IME_ACTION_SEND||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_DOWN)){send();return true;}return false;});input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        stop=button(this,"Stop",false);stop.setOnClickListener(v->session.stop());actions.addView(send,weight1());actions.addView(stop,weight1());root.addView(actions,full());setContentView(root);
    }
    @Override protected void onResume(){super.onResume();handler.post(refresh);if(!session.started)withToolHost(()->{synchronized(session){if(!session.started&&!session.stopped){session.started=true;session.start();}}});}
    @Override protected void onPause(){handler.removeCallbacksAndMessages(null);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("terminal_session",session.id);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){if(isFinishing()){session.stop();synchronized(sessions){sessions.remove(session.id);}}super.onDestroy();}
    private void send(){try{session.send(input.getText().toString());input.setText("");}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}
    private String shown="";
    private void render(){String text=session.text();if(!shown.equals(text)){shown=text;output.setText(text);Linkify.addLinks(output,Linkify.WEB_URLS);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));}boolean alive=session.alive();send.setEnabled(alive);input.setEnabled(alive);stop.setEnabled(alive);}
    private static final class Session {
        final String id=UUID.randomUUID().toString();final Context app;final Intent request;final StringBuilder buffer=new StringBuilder();volatile Process process;volatile boolean started,stopped;
        Session(Context c,Intent i){app=c;request=new Intent(i);}
        synchronized String text(){return buffer.toString();}
        synchronized void append(String value){buffer.append(value.replaceAll("\\x1B\\[[0-9;?]*[ -/]*[@-~]",""));if(buffer.length()>100000)buffer.delete(0,buffer.length()-100000);}
        boolean alive(){Process p=process;return p!=null&&p.isAlive()&&!stopped;}
        void start(){new Thread(()->{
            Object owner=this;McpHost.get(app).acquire(owner);PowerManager.WakeLock wake=app.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"androidagent:login-terminal");wake.acquire(45*60*1000L);
            try{
                EmbeddedRuntime runtime=EmbeddedRuntime.get(app);String mode=request.getStringExtra("mode");List<String> args=new ArrayList<>();
                if("login".equals(mode)){String backend=request.getStringExtra("backend");if(!runtime.agentInstalled(backend))throw new IOException("Najpierw zainstaluj klienta");args.add(backend);Collections.addAll(args,"codex".equals(backend)?new String[]{"login","--device-auth"}:new String[]{"auth","login"});}
                else if("helper".equals(mode)){Collections.addAll(args,EmbeddedRuntime.PREFIX+"/bin/bash",EmbeddedRuntime.HOME+"/bin/buddy-run",Prefs.token(app));String[] values=request.getStringArrayExtra("args");if(values!=null)Collections.addAll(args,values);}
                else Collections.addAll(args,EmbeddedRuntime.PREFIX+"/bin/bash","--noprofile","--norc");
                process=RuntimeProcess.start(runtime.interactiveCommand(args.toArray(new String[0])));if(stopped)process.destroyForcibly();
                try(Reader in=new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)){char[] bytes=new char[4096];int n;while((n=in.read(bytes))!=-1)append(new String(bytes,0,n));}
                int exit=process.waitFor();append("\n[exit "+exit+"]\n");if("login".equals(mode))runtime.refreshAccounts();
            }catch(Exception e){append("\n"+e.getMessage()+"\n");}finally{if(wake.isHeld())wake.release();McpHost.get(app).release(owner);}
        },"embedded-terminal").start();}
        synchronized void send(String value)throws IOException{if(!alive())throw new IOException("Terminal nie jest uruchomiony");process.getOutputStream().write((value+"\n").getBytes(StandardCharsets.UTF_8));process.getOutputStream().flush();}
        void stop(){stopped=true;Process p=process;if(p!=null&&p.isAlive())p.destroyForcibly();}
    }
}
