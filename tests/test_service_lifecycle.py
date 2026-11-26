"""Execute the production service with Android API doubles and a real relay socket.

Checks cached playback after a virtual 31-minute gap, explicit stop, wake/Wi-Fi
lock release, empty-session cleanup, and diagnostics after service termination.
This is not an Android instrumentation or headset test.
"""
from pathlib import Path
import os, shutil, subprocess

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build/lifecycle-tests'
def tool(name):
    return str(Path(os.environ['JAVA_HOME'])/'bin'/(name+'.exe' if os.name=='nt' else name)) if os.environ.get('JAVA_HOME') else shutil.which(name)

sources = {
'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {String getString(String k,String d);boolean getBoolean(String k,boolean d);Editor edit();
interface Editor {Editor putBoolean(String k,boolean v);Editor putString(String k,String v);void apply();}}''',
'android/content/Context.java': '''package android.content;
import android.os.PowerManager;import android.net.wifi.WifiManager;import android.app.NotificationManager;import java.util.*;import java.io.File;
public class Context {
public static final String POWER_SERVICE="power",WIFI_SERVICE="wifi",NOTIFICATION_SERVICE="notification",CLIPBOARD_SERVICE="clipboard"; public static final int MODE_PRIVATE=0;
public static PowerManager power=new PowerManager();public static WifiManager wifi=new WifiManager();
public static class Prefs implements SharedPreferences,SharedPreferences.Editor {Map<String,Object> data=new HashMap<>();
public String getString(String k,String d){return (String)data.getOrDefault(k,d);}public boolean getBoolean(String k,boolean d){return (Boolean)data.getOrDefault(k,d);}
public SharedPreferences.Editor edit(){return this;}public SharedPreferences.Editor putBoolean(String k,boolean v){data.put(k,v);return this;}public SharedPreferences.Editor putString(String k,String v){data.put(k,v);return this;}public void apply(){}}
public static Prefs prefs=new Prefs();
public Object getSystemService(String s){if(s.equals(POWER_SERVICE))return power;if(s.equals(WIFI_SERVICE))return wifi;if(s.equals(NOTIFICATION_SERVICE))return new NotificationManager();return new ClipboardManager();}
public Context getApplicationContext(){return this;}public String getPackageName(){return "com.github.tvbox.osc.jun.skybox";}
public SharedPreferences getSharedPreferences(String n,int m){return prefs;}public File getFilesDir(){return new File(System.getProperty("test.work"));}
public ComponentName startService(Intent i){return new ComponentName();}public ComponentName startForegroundService(Intent i){return new ComponentName();}}
''',
'android/content/ComponentName.java':'package android.content;public class ComponentName {}',
'android/content/Intent.java':'''package android.content;public class Intent {private String action;public Intent(Context c,Class<?> k){}public Intent setAction(String a){action=a;return this;}public String getAction(){return action;}}''',
'android/content/DialogInterface.java':'package android.content;public interface DialogInterface {interface OnClickListener {void onClick(DialogInterface d,int which);}}',
'android/content/ClipboardManager.java':'package android.content;public class ClipboardManager {public void setPrimaryClip(ClipData d){}}',
'android/content/ClipData.java':'package android.content;public class ClipData {public static ClipData newPlainText(String l,String t){return new ClipData();}}',
'android/os/Build.java':'package android.os;public class Build {public static class VERSION {public static int SDK_INT=32;}}',
'android/os/IBinder.java':'package android.os;public interface IBinder {}',
'android/os/Looper.java':'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os;public class Handler {public Handler(Looper l){}public void postDelayed(Runnable r,long d){}public void removeCallbacksAndMessages(Object o){}}',
'android/os/PowerManager.java':'''package android.os;public class PowerManager {public static final int PARTIAL_WAKE_LOCK=1;public WakeLock last;
public WakeLock newWakeLock(int flags,String tag){return last=new WakeLock();}public static class WakeLock {public boolean held;public void setReferenceCounted(boolean b){}public void acquire(){held=true;}public boolean isHeld(){return held;}public void release(){held=false;}}}''',
'android/net/wifi/WifiManager.java':'''package android.net.wifi;public class WifiManager {public static final int WIFI_MODE_FULL_HIGH_PERF=3;public WifiLock last;
public WifiLock createWifiLock(int flags,String tag){return last=new WifiLock();}public static class WifiLock {public boolean held;public void setReferenceCounted(boolean b){}public void acquire(){held=true;}public boolean isHeld(){return held;}public void release(){held=false;}}}''',
'android/app/Activity.java':'package android.app;public class Activity extends android.content.Context {public boolean isFinishing(){return false;}}',
'android/app/Service.java':'''package android.app;import android.content.*;import android.os.IBinder;
public class Service extends Context {public static final int START_NOT_STICKY=2;public int stopped;public boolean foreground;
public void onCreate(){}public int onStartCommand(Intent i,int f,int id){return 2;}public IBinder onBind(Intent i){return null;}public void onDestroy(){}
public void stopSelf(){stopped++;}public void startForeground(int id,Notification n){foreground=true;}public void stopForeground(boolean b){foreground=false;}}''',
'android/app/AlertDialog.java':'''package android.app;import android.content.*;public class AlertDialog {public static int shown;public static String message;
public static class Builder {public Builder(Activity a){}public Builder setTitle(String s){return this;}public Builder setMessage(String s){message=s;return this;}
public Builder setPositiveButton(String s,DialogInterface.OnClickListener l){return this;}public Builder setNeutralButton(String s,DialogInterface.OnClickListener l){return this;}public void show(){shown++;}}}''',
'android/app/Notification.java':'''package android.app;import android.content.Context;public class Notification {public static class Builder {
public Builder(Context c){}public Builder(Context c,String s){}public Builder setSmallIcon(int i){return this;}public Builder setContentTitle(String s){return this;}public Builder setContentText(String s){return this;}public Builder setOngoing(boolean b){return this;}public Builder setContentIntent(PendingIntent i){return this;}public Builder addAction(int i,String s,PendingIntent p){return this;}public Notification build(){return new Notification();}}}''',
'android/app/NotificationManager.java':'package android.app;public class NotificationManager {public static final int IMPORTANCE_LOW=2;public void createNotificationChannel(NotificationChannel c){}}',
'android/app/NotificationChannel.java':'package android.app;public class NotificationChannel {public NotificationChannel(String id,String name,int level){}}',
'android/app/PendingIntent.java':'''package android.app;import android.content.*;public class PendingIntent {public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;
public static PendingIntent getActivity(Context c,int i,Intent n,int f){return new PendingIntent();}public static PendingIntent getService(Context c,int i,Intent n,int f){return new PendingIntent();}}''',
'android/widget/Toast.java':'package android.widget;import android.app.Activity;public class Toast {public static final int LENGTH_SHORT=0;public static Toast makeText(Activity a,String s,int n){return new Toast();}public void show(){}}',
'android/R.java':'package android;public class R {public static class drawable {public static int stat_sys_download=1,ic_menu_close_clear_cancel=2;}}',
'com/github/tvbox/osc/skybox/StreamDiagnosticsActivity.java':'package com.github.tvbox.osc.skybox;public class StreamDiagnosticsActivity extends android.app.Activity {}',
'com/github/tvbox/osc/skybox/ServiceLifecycleTest.java':r'''package com.github.tvbox.osc.skybox;
import java.lang.reflect.*;import java.net.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
import android.app.*;import android.content.*;
public class ServiceLifecycleTest {
static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
static class Clock implements StreamRelay.Clock {long offset;public long nanoTime(){return System.nanoTime()+offset;}void minutes(int n){offset+=n*60L*1000000000L;}}
static void setRelay(StreamRelay r)throws Exception {Field f=StreamProxyService.class.getDeclaredField("relay");f.setAccessible(true);f.set(null,r);}
static void tick(StreamProxyService s)throws Exception {Field f=StreamProxyService.class.getDeclaredField("idleCheck");f.setAccessible(true);((Runnable)f.get(s)).run();}
public static void main(String[] args)throws Exception {
final ServerSocket origin=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));
Thread server=new Thread(new Runnable(){public void run(){try(Socket socket=origin.accept()){
BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.ISO_8859_1));
for(String l;(l=input.readLine())!=null&&l.length()>0;){}
OutputStream out=socket.getOutputStream();out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: 4\r\nConnection: close\r\n\r\nDATA".getBytes(StandardCharsets.ISO_8859_1));out.flush();
}catch(Exception e){throw new RuntimeException(e);}}});server.setDaemon(true);server.start();
Clock clock=new Clock();StreamRelay relay=new StreamRelay("http://127.0.0.1:"+origin.getLocalPort()+"/movie.mp4",Collections.<String,String>emptyMap(),clock);
try(InputStream in=new URL(relay.mediaUrl()).openStream()){while(in.read()!=-1){}}
clock.minutes(31);setRelay(relay);
StreamProxyService service=new StreamProxyService();service.onCreate();
check(Context.power.last.isHeld()&&Context.wifi.last.isHeld(),"foreground service acquires CPU and Wi-Fi protection");
tick(service);check(service.stopped==0&&!relay.isClosed(),"31-minute cached gap must keep session open");
service.onStartCommand(new Intent(service,StreamProxyService.class).setAction("com.github.tvbox.osc.skybox.STOP"),0,1);
check(service.stopped==1,"explicit stop requests service shutdown");service.onDestroy();
check(!Context.power.last.isHeld()&&!Context.wifi.last.isHeld(),"stop releases both resources");
check(relay.isClosed(),"stop closes relay");check(StreamProxyService.diagnostic().contains("用户点击停止"),"close reason retained");
check(!Context.prefs.getBoolean("session_open",true),"clean stop persisted");
StreamProxyService.onReturn(new Activity());check(AlertDialog.shown==1&&AlertDialog.message.contains("用户点击停止"),"closed relay diagnostics remain visible");
Clock blankClock=new Clock();StreamRelay blank=new StreamRelay("http://127.0.0.1:1/none.mp4",Collections.<String,String>emptyMap(),blankClock);
setRelay(blank);StreamProxyService emptyService=new StreamProxyService();emptyService.onCreate();blankClock.minutes(3);tick(emptyService);
check(emptyService.stopped==1,"never-started empty session is cleaned up");emptyService.onDestroy();
check(!Context.power.last.isHeld()&&!Context.wifi.last.isHeld(),"empty session cleanup releases resources");
origin.close();server.join(1000);
System.out.println("PASS: service lifecycle, virtual 31-minute buffering gap, explicit stop, lock cleanup and retained diagnostics.");
}}
'''
}
paths=[]
for name, source in sources.items():
    path=WORK/'src'/name
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(source,encoding='utf-8');paths.append(str(path))
classes=WORK/'classes';classes.mkdir(parents=True,exist_ok=True)
production=ROOT/'src/com/github/tvbox/osc/skybox'
subprocess.run([tool('javac'),'--release','8','-Xlint:-options','-encoding','UTF-8','-d',str(classes),*paths,str(production/'StreamRelay.java'),str(production/'StreamProxyService.java')],check=True)
subprocess.run([tool('java'),'-Dtest.work='+str(WORK),'-cp',str(classes),'com.github.tvbox.osc.skybox.ServiceLifecycleTest'],check=True)
