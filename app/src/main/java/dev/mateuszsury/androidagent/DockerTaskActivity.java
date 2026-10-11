package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;
import android.content.Intent;
import android.os.*;
import android.widget.*;
import org.json.*;

/** Source editor and bounded asynchronous jobs. No shell command runs on Android. */
public class DockerTaskActivity extends Screen {
    private DockerVm vm;
    private String task, job;
    private TextView output;
    private EditText filename, source, image, command, artifact;
    private CheckBox preview;
    private boolean polling, visible;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable pollTick=this::pollJob;
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);vm=DockerVm.get(this);task=getIntent().getStringExtra("task");
        if(task==null){finish();return;} if(state!=null)job=state.getString("job");
        LinearLayout root=column(this);root.setBackgroundColor(BG);root.setFitsSystemWindows(true);
        LinearLayout bar=row(this);ImageButton back=iconButton(this,R.drawable.ms_arrow_back,"Wróć");back.setOnClickListener(v->finish());bar.addView(back);bar.addView(title(this,"Zadanie Docker",21),weight1());root.addView(bar,full());
        ScrollView scroll=new ScrollView(this);LinearLayout body=column(this);body.setPadding(dp(this,22),dp(this,12),dp(this,22),dp(this,24));
        body.addView(title(this,"Pliki projektu",21));
        filename=input("Ścieżka pliku","Dockerfile",false);body.addView(filename,full());
        source=input("Treść pliku","FROM alpine:3.23\nWORKDIR /workspace\n",true);source.setMinLines(5);body.addView(source,full());
        Button load=button(this,"Wczytaj plik",false);load.setOnClickListener(v->operation("read","path",filename.getText().toString()));body.addView(load,full());
        Button files=button(this,"Lista plików",false);files.setOnClickListener(v->operation("files"));body.addView(files,full());
        Button save=button(this,"Zapisz plik",true);save.setOnClickListener(v->operation("write","path",filename.getText().toString(),"content",source.getText().toString()));body.addView(save,full());
        Button build=button(this,"Zbuduj obraz",true);build.setOnClickListener(v->operation("build","dockerfile",filename.getText().toString()));body.addView(build,full());
        body.addView(title(this,"Uruchamianie",21));
        image=input("Obraz bazowy (puste: zbudowany obraz)","",false);body.addView(image,full());
        command=input("Komenda w kontenerze","",false);body.addView(command,full());
        preview=new CheckBox(this);preview.setText("Podgląd aplikacji na porcie 8080");preview.setTextColor(TEXT);preview.setChecked(getIntent().getBooleanExtra("demo",false));body.addView(preview,full());
        Button run=button(this,"Uruchom kontener",true);run.setOnClickListener(v->operation("run","image",image.getText().toString(),"command",command.getText().toString(),"port",preview.isChecked()?8080:JSONObject.NULL));body.addView(run,full());
        Button exec=button(this,"Wykonaj w kontenerze",false);exec.setOnClickListener(v->operation("exec","command",command.getText().toString()));body.addView(exec,full());
        Button view=button(this,"Otwórz podgląd",false);view.setOnClickListener(v->startActivity(new Intent(this,DockerPreviewActivity.class)));body.addView(view,full());
        output=monoText(this,"Gotowy. Zapisz pliki, zbuduj obraz i uruchom aplikację.",12,CODE_TEXT);output.setBackground(rounded(this,CODE,14));output.setPadding(dp(this,14),dp(this,14),dp(this,14),dp(this,14));output.setTextIsSelectable(true);output.setMinHeight(dp(this,140));body.addView(output,full());
        Button logs=button(this,"Odśwież dziennik",false);logs.setOnClickListener(v->operation("logs"));body.addView(logs,full());
        Button cancel=button(this,"Zatrzymaj zadanie",false);cancel.setOnClickListener(v->operation("cancel"));body.addView(cancel,full());
        artifact=input("Plik do zachowania","out/app.apk",false);body.addView(artifact,full());
        Button export=button(this,"Zachowaj plik",true);export.setOnClickListener(v->{final String path=artifact.getText().toString();new Thread(()->{
            try{JSONObject result=vm.export(task,path);handler.post(()->{if(!isDestroyed())output.setText("Zachowano "+result.optString("name")+"\nPlik jest dostępny na ekranie Docker także po usunięciu zadania.");});}
            catch(Exception e){handler.post(()->showError(e));}
        },"docker-export").start();});body.addView(export,full());
        Button cleanup=button(this,"Posprzątaj i usuń zadanie",false);cleanup.setOnClickListener(v->confirmSheet(this,"Usunąć zadanie?","Usuniesz kontener, jego obraz i pliki robocze. Zachowane pliki pozostaną dostępne.","Posprzątaj",true,()->operation("cleanup")));body.addView(cleanup,full());
        DockerActivity.spaceButtons(body);
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        if(state!=null){filename.setText(state.getString("filename"));source.setText(state.getString("source"));image.setText(state.getString("image"));command.setText(state.getString("command"));artifact.setText(state.getString("artifact"));output.setText(state.getString("output"));preview.setChecked(state.getBoolean("preview"));}
        else {
            final String initial=source.getText().toString();
            new Thread(()->{
                try{JSONObject file=vm.request(new JSONObject().put("op","read").put("task",task).put("path","Dockerfile"));
                    handler.post(()->{if(!isDestroyed()&&source.getText().toString().equals(initial))source.setText(file.optString("content"));});
                }catch(Exception ignored){}
            },"docker-open-source").start();
        }
    }
    private EditText input(String label,String value,boolean multiline){
        EditText e=new EditText(this);e.setHint(label);e.setContentDescription(label);e.setText(value);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(13);e.setTypeface(mono(this,400));e.setSingleLine(!multiline);e.setBackground(rounded(this,CARD,12));e.setPadding(dp(this,12),dp(this,12),dp(this,12),dp(this,12));return e;
    }
    private void operation(String op,Object... fields){
        final JSONObject args=new JSONObject();
        try{args.put("op",op).put("task",task);for(int i=0;i<fields.length;i+=2)if(fields[i+1]!=JSONObject.NULL)args.put((String)fields[i],fields[i+1]);}
        catch(Exception e){showError(e);return;}
        output.setText("Wykonywanie…");
        new Thread(()->{
            try{
                JSONObject result=vm.request(args);
                handler.post(()->{
                    if(isDestroyed())return;
                    if(result.has("kind")&&result.has("id")){job=result.optString("id");pollJob();}
                    else if("read".equals(op)){filename.setText(result.optString("path"));source.setText(result.optString("content"));output.setText("Wczytano "+result.optString("path"));}
                    else if("files".equals(op)){
                        JSONArray list=result.optJSONArray("files");
                        String[] names=new String[list==null?0:list.length()];for(int i=0;i<names.length;i++)names[i]=list.optString(i);
                        if(names.length==0)output.setText("Projekt nie zawiera plików.");
                        else new android.app.AlertDialog.Builder(this).setTitle("Pliki projektu").setItems(names,(dialog,index)->operation("read","path",names[index])).setNegativeButton("Zamknij",null).show();
                    }
                    else if("cleanup".equals(op)){finish();}
                    else output.setText(result.optString("output",result.toString()));
                });
            }catch(Exception e){handler.post(()->showError(e));}
        },"docker-task").start();
    }
    private void pollJob(){
        if(polling||!visible||job==null||isDestroyed())return;
        handler.removeCallbacks(pollTick);polling=true;final String id=job;
        new Thread(()->{
            try{JSONObject result=vm.request(new JSONObject().put("op","logs").put("task",task).put("job",id));
                handler.post(()->{
                    polling=false;if(isDestroyed())return;
                    if(!id.equals(job)){pollJob();return;}
                    output.setText(result.optString("state")+"\n\n"+result.optString("output"));
                    if(visible&&("running".equals(result.optString("state"))||"queued".equals(result.optString("state"))))handler.postDelayed(pollTick,1500);
                });
            }catch(Exception e){handler.post(()->{polling=false;if(!id.equals(job)){pollJob();return;}showError(e);});}
        },"docker-job-poll").start();
    }
    private void showError(Exception e){if(!isDestroyed())output.setText("Błąd: "+e.getMessage());}
    @Override protected void onResume(){super.onResume();visible=true;if(job!=null)pollJob();}
    @Override protected void onPause(){visible=false;handler.removeCallbacks(pollTick);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){if(filename!=null){out.putString("job",job);out.putString("filename",filename.getText().toString());out.putString("source",source.getText().toString());out.putString("image",image.getText().toString());out.putString("command",command.getText().toString());out.putString("artifact",artifact.getText().toString());out.putString("output",output.getText().toString());out.putBoolean("preview",preview.isChecked());}super.onSaveInstanceState(out);}
}
