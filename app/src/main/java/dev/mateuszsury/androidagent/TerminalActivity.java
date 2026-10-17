package dev.mateuszsury.androidagent;

import android.content.*;
import android.graphics.Typeface;
import android.os.*;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.*;
import android.view.inputmethod.EditorInfo;
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
    private Session session;
    private TextView output, status, restart, stop;
    private Dot dot;
    private EditText input;
    private Button send;
    private ScrollView scroll;
    private LinearLayout keys, empty;
    private TextView emptyTitle, emptySub;
    private Typeface bold;
    private boolean visible,hostRequested;
    private long shownVersion=-1;
    private String shownState="";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){render();handler.postDelayed(this,350);}};
    static void openShell(Context c){open(c,"shell",null,null);}
    static void openLogin(Context c,String backend){open(c,"login",backend,null);}
    static void openHelper(Context c,String...args){open(c,"helper",null,args);}
    private static void open(Context c,String mode,String backend,String[]args){Intent i=new Intent(c,TerminalActivity.class).putExtra("mode",mode);if(backend!=null)i.putExtra("backend",backend);if(args!=null)i.putExtra("args",args);if(!(c instanceof android.app.Activity))i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(i);}

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        String id=state==null?getIntent().getStringExtra("terminal_session"):state.getString("terminal_session");
        synchronized(sessions){session=id==null?null:sessions.get(id);if(session==null){session=new Session(getApplicationContext(),getIntent());sessions.put(session.id,session);getIntent().putExtra("terminal_session",session.id);}}
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        bold=mono(this,700);
        String mode=getIntent().getStringExtra("mode"),backend=getIntent().getStringExtra("backend");
        boolean login="login".equals(mode);
        String context=login?("codex".equals(backend)?"$ codex login --device-auth":"$ claude auth login"):"helper".equals(mode)?"$ buddy-run":"~ · bash · bez roota";

        LinearLayout root=column(this);root.setBackgroundColor(BG);root.setFitsSystemWindows(true);
        stop=barAction(this,"Stop");stop.setTextColor(ERR);stop.setContentDescription("Stop");
        stop.setOnClickListener(v->{session.stop();Motion.pop(v,0);});
        root.addView(topBar(this,"Wbudowany terminal",context,stop),full());

        // Session status strip: a dot, a mono state and a restart action once the process ended.
        LinearLayout strip=row(this);strip.setPadding(dp(this,16),dp(this,8),dp(this,8),dp(this,2));
        dot=toolDot(this,FAINT);strip.addView(dot);
        status=single(monoText(this,"",12,MUTED));status.setPadding(dp(this,6),0,0,0);strip.addView(status,weight1());
        restart=barAction(this,"Nowa sesja");restart.setMinHeight(dp(this,36));restart.setPadding(dp(this,10),dp(this,6),dp(this,10),dp(this,6));
        restart.setVisibility(View.GONE);restart.setOnClickListener(v->restart());strip.addView(restart);
        root.addView(strip,full());
        String instructions=login?"Zaloguj się na oficjalnej stronie otwartej z linku. Jeśli klient poprosi o kod, wpisz go poniżej.":"Wpisz komendę lub odpowiedź dla klienta. Stop kończy bieżącą sesję terminala.";
        TextView intro=text(this,instructions,12,FAINT);intro.setLineSpacing(dp(this,2),1f);intro.setPadding(dp(this,16),dp(this,2),dp(this,16),dp(this,10));root.addView(intro,full());

        // The terminal surface: always dark, mono, ANSI colours as spans, links in the accent colour.
        FrameLayout panel=new FrameLayout(this);panel.setBackground(outlined(this,CODE,14,CODE_LINE,1));panel.setClipToOutline(true);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(true);
        output=monoText(this,"",12,CODE_TEXT);output.setTextSize(TypedValue.COMPLEX_UNIT_SP,12.5f);output.setLineSpacing(dp(this,1),1.08f);
        output.setPadding(dp(this,14),dp(this,12),dp(this,14),dp(this,14));output.setTextIsSelectable(true);
        output.setMovementMethod(LinkMovementMethod.getInstance());output.setLinkTextColor(ACCENT);output.setHighlightColor(Theme.withAlpha(ACCENT,0x55));
        scroll.addView(output,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        empty=column(this);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(this,28),dp(this,24),dp(this,28),dp(this,24));
        Spark spark=new Spark(this).animated(false).color(Theme.withAlpha(ACCENT,0xC0));empty.addView(spark,new LinearLayout.LayoutParams(dp(this,28),dp(this,28)));
        emptyTitle=monoText(this,"",14,CODE_TEXT);emptyTitle.setTypeface(mono(this,600));emptyTitle.setGravity(Gravity.CENTER);emptyTitle.setPadding(0,dp(this,14),0,0);empty.addView(emptyTitle,full());
        emptySub=text(this,"",13,CODE_MUTED);emptySub.setGravity(Gravity.CENTER);emptySub.setLineSpacing(dp(this,2),1f);emptySub.setPadding(0,dp(this,6),0,dp(this,16));empty.addView(emptySub,full());
        Button prepare=button(this,"Otwórz środowisko",true);prepare.setOnClickListener(v->EmbeddedRuntime.open(this));empty.addView(prepare,wrap());
        empty.setVisibility(View.GONE);
        panel.addView(empty,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams panelP=new LinearLayout.LayoutParams(-1,0,1);panelP.leftMargin=panelP.rightMargin=dp(this,12);
        root.addView(panel,panelP);

        // Raw keys go straight to the PTY behind `script`: ^C interrupts, Tab completes, arrows walk history.
        keys=row(this);keys.setPadding(dp(this,12),dp(this,8),dp(this,12),0);
        key("Ctrl-C","Klawisz Ctrl-C",()->"\u0003");
        key("Tab","Klawisz Tab",()->{String typed=input.getText().toString();input.setText("");return typed+"\t";});
        key("Esc","Klawisz Escape",()->"\u001b");
        key("↑","Strzałka w górę",()->session.applicationCursor()?"\u001bOA":"\u001b[A");
        key("↓","Strzałka w dół",()->session.applicationCursor()?"\u001bOB":"\u001b[B");
        root.addView(keys,full());

        LinearLayout bar=row(this);bar.setPadding(dp(this,12),dp(this,8),dp(this,12),dp(this,10));
        input=input(this,login?"kod lub odpowiedź":"komenda lub odpowiedź",true);input.setContentDescription(login?"Kod lub odpowiedź dla klienta":"Komenda lub odpowiedź dla klienta");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP,14);input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND|EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setOnEditorActionListener((v,a,event)->{if(a==EditorInfo.IME_ACTION_SEND||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_DOWN)){send();return true;}return false;});
        bar.addView(ToolUi.prompt(this,"shell".equals(mode)||mode==null?"$":"›",input),weight1());
        send=button(this,"Wyślij",true);send.setOnClickListener(v->send());
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(this,46));sp.leftMargin=dp(this,8);
        bar.addView(send,sp);
        root.addView(bar,full());
        setContentView(root);
        render();
    }

    interface Key{String bytes();}
    private void key(String label,String description,Key key){
        TextView k=monoText(this,label,12,TEXT);k.setTypeface(mono(this,600));k.setGravity(Gravity.CENTER);
        k.setPadding(dp(this,12),0,dp(this,12),0);k.setMinWidth(dp(this,44));k.setContentDescription(description);
        k.setBackground(outlined(this,CARD,8,OUTLINE,1));k.setForeground(getDrawable(ripple(this,false)));
        k.setOnClickListener(v->{try{session.sendRaw(key.bytes());}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_SHORT).show();}});
        Motion.press(k);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(this,34));p.rightMargin=dp(this,6);
        keys.addView(k,p);
    }

    @Override protected void onResume(){super.onResume();visible=true;hostRequested=false;handler.removeCallbacks(refresh);handler.post(refresh);startIfReady();}
    @Override protected void onPause(){visible=false;handler.removeCallbacksAndMessages(null);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("terminal_session",session.id);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){if(isFinishing()){session.stop();synchronized(sessions){sessions.remove(session.id);}}super.onDestroy();}

    /** The process needs the embedded Linux; until it is prepared the panel explains where to get it. */
    private void startIfReady(){
        if(session.started||!visible||hostRequested||!EmbeddedRuntime.get(this).ready())return;
        hostRequested=true; // once per resume: the host start is asynchronous
        withToolHost(()->{synchronized(session){if(!session.started&&!session.stopped){session.started=true;session.start();}}});
    }

    private void restart(){
        Intent again=new Intent(getIntent());again.removeExtra("terminal_session");
        finish();startActivity(again);overridePendingTransition(0,0);
    }

    private void send(){try{session.send(input.getText().toString());input.setText("");}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}

    private void render(){
        long version=session.version();
        if(version!=shownVersion){
            shownVersion=version;
            boolean follow=scroll.getScrollY()+scroll.getHeight()>=output.getHeight()-dp(this,64);
            output.setText(linked(session.snapshot(bold)));
            if(follow)scroll.post(()->scroll.scrollTo(0,Math.max(0,output.getHeight()-scroll.getHeight())));
        }
        EmbeddedRuntime runtime=EmbeddedRuntime.get(this);
        boolean alive=session.alive(),ready=runtime.ready(),ended=session.started&&!alive&&(session.finished||session.stopped);
        if(!session.started&&ready&&visible)startIfReady();
        String state;int color;boolean pulse=false;
        if(!session.started&&!ready){state=runtime.busy()?"przygotowywanie środowiska…":"środowisko nieprzygotowane";color=runtime.busy()?ACCENT:FAINT;pulse=runtime.busy();}
        else if(alive){state="sesja aktywna";color=OK;}
        else if(ended){Integer exit=session.exit;state=exit==null?"sesja zakończona":"sesja zakończona · kod "+exit;color=exit!=null&&exit==0?FAINT:ERR;}
        else{state="uruchamianie…";color=ACCENT;pulse=true;}
        if(!state.equals(shownState)){shownState=state;Motion.swapText(status,state);}
        dot.setColor(color);dot.setPulsing(pulse);
        boolean showEmpty=!session.started&&!ready;
        if(showEmpty){
            ToolUi.setText(emptyTitle,runtime.busy()?"Przygotowywanie środowiska…":"Środowisko nie jest przygotowane");
            ToolUi.setText(emptySub,runtime.busy()?runtime.status()+" · "+runtime.progress()+"%":"Terminal działa we wbudowanym Linuxie. Przygotuj go na ekranie środowiska, a sesja uruchomi się sama.");
        }
        if((empty.getVisibility()==View.VISIBLE)!=showEmpty){empty.setVisibility(showEmpty?View.VISIBLE:View.GONE);if(showEmpty)Motion.fadeIn(empty,0);}
        send.setEnabled(alive);send.setAlpha(alive?1f:0.45f);input.setEnabled(alive);
        stop.setEnabled(alive);stop.setAlpha(alive?1f:0.4f);
        for(int i=0;i<keys.getChildCount();i++){View k=keys.getChildAt(i);k.setEnabled(alive);k.setAlpha(alive?1f:0.4f);}
        ToolUi.setVisible(restart,ended);
    }

    /** URLs become accent links; OSC 8 links from the program are kept. */
    private static CharSequence linked(CharSequence text){
        SpannableStringBuilder out=text instanceof SpannableStringBuilder?(SpannableStringBuilder)text:new SpannableStringBuilder(text);
        SpannableString probe=new SpannableString(out.toString());
        if(Linkify.addLinks(probe,Linkify.WEB_URLS))
            for(URLSpan url:probe.getSpans(0,probe.length(),URLSpan.class)){
                int a=probe.getSpanStart(url),b=probe.getSpanEnd(url);
                if(out.getSpans(a,b,URLSpan.class).length==0)out.setSpan(new URLSpan(url.getURL()),a,b,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        return out;
    }

    private static final class Session {
        final String id=UUID.randomUUID().toString();final Context app;final Intent request;final AnsiText term=new AnsiText();volatile Process process;volatile boolean started,stopped,finished;volatile Integer exit;
        Session(Context c,Intent i){app=c;request=new Intent(i);term.setReply(this::reply);}
        synchronized String text(){return term.plain();}
        synchronized long version(){return term.version();}
        synchronized CharSequence snapshot(Typeface bold){return term.spanned(bold);}
        synchronized boolean applicationCursor(){return term.applicationCursor();}
        synchronized void append(String value){term.append(value);}
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
                int code=process.waitFor();exit=code;append("\u001b[?1049l\u001b[0m\n\u001b["+(code==0?"32":"31")+"m[exit "+code+"]\u001b[0m\n");if("login".equals(mode))runtime.refreshAccounts();
            }catch(Exception e){append("\u001b[0m\n\u001b[31m"+e.getMessage()+"\u001b[0m\n");}finally{finished=true;if(wake.isHeld())wake.release();McpHost.get(app).release(owner);}
        },"embedded-terminal").start();}
        synchronized void send(String value)throws IOException{if(!alive())throw new IOException("Terminal nie jest uruchomiony");process.getOutputStream().write((value+"\n").getBytes(StandardCharsets.UTF_8));process.getOutputStream().flush();}
        /** Bytes without a newline (control keys, escape sequences) for the PTY's line discipline and readline. */
        synchronized void sendRaw(String value)throws IOException{if(!alive())throw new IOException("Terminal nie jest uruchomiony");process.getOutputStream().write(value.getBytes(StandardCharsets.UTF_8));process.getOutputStream().flush();}
        /** Answers to terminal queries (cursor position) from the output parser; best effort. */
        private void reply(String value){Process p=process;if(p==null||!p.isAlive())return;try{p.getOutputStream().write(value.getBytes(StandardCharsets.UTF_8));p.getOutputStream().flush();}catch(IOException ignored){}}
        void stop(){stopped=true;Process p=process;if(p!=null&&p.isAlive())p.destroyForcibly();}
    }
}
