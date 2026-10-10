package dev.mateuszsury.androidagent;

import static dev.mateuszsury.androidagent.Ui.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;

/** Interactive localhost preview. Container pages have no Android Java bridge. */
public class DockerPreviewActivity extends Screen {
    WebView web;
    private final String home="http://127.0.0.1:"+DockerVm.PREVIEW_PORT+"/";
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=column(this);root.setBackgroundColor(BG);root.setFitsSystemWindows(true);
        LinearLayout bar=row(this);
        ImageButton back=iconButton(this,R.drawable.ms_arrow_back,"Wróć");back.setOnClickListener(v->finish());bar.addView(back);
        bar.addView(title(this,"Podgląd aplikacji",20),weight1());
        Button refresh=button(this,"Odśwież",false);refresh.setOnClickListener(v->web.loadUrl(home));bar.addView(refresh);
        root.addView(bar,full());
        web=new WebView(this);web.setContentDescription("Podgląd aplikacji WWW");
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();
                if("http".equals(uri.getScheme())&&"127.0.0.1".equals(uri.getHost())&&uri.getPort()==DockerVm.PREVIEW_PORT)return false;
                if("https".equals(uri.getScheme())||"http".equals(uri.getScheme())) {
                    try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}
                    catch(android.content.ActivityNotFoundException e){Toast.makeText(DockerPreviewActivity.this,"Brak przeglądarki do otwarcia odnośnika",Toast.LENGTH_SHORT).show();}
                }
                return true;
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                if(request.isForMainFrame())view.loadDataWithBaseURL(null,"<meta name=viewport content='width=device-width,initial-scale=1'><style>body{font:18px system-ui;padding:24px}</style><h2>Aplikacja jeszcze nie odpowiada.</h2><p>Uruchom kontener z podglądem i naciśnij Odśwież.</p>","text/html","UTF-8",null);
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);web.loadUrl(home);
    }
    @Override protected void onDestroy(){if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
}
