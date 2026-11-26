"""Contract tests on the real Java helper against minimal Android API doubles.

These exercise intent construction and failure handling, not a real Android device.
"""
from pathlib import Path
import subprocess
import os
import shutil

root = Path(__file__).resolve().parents[1]
work = root / 'build/contract-tests'
sources = {
'android/content/ComponentName.java': '''package android.content;
public class ComponentName { public String pkg,name; public ComponentName(String p,String n){pkg=p;name=n;} }''',
'android/content/ActivityNotFoundException.java': '''package android.content;
public class ActivityNotFoundException extends RuntimeException {}''',
'android/net/Uri.java': '''package android.net;
public class Uri { private final String value; private Uri(String s){value=s;}
public static Uri parse(String s){return new Uri(s);} public String toString(){return value;}
public String getScheme(){return value.substring(0,value.indexOf(':'));} }''',
'android/content/Intent.java': '''package android.content;
import android.net.Uri;
public class Intent {
public static final String ACTION_VIEW="android.intent.action.VIEW";
public String action,type,title; public Uri data; public ComponentName component; public Intent inner;
public Intent(String a){action=a;}
public Intent setDataAndType(Uri u,String t){data=u;type=t;return this;}
public Uri getData(){return data;}
public Intent putExtra(String k,String s){title=s;return this;}
public Intent setClassName(String p,String n){component=new ComponentName(p,n);return this;}
public Intent setComponent(ComponentName c){component=c;return this;}
public static Intent createChooser(Intent i,CharSequence t){Intent c=new Intent("android.intent.action.CHOOSER");c.inner=i;return c;}
}''',
'android/content/pm/ActivityInfo.java': '''package android.content.pm;
public class ActivityInfo { public String packageName,name; public boolean enabled=true,exported=true; }''',
'android/content/pm/ResolveInfo.java': '''package android.content.pm;
public class ResolveInfo { public ActivityInfo activityInfo=new ActivityInfo(); public String label;
public CharSequence loadLabel(PackageManager pm){return label;} }''',
'android/content/pm/PackageManager.java': '''package android.content.pm;
import android.content.Intent; import java.util.*;
public class PackageManager { public List<ResolveInfo> handlers=new ArrayList<>(); public Intent query;
public List<ResolveInfo> queryIntentActivities(Intent i,int f){query=i;return handlers;} }''',
'android/app/Activity.java': '''package android.app;
import android.content.*; import android.content.pm.*;
public class Activity { public PackageManager pm=new PackageManager(); public Intent launched;
public boolean race,denied; public int attempts;
public PackageManager getPackageManager(){return pm;}
public void startActivity(Intent i){attempts++;if(denied)throw new SecurityException();
if(race&&i.component!=null)throw new ActivityNotFoundException();launched=i;} }''',
'android/widget/Toast.java': '''package android.widget;
import android.app.Activity;
public class Toast { public static final int LENGTH_LONG=1; public static String last;
public static Toast makeText(Activity a,CharSequence t,int d){last=t.toString();return new Toast();} public void show(){} }''',
'com/github/tvbox/osc/skybox/StreamProxyService.java': '''package com.github.tvbox.osc.skybox;
import android.app.Activity; import java.util.Map; import java.io.IOException;
public class StreamProxyService {
public static Map<String,String> lastHeaders; public static String lastUrl, result;
public static String prepare(Activity a,String u,Map<String,String> h) throws IOException {
lastHeaders=h;lastUrl=u;return result==null?u:result;}
}''',
'HandoffContractTest.java': '''import android.app.Activity;
import android.content.*; import android.content.pm.*; import android.widget.Toast;
import com.github.tvbox.osc.skybox.SkyboxPlayer;
import com.github.tvbox.osc.skybox.StreamProxyService;
public class HandoffContractTest {
static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
static ResolveInfo app(String pkg,String label){ResolveInfo r=new ResolveInfo();r.activityInfo.packageName=pkg;r.activityInfo.name="VideoActivity";r.label=label;return r;}
public static void main(String[] args){
String url="https://example.test/中文%20name.m3u8?token=a%2Bb%3D&expires=999#frag";
Activity a=new Activity(); a.pm.handlers.add(app("vendor.vr.video","SKYBOX"));
check(SkyboxPlayer.play(a,url,"测试 第1集",null),"recognized SKYBOX launch");
check(a.launched.component.pkg.equals("vendor.vr.video"),"discover store-specific package by label");
check(a.launched.data.toString().equals(url),"signed URL must remain unchanged");
check(a.launched.type.equals("video/mp4")&&a.launched.action.equals(Intent.ACTION_VIEW),"Kazumi MIME and action");
check(a.launched.title.equals("测试 第1集"),"episode title");
a=new Activity(); a.pm.handlers.add(app("vendor.unknown","VR Player"));
check(SkyboxPlayer.play(a,url,null,null)&&a.launched.inner!=null,"unknown variant is selectable in system picker");
check(a.launched.inner.component==null&&a.launched.inner.data.toString().equals(url),"chooser preserves untargeted URL");
a=new Activity();a.pm.handlers.add(app("one.skybox","One"));a.pm.handlers.add(app("two.skybox","Two"));
check(SkyboxPlayer.play(a,url,null,null)&&a.launched.inner!=null,"ambiguous versions require choice");
a=new Activity();check(!SkyboxPlayer.play(a,url,null,null)&&a.launched==null,"missing external player returns failure");
check(Toast.last.contains("SKYBOX"),"missing player gives useful message");
a=new Activity();a.pm.handlers.add(app("one.skybox","SKYBOX"));a.race=true;
check(SkyboxPlayer.play(a,url,null,null)&&a.attempts==2&&a.launched.inner!=null,"package removal race falls back to chooser");
a=new Activity();a.pm.handlers.add(app("one.skybox","SKYBOX"));a.denied=true;
check(!SkyboxPlayer.play(a,url,null,null),"security denial handled without crash");
a=new Activity();ResolveInfo disabled=app("one.skybox","SKYBOX");disabled.activityInfo.enabled=false;a.pm.handlers.add(disabled);a.pm.handlers.add(app("vendor.other","Other Player"));
check(SkyboxPlayer.play(a,url,null,null)&&a.launched.inner!=null&&a.launched.inner.component==null,"disabled SKYBOX is not selected directly");
a=new Activity();check(!SkyboxPlayer.play(a,"",null,null)&&a.attempts==0,"empty URL rejected");
check(!SkyboxPlayer.play(null,url,null,null),"detached activity handled");
a=new Activity();a.pm.handlers.add(app("one.skybox","SKYBOX"));
java.util.HashMap<String,String> auth=new java.util.HashMap<>();auth.put("Referer","https://source.example/");
StreamProxyService.result="http://127.0.0.1:1234/session/media.m3u8";
check(SkyboxPlayer.play(a,url,"title",auth),"relay handoff");
check(StreamProxyService.lastHeaders==auth&&StreamProxyService.lastUrl.equals(url),"all original headers reach relay with signed URL intact");
check(a.launched.data.toString().equals(StreamProxyService.result),"SKYBOX receives local media endpoint");
System.out.println("PASS: 10 handoff scenarios (JVM Android API doubles; no headset playback test).");
} }'''
}
paths = []
for name, source in sources.items():
    path = work / 'src' / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding='utf-8')
    paths.append(str(path))
out = work / 'classes'
out.mkdir(parents=True, exist_ok=True)
helper = root / 'src/com/github/tvbox/osc/skybox/SkyboxPlayer.java'
subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')) if os.environ.get('JAVA_HOME') else shutil.which('javac'), '--release', '8', '-Xlint:-options', '-encoding', 'UTF-8', '-d', str(out), *paths, str(helper)], check=True)
subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if os.environ.get('JAVA_HOME') else shutil.which('java'), '-cp', str(out), 'HandoffContractTest'], check=True)
