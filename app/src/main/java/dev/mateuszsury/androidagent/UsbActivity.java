package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
    private LinearLayout devices,sessions,roots;private TextView state;private Dot stateDot;private String signature="",artifactSession="";private String artifactAction="import";
    private final Map<String,ToolUi.Row> sessionRows=new HashMap<>();
    private final Set<String> seen=new HashSet<>();
    private boolean drawn;
    private final Runnable refresh=new Runnable(){public void run(){render();handler.postDelayed(this,1500);}};

    @Override protected void onCreate(Bundle saved){super.onCreate(saved);if(saved!=null){artifactSession=saved.getString("artifactSession","");artifactAction=saved.getString("artifactAction","import");}
        LinearLayout root=column(this);root.setBackgroundColor(BG);root.setFitsSystemWindows(true);
        root.addView(topBar(this,"USB 设备","uart · esp · adb · 存储介质"),full());
        ScrollView scroll=new ScrollView(this);LinearLayout body=ToolUi.body(this);
        TextView intro=ToolUi.note(this,"UART、ESP、第二台 Android 以及存储介质中的文件。选择设备并分配给对话。Docker 是可选的。",14);
        intro.setPadding(dp(this,20),dp(this,10),dp(this,20),dp(this,14));body.addView(intro,full());

        // ------------------------------------------------------------ hero: agent access
        LinearLayout hero=terminalBox(this,"代理访问",ACCENT);
        LinearLayout head=row(this);stateDot=toolDot(this,FAINT);head.addView(stateDot);
        state=text(this,"",14,TEXT);state.setTypeface(sans(this,600));state.setPadding(dp(this,8),0,0,0);head.addView(state,weight1());
        hero.addView(head,full());
        Button enable=button(this,"启用代理访问",true);enable.setOnClickListener(v->{try{McpHost.get(this).requireReady();McpHost.get(this).allowControl();startForegroundService(new Intent(this,AgentService.class));offerNotifications();signature="";}catch(Exception e){error(e);}});
        hero.addView(enable,ToolUi.buttonParams(this,14));
        Button stop=ToolUi.dangerButton(this,"停止 · 撤销所有访问权限");stop.setOnClickListener(v->{McpHost.get(this).emergencyStop();stopService(new Intent(this,AgentService.class));signature="";render();});
        hero.addView(stop,ToolUi.buttonParams(this,8));
        body.addView(hero,cardParams(this));

        body.addView(sectionLabel(this,"已连接的设备"),full());devices=column(this);body.addView(devices,full());
        body.addView(sectionLabel(this,"已打开的连接"),full());sessions=ToolUi.group(this);body.addView(sessions,cardParams(this));
        body.addView(sectionLabel(this,"目录与存储介质"),full());
        LinearLayout storage=ToolUi.group(this);
        ToolUi.Row select=new ToolUi.Row(this,R.drawable.ms_add,"选择目录","U 盘、磁盘或文件夹",false).accent()
            .onClick(v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),710));
        storage.addView(select.view,full());roots=column(this);storage.addView(roots,full());
        body.addView(storage,cardParams(this));
        TextView note=ToolUi.note(this,"打开适配器时，控制线可能改变状态。烧录需要指定芯片和固件。另一台手机上的 Android 必须接受 ADB 密钥。存储介质通过系统目录选择器打开。",12);
        note.setTextColor(FAINT);body.addView(note,full());
        LinearLayout more=ToolUi.group(this);
        ToolUi.add(more,new ToolUi.Row(this,R.drawable.ms_description,"Licencje USB","usb-serial · esp-flasher · libusb",true).onClick(v->new Thread(()->{try{StringBuilder notices=new StringBuilder("USB：固定来源与许可\n\n");for(String name:new String[]{"sources.json","usb-serial-MIT.txt","esp-flasher-Apache-2.0.txt"})try(InputStream in=getAssets().open("usb/"+name)){notices.append(new String(UsbArtifacts.read(in,65536),java.nio.charset.StandardCharsets.UTF_8)).append("\n\n");}notices.append("libusb/usbredir 许可：Docker 面板 → 许可与来源。");runOnUiThread(()->message("Licencje USB",notices.toString()));}catch(Exception e){runOnUiThread(()->error(e));}},"usb-licenses").start()).view);
        LinearLayout.LayoutParams mp=cardParams(this);mp.topMargin=dp(this,12);body.addView(more,mp);
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        placeholder(devices,"正在搜索设备…",false);sessions.setBackground(outlined(this,0x00000000,14,OUTLINE,1));placeholder(sessions,"未打开任何连接。",true);
        updateState();
        Motion.stagger(body,30,35);
    }

    /** A quiet line inside an empty list (a card for devices, a row for groups). */
    private void placeholder(LinearLayout list,String text,boolean inGroup){
        TextView t=text(this,text,14,MUTED);t.setPadding(dp(this,16),dp(this,15),dp(this,16),dp(this,15));
        if(inGroup){list.addView(t,full());return;}
        LinearLayout box=column(this);box.setBackground(outlined(this,0x00000000,14,OUTLINE,1));box.addView(t,full());
        list.addView(box,cardParams(this));
    }

    private volatile boolean rendering;
    private boolean resumed;
    private void render(){if(!resumed||isDestroyed())return;updateState();if(rendering)return;rendering=true;new Thread(()->{try{UsbSessions hub=UsbSessions.get(this);JSONArray d=hub.list(),s=hub.sessions(null),r=UsbStorage.get(this).roots("panel");runOnUiThread(()->{rendering=false;if(resumed&&!isDestroyed())draw(d,s,r);});}catch(Exception e){runOnUiThread(()->{rendering=false;if(!isDestroyed())state.setText("USB: "+e.getMessage());});}},"usb-refresh").start();}
    private void updateState() {
        McpHost host = McpHost.get(this);
        boolean stopping=host.stopping(),revoked=host.controlRevoked()!=null,running=host.running();
        ToolUi.setText(state, stopping ? "正在停止操作并撤销访问权限…"
            : revoked ? "代理访问已停止"
            : running ? "代理访问已启用 · Codex 和 Claude Code" : "代理访问已禁用");
        stateDot.setColor(stopping?WARN:revoked?ERR:running?OK:FAINT);stateDot.setPulsing(stopping);
    }

    /** Sessions without their live counters: rows are rebuilt only when something structural changes. */
    private static String structure(JSONArray s)throws JSONException{JSONArray out=new JSONArray();for(int i=0;i<s.length();i++){JSONObject o=new JSONObject(s.getJSONObject(i).toString());o.remove("rx");o.remove("tx");out.put(o);}return out.toString();}
    private static String counters(JSONObject o){String error=o.optString("error");return "rx "+o.optLong("rx")+" · tx "+o.optLong("tx")+(error.isEmpty()?"":" · "+error);}

    private void draw(JSONArray d,JSONArray s,JSONArray r){try{UsbSessions hub=UsbSessions.get(this);updateState();if(s.length()>0)offerNotifications();String sig=d.toString()+structure(s)+r.toString();
        if(sig.equals(signature)){for(int i=0;i<s.length();i++){JSONObject o=s.getJSONObject(i);ToolUi.Row row=sessionRows.get(o.optString("id"));if(row!=null)row.setMeta(counters(o));}return;}
        boolean animate=drawn;drawn=true;signature=sig;devices.removeAllViews();sessions.removeAllViews();roots.removeAllViews();sessionRows.clear();int delay=0;
        if(d.length()==0)placeholder(devices,"没有 USB 设备。请使用 OTG 线连接设备。",false);
        for(int i=0;i<d.length();i++){JSONObject o=d.getJSONObject(i);View card=deviceCard(o,hub);devices.addView(card,cardParams(this));if(animate&&seen.add("d"+o.optString("id")))Motion.enter(card,delay+=40);else seen.add("d"+o.optString("id"));}
        sessions.setBackground(s.length()==0?outlined(this,0x00000000,14,OUTLINE,1):outlined(this,CARD,14,LINE,1));if(s.length()==0)placeholder(sessions,"未打开任何连接。",true);
        for(int i=0;i<s.length();i++){JSONObject o=s.getJSONObject(i);String profile=o.optString("profile");
            ToolUi.Row row=new ToolUi.Row(this,profile.equals("adb")?R.drawable.ms_phone:profile.equals("docker")?R.drawable.ms_build_circle:R.drawable.ms_terminal,
                profileLabel(profile)+" · "+ownerLabel(o.optString("owner")),counters(o),true).onClick(v->sessionMenu(o));
            row.trail(toolDot(this,o.optBoolean("connected")?OK:ERR));sessionRows.put(o.optString("id"),row);ToolUi.add(sessions,row.view);
            if(animate&&seen.add("s"+o.optString("id")))Motion.enter(row.view,delay+=40);else seen.add("s"+o.optString("id"));}
        for(int i=0;i<r.length();i++){JSONObject o=r.getJSONObject(i);
            ToolUi.Row row=new ToolUi.Row(this,R.drawable.ms_description,o.optString("name"),(o.optBoolean("write")?"保存":"读取")+" · "+ownerLabel(o.optString("owner")),true)
                .onClick(v->ToolUi.options(this,o.optString("name"),null,new String[]{"浏览文件","分配给对话","撤销对话的访问权限","从应用中删除目录"},new int[]{3},which->{if(which==0)browse(o.optString("document"));else if(which==1)chooseOwner(owner->ToolUi.options(this,"访问范围",o.optString("name"),new String[]{"只读","读写"},null,w->{background(()->{UsbStorage.get(this).assign(o.optString("root"),owner,w==1);return null;});}));else if(which==2){background(()->{UsbStorage.get(this).revokeOwner(o.optString("owner"));return null;});}else{background(()->{UsbStorage.get(this).remove(o.optString("root"));return null;});}}));
            if(!o.optBoolean("available",true))row.title.setTextColor(MUTED);
            View line=new View(this);line.setBackgroundColor(LINE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(this,1));lp.leftMargin=dp(this,16);roots.addView(line,lp);
            roots.addView(row.view,full());if(animate&&seen.add("r"+o.optString("root")))Motion.enter(row.view,delay+=40);else seen.add("r"+o.optString("root"));}
    }catch(Exception e){state.setText("USB: "+e.getMessage());}}

    /** A device card: permission dot, name, mono vid:pid and the profiles it can open. */
    private View deviceCard(JSONObject o,UsbSessions hub)throws JSONException{
        boolean permitted=o.optBoolean("permission");
        LinearLayout card=card(this);
        card.setForeground(getDrawable(ripple(this,false)));card.setClipToOutline(true);
        LinearLayout top=row(this);Dot dot=toolDot(this,permitted?OK:WARN);top.addView(dot);
        TextView name=single(title(this,o.optString("name","USB 设备").isEmpty()?"USB 设备":o.optString("name","USB 设备"),15));name.setPadding(dp(this,8),0,dp(this,8),0);top.addView(name,weight1());
        top.addView(icon(this,R.drawable.ms_chevron_right,FAINT,20));card.addView(top,full());
        String ids=String.format(Locale.US,"%04x:%04x",o.getInt("vid"),o.getInt("pid"));String serial=o.optString("serial");
        TextView meta=single(monoText(this,ids+(serial.isEmpty()?"":" · sn "+serial)+" · "+(permitted?"已授予访问权限":"需要授权"),12,permitted?MUTED:WARN));
        meta.setPadding(dp(this,24),dp(this,4),0,0);card.addView(meta,full());
        LinearLayout badges=row(this);badges.setPadding(dp(this,24),dp(this,10),0,0);
        if(o.optBoolean("serial_supported"))addBadge(badges,"UART / ESP",ACCENT_TEXT);
        if(adb(o))addBadge(badges,"ADB",INFO);
        addBadge(badges,"Docker",MUTED);
        card.addView(badges,full());
        card.setOnClickListener(v->{if(!permitted){background(()->{hub.permission(this,o.optString("id"));return null;});return;}
            ToolUi.options(this,"选择配置",name.getText()+" · "+ids,new String[]{"UART / ESP 终端","Drugi Android · ADB","USB do Dockera"},null,which->{String p=new String[]{"serial","adb","docker"}[which];if(p.equals("serial"))form("Port adaptera",new String[]{"Numer portu (od 0)"},new String[]{"0"},values->open(o.optString("id"),p,Integer.parseInt(values[0])));else open(o.optString("id"),p,0);});});
        Motion.press(card);
        return card;
    }
    private void addBadge(LinearLayout row,String text,int color){LinearLayout.LayoutParams p=wrap();p.rightMargin=dp(this,6);row.addView(badge(this,text,color),p);}
    private static boolean adb(JSONObject o){JSONArray f=o.optJSONArray("interfaces");if(f!=null)for(int i=0;i<f.length();i++){JSONObject x=f.optJSONObject(i);if(x!=null&&x.optInt("class")==255&&x.optInt("subclass")==66&&x.optInt("protocol")==1)return true;}return false;}
    private static String profileLabel(String p){return p.equals("serial")?"UART":p.equals("adb")?"ADB":p.equals("docker")?"Docker":p;}

    private String ownerLabel(String owner){if(owner.equals("panel"))return "panel";for(Sessions.Session s:ChatHub.get(this).sessions.all)if((owner.equals("chat:"+s.id)||owner.equals("pending:"+s.id)))return s.title+(owner.startsWith("pending:")?" · 下一个任务":"");return "对话";}
    private void open(String d,String p,int port){background(()->UsbSessions.get(this).open(d,p,"panel",port).json());}
    private void sessionMenu(JSONObject o){String id=o.optString("id"),owner=o.optString("owner"),profile=o.optString("profile");List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        if(profile.equals("serial")){labels.add("终端与日志");actions.add(()->terminal(id,owner));}
        if(owner.equals("panel")){
            labels.add("分配给对话");actions.add(()->chooseOwner(value->{background(()->{UsbSessions.get(this).assign(id,value);return null;});}));
            if(profile.equals("serial")){labels.add("Parametry UART");actions.add(()->form("Parametry UART",new String[]{"Baud rate","Data bits","Stop bits (1 / 2 / 3 = 1.5)","Parity (0–4)"},new String[]{"115200","8","1","0"},v->call("usb_serial_configure",new JSONObject().put("session",id).put("baud",Integer.parseInt(v[0])).put("bits",Integer.parseInt(v[1])).put("stop",Integer.parseInt(v[2])).put("parity",Integer.parseInt(v[3])))));
                labels.add("DTR / RTS / break 线路");actions.add(()->controlLines(id));
                labels.add("保存日志");actions.add(()->simple("usb_serial_save",id));labels.add("Identyfikuj ESP");actions.add(()->espReset(id,"esp_info",null));labels.add("Wgraj firmware ESP");actions.add(()->pickArtifact(id,"flash"));
            }
            if(profile.equals("adb")){labels.add("连接 ADB / 接受密钥");actions.add(()->{try{call("adb_status",new JSONObject().put("session",id).put("connect",true));}catch(Exception e){error(e);}});labels.add("在第二台手机上的命令");actions.add(()->form("Shell · drugi Android",new String[]{"命令"},new String[]{"getprop ro.product.model"},v->call("adb_exec",new JSONObject().put("session",id).put("command",v[0]))));labels.add("Logcat");actions.add(()->simple("adb_logcat",id));labels.add("下载文件");actions.add(()->form("下载文件",new String[]{"另一台 Android 上的路径"},new String[]{"/sdcard/Download/example.txt"},v->call("adb_pull",new JSONObject().put("session",id).put("path",v[0]))));labels.add("发送文件");actions.add(()->pickArtifact(id,"push"));labels.add("Zainstaluj APK");actions.add(()->pickArtifact(id,"install"));}
            if(profile.equals("serial")||profile.equals("docker")){labels.add("分配给 Docker 任务");actions.add(()->chooseTask(task->{try{call(profile.equals("serial")?"usb_docker_serial":"usb_docker_attach",new JSONObject().put("session",id).put("task",task));}catch(Exception e){error(e);}}));}
        }
        labels.add("关闭并撤销访问权限");actions.add(()->{background(()->{UsbSessions.get(this).close(owner,id);return null;});});
        ToolUi.options(this,"连接 "+profileLabel(profile),ownerLabel(owner)+" · "+counters(o),labels.toArray(new String[0]),new int[]{labels.size()-1},which->actions.get(which).run());
    }
    /** DTR / RTS / break as switches; every change sends the full line state. */
    private void controlLines(String id){
        final boolean[] checked=new boolean[3];LinearLayout box=ToolUi.group(this);String[] names={"DTR","RTS","Break"};
        for(int i=0;i<3;i++){final int which=i;ToolUi.add(box,switchRow(this,names[i],i==2?"线路中断信号":"适配器控制线",false,on->{checked[which]=on;try{call("usb_serial_control",new JSONObject().put("session",id).put("dtr",checked[0]).put("rts",checked[1]).put("break",checked[2]));}catch(Exception e){error(e);}}));}
        LinearLayout content=column(this);content.addView(box,full());Button close=button(this,"关闭",false);content.addView(close,ToolUi.buttonParams(this,14));
        Dialog d=ToolUi.sheet(this,"控制线","更改会立即应用到适配器。",content);close.setOnClickListener(v->d.dismiss());d.show();
    }
    interface Choice{void accept(String value);}interface Values{void accept(String[] values)throws Exception;}interface Work{JSONObject run()throws Exception;}
    private void chooseOwner(Choice choice){List<String> names=new ArrayList<>(),ids=new ArrayList<>();for(Sessions.Session s:ChatHub.get(this).sessions.all){names.add(s.title+" · "+s.backend+(s.running?" · 活跃":" · 下一个任务"));ids.add((s.running?"chat:":"pending:")+s.id);}if(ids.isEmpty()){message("分配","请先创建对话。你可以将设备分配给当前回复，或一次性分配给下一个任务。",false);return;}ToolUi.options(this,"分配给对话",null,names.toArray(new String[0]),null,w->choice.accept(ids.get(w)));}
    private void chooseTask(Choice choice){JSONArray tasks=DockerVm.get(this).guest.optJSONArray("tasks");if(tasks==null||tasks.length()==0){message("Docker","在 Docker 面板中启动环境并创建任务。",false);return;}String[] names=new String[tasks.length()];for(int i=0;i<names.length;i++)names[i]=tasks.optJSONObject(i).optString("name");ToolUi.options(this,"Docker 任务",null,names,null,w->choice.accept(tasks.optJSONObject(w).optString("id")));}
    private void form(String title,String[] labels,String[] defaults,Values done){if(isDestroyed()||isFinishing())return;LinearLayout content=column(this);EditText[] input=new EditText[labels.length];
        for(int i=0;i<labels.length;i++){TextView label=text(this,labels[i],13,MUTED);label.setPadding(0,i==0?0:dp(this,12),0,dp(this,6));content.addView(label,full());input[i]=input(this,labels[i],true);input[i].setSingleLine();input[i].setText(defaults[i]);input[i].setSelection(defaults[i].length());content.addView(input[i],full());}
        Button cancel=button(this,"取消",false),run=button(this,"执行",true);LinearLayout buttons=ToolUi.buttons(this,cancel,run);LinearLayout.LayoutParams bp=full();bp.topMargin=dp(this,18);content.addView(buttons,bp);
        Dialog d=ToolUi.sheet(this,title,null,content);if(d.getWindow()!=null)d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        cancel.setOnClickListener(v->d.dismiss());run.setOnClickListener(button->{String[] values=new String[input.length];for(int i=0;i<values.length;i++)values[i]=input[i].getText().toString();try{done.accept(values);d.dismiss();}catch(Exception e){Motion.shake(content);error(e);}});d.show();}
    private void terminal(String id,String owner){background(()->{SerialTransport port=UsbSessions.get(this).owned(owner,id).serial;runOnUiThread(()->terminalView(id,owner,port));return null;});}
    AlertDialog terminalView(String id,String owner,SerialTransport port){if(isDestroyed()||isFinishing())return null;
        LinearLayout content=column(this);content.setPadding(dp(this,20),dp(this,6),dp(this,20),dp(this,4));
        FrameLayout frame=new FrameLayout(this);frame.setBackground(outlined(this,CODE,12,CODE_LINE,1));frame.setClipToOutline(true);frame.setPadding(dp(this,1),dp(this,1),dp(this,1),dp(this,1));
        TextView log=monoText(this,"",12,CODE_TEXT);log.setTextIsSelectable(true);log.setBackgroundColor(CODE);log.setLineSpacing(dp(this,2),1f);log.setPadding(dp(this,12),dp(this,12),dp(this,12),dp(this,12));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(log);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));content.addView(frame,new LinearLayout.LayoutParams(-1,dp(this,260)));
        EditText command=input(this,"Tekst lub hex",true);command.setSingleLine(true);LinearLayout.LayoutParams cp=full();cp.topMargin=dp(this,10);content.addView(command,cp);
        LinearLayout controls=row(this);Spinner format=new Spinner(this);format.setContentDescription("Format danych");ToolUi.styleSpinner(format,new String[]{"Tekst + LF","Tekst + CRLF","文本没有换行符","Hex"});
        controls.addView(format,new LinearLayout.LayoutParams(0,dp(this,46),1));Button send=button(this,"发送",true);send.setEnabled(owner.equals("panel"));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(this,46));sp.leftMargin=dp(this,8);controls.addView(send,sp);
        LinearLayout.LayoutParams rp=full();rp.topMargin=dp(this,8);content.addView(controls,rp);
        send.setOnClickListener(v->{try{int f=format.getSelectedItemPosition();String value=command.getText().toString()+(f==0?"\n":f==1?"\r\n":"");call("usb_serial_write",new JSONObject().put("session",id).put("data",value).put("encoding",f==3?"hex":"utf8"));command.setText("");}catch(Exception e){error(e);}});
        ScrollView terminalScroll=new ScrollView(this);terminalScroll.setFillViewport(true);terminalScroll.addView(content);
        AlertDialog dialog=ToolUi.theme(new AlertDialog.Builder(this).setTitle("UART 终端").setView(terminalScroll).setPositiveButton("关闭",null).create());
        Runnable poll=new Runnable(){long end=-1;public void run(){if(!dialog.isShowing()||isDestroyed())return;try{boolean writable=owner.equals("panel")&&!port.closed&&!port.exclusive&&!port.bridged;send.setEnabled(writable);send.setAlpha(writable?1f:0.45f);long latest=port.received.end();if(latest!=end){UsbBuffer.Slice s=port.received.read(Math.max(0,latest-65536),65536);log.setText(UsbTools.encode(s.bytes,"utf8"));end=latest;scroll.post(()->scroll.scrollTo(0,log.getHeight()));}}catch(Exception e){log.setText(e.getMessage());}if(!port.closed)handler.postDelayed(this,500);}};
        dialog.setOnShowListener(v->handler.post(poll));dialog.setOnDismissListener(v->handler.removeCallbacks(poll));dialog.show();return dialog;}
    private void browse(String document) { browse(document, 0); }
    private void browse(String document, int offset) {
        background(() -> {
            JSONArray entries = UsbStorage.get(this).list("panel", document, offset, 200);
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                List<String> names = new ArrayList<>();
                List<Runnable> actions = new ArrayList<>();
                if (offset > 0) {
                    names.add("← 上一个文件");
                    actions.add(() -> browse(document, Math.max(0, offset - 200)));
                }
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    names.add(entry.optString("name"));
                    actions.add(() -> documentMenu(entry));
                }
                if (entries.length() == 200) {
                    names.add("下一个文件 →");
                    actions.add(() -> browse(document, offset + 200));
                }
                names.add("+ 新建文件");
                actions.add(() -> form("新建文件", new String[]{"名称"}, new String[]{"文件.txt"},
                    v -> call("usb_storage_create", new JSONObject().put("parent", document)
                        .put("name", v[0]).put("mime", "text/plain"))));
                // Native list dialog on purpose: paging is driven through its accessible list.
                ToolUi.theme(new AlertDialog.Builder(this).setTitle("文件 · 第 " + (offset / 200 + 1))
                    .setItems(names.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                    .setNegativeButton("关闭", null).create()).show();
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
        Object bytes = entry.opt("bytes");
        ToolUi.options(this, entry.optString("name"), bytes instanceof Number ? ToolUi.size(((Number) bytes).longValue()) + " · " + entry.optString("mime") : entry.optString("mime"),
            new String[]{"读取文本", "导入文件", "覆盖文本", "删除文件"}, new int[]{3}, action -> {
                try {
                    if (action < 2) call(action == 0 ? "usb_storage_read" : "usb_storage_import",
                        new JSONObject().put("document", id));
                    else if (action == 2) form("覆盖文件", new String[]{"Tekst"}, new String[]{""},
                        v -> call("usb_storage_write", new JSONObject().put("document", id).put("data", v[0])));
                    else confirmSheet(this, "删除文件？", entry.optString("name"), "删除", true, () -> {
                        try { call("usb_storage_delete", new JSONObject().put("document", id)); }
                        catch (Exception e) { error(e); }
                    });
                } catch (Exception e) { error(e); }
            });
    }
    private void pickArtifact(String session,String action){artifactSession=session;artifactAction=action;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(action.equals("install")?"application/vnd.android.package-archive":"application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),711);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==710){background(()->{UsbStorage.get(this).add(uri,data.getFlags());return null;});}else if(request==711){final String session=artifactSession,action=artifactAction;new Thread(()->{String name="firmware.bin";try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst()&&cursor.getString(0)!=null)name=cursor.getString(0);}catch(Exception ignored){}final String filename=name;try(InputStream in=getContentResolver().openInputStream(uri)){JSONObject artifact=UsbArtifacts.save(this,in,filename,"panel",null,512L*1024*1024);runOnUiThread(()->{if(isDestroyed())return;if(action.equals("flash"))espReset(session,"esp_flash",artifact);else if(action.equals("install")){try{call("adb_install",new JSONObject().put("session",session).put("artifact",artifact.getString("id")));}catch(Exception e){error(e);}}else if(action.equals("push"))form("发送文件",new String[]{"另一台 Android 上的路径"},new String[]{"/sdcard/Download/"+filename},v->call("adb_push",new JSONObject().put("session",session).put("artifact",artifact.getString("id")).put("path",v[0])));});}catch(Exception e){runOnUiThread(()->error(e));}},"usb-import").start();}}
    private void espReset(String id,String operation,JSONObject artifact){ToolUi.options(this,"Bootloader ESP","如何进入烧录模式？",new String[]{"手动 BOOT / RESET","通过 DTR / RTS 自动复位"},null,w->{try{if(artifact==null)call(operation,new JSONObject().put("session",id).put("auto_reset",w==1));else form("Mapa firmware'u · "+artifact.optString("sha256"),new String[]{"来自 ESP 识别信息的芯片编号","Offset (np. 0x1000)"},new String[]{"0","0x1000"},v->call(operation,new JSONObject().put("session",id).put("auto_reset",w==1).put("expected_chip",Integer.parseInt(v[0])).put("segments",new JSONArray().put(new JSONObject().put("artifact",artifact.getString("id")).put("offset",Long.decode(v[1]))))));}catch(Exception e){error(e);}});}
    private void simple(String n,String session){try{call(n,new JSONObject().put("session",session));}catch(Exception e){error(e);}}
    private void call(String n,JSONObject a){background(()->{JSONObject r=UsbTools.call(this,"panel",n,a).getJSONObject("structuredContent");if(r.has("kind")&&r.has("id")){runOnUiThread(()->watchJob(r.optString("id")));return null;}return r;});}
    /** A long USB operation: live JSON status in a terminal panel, with cancel. */
    private void watchJob(String id){if(isDestroyed()||isFinishing())return;LinearLayout content=column(this);LinearLayout head=row(this);Dot dot=toolDot(this,ACCENT);dot.setPulsing(true);head.addView(dot);TextView phase=single(monoText(this,"等待中…",12,MUTED));phase.setPadding(dp(this,6),0,0,0);head.addView(phase,weight1());content.addView(head,full());
        LinearLayout panelBox=column(this);LinearLayout.LayoutParams pb=full();pb.topMargin=dp(this,10);content.addView(panelBox,pb);TextView status=ToolUi.logPanel(panelBox,"等待中…",ToolUi.screenDp(this,0.4f),false);
        Button cancel=ToolUi.dangerButton(this,"取消操作"),close=button(this,"关闭",false);LinearLayout.LayoutParams bp=full();bp.topMargin=dp(this,14);content.addView(ToolUi.buttons(this,cancel,close),bp);
        Dialog dialog=ToolUi.sheet(this,"Operacja USB",null,content);
        cancel.setOnClickListener(v->{dialog.dismiss();try{UsbJobs.get(this).status("panel",id,true);}catch(Exception e){error(e);}});close.setOnClickListener(v->dialog.dismiss());
        Runnable poll=new Runnable(){public void run(){if(!dialog.isShowing())return;try{JSONObject j=UsbJobs.get(UsbActivity.this).status("panel",id,false);String s=j.optString("state");ToolUi.setText(status,j.toString(2));ToolUi.setText(phase,s);boolean live=s.equals("queued")||s.equals("running");dot.setPulsing(live);dot.setColor(live?ACCENT:s.equals("succeeded")?OK:ERR);ToolUi.setVisible(cancel,live);if(live)handler.postDelayed(this,500);}catch(Exception e){status.setText(e.getMessage());}}};
        dialog.setOnShowListener(v->handler.post(poll));dialog.setOnDismissListener(v->handler.removeCallbacks(poll));dialog.show();}
    private void background(Work work) {
        try {
            operations.execute(() -> {
                try {
                    JSONObject value = work.run();
                    runOnUiThread(() -> {
                        signature = "";
                        if (!isDestroyed()) {
                            render();
                            if (value != null) message("Wynik", pretty(value));
                        }
                    });
                } catch (Exception e) { runOnUiThread(() -> error(e)); }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            message("USB", "正在进行的操作过多。请等待它们完成。", false);
        }
    }
    private static String pretty(JSONObject value){try{return value.toString(2);}catch(JSONException e){return value.toString();}}
    private void message(String title,String body){message(title,body,true);}
    private void message(String title,String body,boolean code){ToolUi.message(this,title,body,code,false);}
    private void error(Exception e){if(!isDestroyed()&&!isFinishing())ToolUi.message(this,"USB",String.valueOf(e.getMessage()),false,true);}
    @Override protected void onResume(){super.onResume();resumed=true;handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);operations.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("artifactSession",artifactSession);out.putString("artifactAction",artifactAction);super.onSaveInstanceState(out);}
}
